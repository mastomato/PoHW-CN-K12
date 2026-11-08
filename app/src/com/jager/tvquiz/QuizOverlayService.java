package com.jager.tvquiz;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.List;

/**
 * 常驻前台服务：倒计时到点后直接由 WindowManager 添加 TYPE_APPLICATION_OVERLAY 全屏窗口。
 * 关键：加窗口不是 startActivity，因此不受 Android 10+ 后台启动 Activity(BAL) 限制。
 *
 * 审查加固（v1.0.0 首个正式版）：
 *  - FGS 契约：onStartCommand 开头无条件 startForeground()，覆盖 STOP/拒绝/权限不足/
 *    startForeground 抛异常等所有分支，避免 Android 8-11 ANR 与 12+ DidNotStartInTime。
 *  - STOP 授权：不再信任客户端传来的 verified boolean，改由本进程签发一次性令牌。
 *  - 窗口焦点：遮罩失焦时有限次 updateViewLayout 重抢 + 一次 remove/add 重建，
 *    修复「第二次弹题遥控器选不中」在通知栏/系统对话框抢焦点后的等价路径。
 *  - 判题：答题状态统一由 QuizFlow 推进，服务不再单独维护 wrongAttempts。
 */
public class QuizOverlayService extends Service {

    public static final String ACTION_START = "com.jager.tvquiz.START";
    public static final String ACTION_STOP = "com.jager.tvquiz.STOP";
    public static final String ACTION_QUIZ_NOW = "com.jager.tvquiz.QUIZ_NOW";
    public static final String ACTION_RELOAD = "com.jager.tvquiz.RELOAD";
    public static final String ACTION_SYNC = "com.jager.tvquiz.SYNC";
    /** 一次性 STOP 令牌；绝不把「已验证」布尔值作为授权依据。 */
    public static final String EXTRA_STOP_TOKEN = "com.jager.tvquiz.STOP_TOKEN";

    private static final String TAG = "PoHW.Guard";
    private static final int NOTIF_ID = 1;
    private static final String CHANNEL = "tvquiz_guard";
    private static final int FOCUS_RETRY_MAX = 6;

    private static volatile boolean running = false;
    private static volatile boolean quizShowing = false;
    private static volatile int remainSec = 0;

    public static boolean isRunning() { return running; }
    public static boolean isQuizShowing() { return quizShowing; }
    public static int remainSec() { return remainSec; }

    /**
     * 统一关闭入口。verified=true 表示调用方已完成家长密码门禁；
     * 此时由本进程签发一次性令牌，服务侧消费后即作废，客户端无法伪造。
     */
    public static void requestStop(Context c, boolean verified) {
        Intent i = new Intent(c, QuizOverlayService.class);
        i.setAction(ACTION_STOP);
        if (verified) {
            i.putExtra(EXTRA_STOP_TOKEN, new SettingsStore(c).issueStopToken());
        }
        try {
            // API 26+ 用 startForegroundService，避免 App 处于后台时 startService 被拒；
            // 服务侧任何分支都会先 startForeground()，满足 5 秒契约。
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            Toast.makeText(c, "关闭守护失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private WindowManager wm;
    private OverlayQuizView quizView;
    private MediaPauseController media;
    private QuizEngine engine;
    private QuizEngine.Question current;
    private final QuizRound round = new QuizRound();
    private SettingsStore st;
    private SoundPool sounds;
    private int correctSound, wrongSound, completeSound;
    private int interval = 30;
    private String lastKey = "";
    private long nextSyncAt = 0L;
    /**
     * 本次弹题会话内累计答对题数。与 QuizRound.correct() 分开记账：
     * 开新一轮会 reset 轮次成绩，但退出门禁看的是整个会话，不能倒退重新上锁。
     */
    private int sessionCorrect = 0;
    /** 本次弹题读取的家长设定阈值；换题不变，下次弹题重新读。 */
    private int requiredCorrect = ExitPolicy.DEFAULT_REQUIRED_CORRECT;
    /** 家长通过密码验证后的单次放行，用完即清。 */
    private boolean parentOverride = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 窗口焦点重抢状态机
    private int focusRetry = 0;
    private boolean focusRecovering = false;
    private boolean overlayRebuilt = false;

    private final OverlayQuizView.Listener listener = new OverlayQuizView.Listener() {
        @Override public void onAnswer(int idx) { answer(idx); }
        @Override public void onNext() { nextQuestion(); }
        @Override public void onNewRound() { newRound(); }
        @Override public void onExit() { finishQuiz(); }
        @Override public void onParentUnlock() {
            handler.post(new Runnable() {
                @Override public void run() { showParentGate(); }
            });
        }
        @Override public void onParentCancel() { hideParentGate(); }
        @Override public void onWindowFocus(boolean hasFocus) { onQuizWindowFocus(hasFocus); }
    };

    private final Runnable focusCheck = new Runnable() {
        @Override public void run() {
            if (!quizShowing || quizView == null) return;
            if (quizView.hasWindowFocus()) {
                focusRetry = 0;
                focusRecovering = false;
                quizView.focusFirst();
                return;
            }
            focusRecovering = true;
            if (focusRetry < FOCUS_RETRY_MAX) {
                focusRetry++;
                // 重新下发 LayoutParams 会强制 WMS 重新评估 focused window；
                // 仅 requestFocus() 只能改 View 树焦点，抢不回窗口焦点。
                try { wm.updateViewLayout(quizView.view(), buildParams()); }
                catch (Exception e) { Log.w(TAG, "focus updateViewLayout failed", e); }
                handler.postDelayed(this, 200);
                return;
            }
            if (!overlayRebuilt) {
                overlayRebuilt = true;
                rebuildOverlay();
                focusRetry = 0;
                handler.postDelayed(this, 300);
                return;
            }
            // 有限重试 + 一次重建都无法夺回窗口焦点：停止自旋，保留遮罩。
            // 不自动放行答题（否则拉下通知栏即可绕过守护），触摸/鼠标点击仍可作答。
            Log.w(TAG, "cannot regain overlay window focus; keeping quiz visible");
            focusRecovering = false;
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!quizShowing) {
                remainSec--;
                if (remainSec <= 0) showQuiz();
            } else {
                // 未满足退出门禁时，遮罩不许消失：HOME / 系统对话框偶尔会把覆盖窗口
                // 摘掉，这里每秒巡检一次，掉线就重建，同时再压一次暂停信号。
                if (!parentOverride && !ExitPolicy.canExit(sessionCorrect, requiredCorrect)
                        && quizView != null && !quizView.isAttached()) {
                    rebuildOverlay();
                    if (media != null) media.reassert();
                }
            }
            long now = System.currentTimeMillis();
            if (now >= nextSyncAt && !st.bankUrl().isEmpty()) {
                nextSyncAt = now + st.syncIntervalMs();
                syncNow(false);
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        st = new SettingsStore(this);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        media = new MediaPauseController(this);
        try {
            sounds = new SoundPool(3, AudioManager.STREAM_MUSIC, 0);
            correctSound = sounds.load(this, R.raw.correct, 1);
            wrongSound = sounds.load(this, R.raw.wrong, 1);
            completeSound = sounds.load(this, R.raw.complete, 1);
        } catch (RuntimeException e) { Log.w(TAG, "sound effects unavailable", e); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // FGS 5 秒契约：只要是被 startForegroundService() 拉起（含 ACTION_STOP、开机自启），
        // 无论最终走哪个分支，都必须先 startForeground()。把 startForeground 放在
        // startGuard() 里会导致「权限不足 / 拒绝关闭 / 构造通知失败」分支裸奔。
        boolean fgReady = enterForeground();

        String action = intent == null ? ACTION_START : intent.getAction();
        if (action == null) action = ACTION_START;

        if (ACTION_STOP.equals(action)) {
            String token = intent == null ? null : intent.getStringExtra(EXTRA_STOP_TOKEN);
            boolean verified = st.consumeStopToken(token);
            if (!GuardPolicy.allowStop(st.hasPin(), verified)) {
                Toast.makeText(this, "已设置家长密码：请在 APP 内验证后才能关闭守护",
                        Toast.LENGTH_LONG).show();
                if (!running) { leaveForeground(); stopSelf(); }
                return running ? START_STICKY : START_NOT_STICKY;
            }
            stopAll();
            return START_NOT_STICKY;
        }

        if (!fgReady) {
            Log.e(TAG, "startForeground failed, guard cannot stay resident");
            Toast.makeText(this, "前台服务启动失败，答题守护无法常驻", Toast.LENGTH_LONG).show();
            st.guardEnabled(false);
            leaveForeground();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!running) startGuard();
        if (!running) { leaveForeground(); stopSelf(); return START_NOT_STICKY; }

        if (ACTION_QUIZ_NOW.equals(action)) {
            showQuiz();
        } else if (ACTION_RELOAD.equals(action)) {
            reload(false);
            if (!quizShowing) remainSec = Math.min(remainSec, interval);
        } else if (ACTION_SYNC.equals(action)) {
            syncNow(true);
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(focusCheck);
        hideQuiz();
        if (media != null) media.release();
        if (sounds != null) { sounds.release(); sounds = null; }
        running = false;
        // 系统/force-stop 路径不会走 stopAll()，这里兜底显式退出前台。
        leaveForeground();
        super.onDestroy();
    }

    /** 所有分支共用的 FGS 进入点；失败返回 false，由调用方决定立即停止。 */
    private boolean enterForeground() {
        try {
            startForeground(NOTIF_ID, buildNotification());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
            return false;
        }
    }

    private void leaveForeground() {
        try { stopForeground(true); } catch (Exception e) { Log.w(TAG, "stopForeground failed", e); }
    }

    private boolean canDraw() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return Settings.canDrawOverlays(this);
    }

    private void startGuard() {
        if (!canDraw()) {
            Toast.makeText(this, "请先授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show();
            st.guardEnabled(false);
            return;
        }
        running = true;
        st.guardEnabled(true);
        reload(true);
        remainSec = interval;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1000);
        Toast.makeText(this, "答题守护已开启，切到其他应用也会按时弹题", Toast.LENGTH_SHORT).show();
    }

    private void stopAll() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(focusCheck);
        hideQuiz();
        if (media != null) media.release();
        running = false;
        st.guardEnabled(false);
        leaveForeground();
        stopSelf();
        Toast.makeText(this, "答题守护已关闭", Toast.LENGTH_SHORT).show();
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    getString(R.string.guard_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.guard_notification_text));
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_quiz)
                .setContentTitle(getString(R.string.guard_notification_title))
                .setContentText(getString(R.string.guard_notification_text))
                .setOngoing(true);
        return b.build();
    }

    private String[] editions() {
        String[] out = new String[Subjects.COUNT];
        for (int i = 0; i < out.length; i++) out[i] = st.editionFor(i);
        return out;
    }

    private void reload(boolean first) {
        if (first) BankRepository.installBuiltin(this, st);
        interval = st.intervalSec();
        engine = new QuizEngine(st.grade(), st.edition(),
                st.weight(Subjects.MATH), st.weight(Subjects.CHINESE),
                st.weight(Subjects.ENGLISH), st.weight(Subjects.OTHER));
        engine.setAllSubjects(st.allSubjects());
        engine.setTerm(st.termFilter());
        QuizDb cached = QuizDb.peek();
        if (cached != null) {
            engine.setDb(cached);
        } else {
            // 首启复制 ~4.5MB 题库、创建错题库，都放到后台线程。
            final Context app = getApplicationContext();
            new Thread(new Runnable() {
                @Override public void run() {
                    final QuizDb opened = QuizDb.open(app);
                    WrongBook.open(app);
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (running && engine != null) engine.setDb(opened);
                        }
                    });
                }
            }, "pohw-db-open").start();
        }
        List<QuizEngine.Question> qs = BankRepository.load(this, st.grade(), editions());
        engine.setBank(qs);
        nextSyncAt = System.currentTimeMillis() + (first ? 5000L : st.syncIntervalMs());
    }

    private void syncNow(final boolean force) {
        BankRepository.sync(getApplicationContext(), st, st.bankUrl(), force,
                new BankRepository.Callback() {
                    @Override public void onDone(BankRepository.SyncResult r) {
                        if (running) reload(false);
                    }
                });
    }

    /** 每次弹题都换一道与上一题不同的题；单题题库会退回同一题，由 answer() 处理。 */
    private QuizEngine.Question drawFresh() {
        QuizEngine.Question q = null;
        for (int i = 0; i < 8; i++) {
            if (engine == null) break;
            q = engine.next();
            if (q == null) break;
            String k = q.key();
            if (!k.equals(lastKey)) {
                lastKey = k;
                return q;
            }
        }
        if (q != null) lastKey = q.key();
        return q;
    }

    private String metaFor(QuizEngine.Question q) {
        return st.gradeLabel() + " · " + Subjects.name(q.subject) + " "
                + st.editionFor(q.subject);
    }

    private void showQuiz() {
        if (quizShowing) return;
        if (!canDraw()) { stopAll(); return; }
        if (engine == null) reload(false);
        current = drawFresh();
        if (current == null) {
            remainSec = interval;
            Toast.makeText(this, "题库暂不可用，稍后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        round.reset(st.roundCount());
        // 新一次弹题重置会话门禁：上次的答对成绩不继承。
        sessionCorrect = 0;
        requiredCorrect = st.requiredCorrect();
        parentOverride = false;
        st.bumpQuestioned();

        // 不复用 View：反复 add/remove 会让窗口焦点状态不可预测。
        final OverlayQuizView view = new OverlayQuizView(this, listener);
        view.setExitGate(requiredCorrect, sessionCorrect, st.hasPin());
        view.bind(current, metaFor(current), round.questionNumber(), round.total());
        quizView = view;
        focusRetry = 0;
        focusRecovering = false;
        overlayRebuilt = false;
        handler.removeCallbacks(focusCheck);
        try {
            wm.addView(view.view(), buildParams());
            quizShowing = true;
            view.view().post(new Runnable() {
                @Override public void run() {
                    if (quizShowing && quizView == view) view.focusFirst();
                }
            });
        } catch (Exception e) {
            // addView 成功后若出异常，必须无条件移除，否则遮罩会永久盖住系统界面
            quizShowing = false;
            removeOverlayNow();
            remainSec = interval;
            Toast.makeText(this, "无法显示答题遮罩：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (media != null) media.quizShown(st);
    }

    private WindowManager.LayoutParams buildParams() {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT);
        // 显式声明窗口可聚焦（new LayoutParams 默认即不带 NOT_FOCUSABLE）。
        // 注意：这行不「保证」WMS 一定把 focused window 给遮罩——通知栏/系统对话框仍可抢走，
        // 真正的兜底是 onQuizWindowFocus() + focusCheck 的有限次重抢与窗口重建。
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.dimAmount = 0.88f;
        return lp;
    }

    private void onQuizWindowFocus(boolean hasFocus) {
        if (!quizShowing || quizView == null) return;
        if (hasFocus) {
            handler.removeCallbacks(focusCheck);
            focusRetry = 0;
            focusRecovering = false;
            quizView.focusFirst();
            // 覆盖层被系统弹窗压过再回来时，播放器可能已自行续播，补压一次。
            if (media != null) media.reassert();
            return;
        }
        // focusCheck 执行期间（updateViewLayout / remove+add）自身会触发 false 回调，
        // 用 focusRecovering 过滤嵌套回调，避免无限自旋。
        if (focusRecovering) return;
        handler.removeCallbacks(focusCheck);
        handler.postDelayed(focusCheck, 200);
    }

    /** remove + add 同一个 View 实例，保留 QuizFlow 状态，同时让 WMS 重建 ViewRootImpl。 */
    private void rebuildOverlay() {
        if (wm == null || quizView == null) return;
        final View v = quizView.view();
        try { wm.removeViewImmediate(v); } catch (Exception e) { Log.w(TAG, "rebuild remove failed", e); }
        try {
            wm.addView(v, buildParams());
            v.post(new Runnable() {
                @Override public void run() {
                    if (quizShowing && quizView != null) quizView.focusFirst();
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "rebuild add failed", e);
        }
    }

    /** 答对自动进入下一题；答错展示解析，等待用户按确定继续。 */
    private void answer(int idx) {
        if (!quizShowing || current == null || quizView == null) return;
        QuizFlow flow = quizView.flow();
        int outcome = flow.submit(idx, current.answerIndex);
        if (outcome == QuizFlow.IGNORE) return;
        if (outcome == QuizFlow.CORRECT) {
            st.bumpCorrect();
            round.record(true);
            sessionCorrect++;
            // 累计答对数达到家长设定的阈值就立刻解锁返回键，不必做完一整轮。
            quizView.setExitGate(requiredCorrect, sessionCorrect, st.hasPin());
            play(correctSound);
            quizView.correct(current.answerIndex, current.explain);
            return;
        }
        recordWrong(idx);
        round.record(false);
        play(wrongSound);
        String answerText = current.options != null
                && current.answerIndex >= 0 && current.answerIndex < current.options.length
                ? current.options[current.answerIndex] : "";
        quizView.wrong(current.answerIndex, answerText, current.explain);
    }

    private void play(int id) {
        if (sounds != null && id != 0) sounds.play(id, .8f, .8f, 1, 0, 1f);
    }

    /** 题目切换只重绑内容，不摘下遮罩；本轮完成也留在答题页。 */
    private void nextQuestion() {
        if (!quizShowing || quizView == null) return;
        if (round.complete()) {
            play(completeSound);
            quizView.showRoundComplete(round.correct(), round.total());
            return;
        }
        current = drawFresh();
        if (current == null) {
            quizView.showRoundComplete(round.correct(), round.answered());
            return;
        }
        st.bumpQuestioned();
        quizView.bind(current, metaFor(current), round.questionNumber(), round.total());
    }

    private void newRound() {
        if (!quizShowing || quizView == null) return;
        round.reset(st.roundCount());
        current = drawFresh();
        if (current == null) {
            quizView.showRoundComplete(0, 0);
            return;
        }
        st.bumpQuestioned();
        quizView.bind(current, metaFor(current), round.questionNumber(), round.total());
    }

    private void recordWrong(int picked) {
        if (current == null) return;
        WrongBook.Entry e = WrongBook.of(current, st.grade(), st.gradeLabel(),
                st.editionFor(current.subject), picked);
        WrongBook.recordAsync(getApplicationContext(), e);
    }

    private void finishQuiz() {
        if (!quizShowing) return;
        // 第二道防线：即使 View 层被改坏 / 有别的路径调 onExit，服务也不放行。
        if (!parentOverride && !ExitPolicy.canExit(sessionCorrect, requiredCorrect)) {
            if (quizView != null) quizView.nudgeExit();
            return;
        }
        hideQuiz();
        if (media != null) media.quizFinished(st);
        remainSec = interval;
        parentOverride = false;
    }

    /** 家长应急出口：菜单键 / 数字 0 触发，密码正确才允许退出本次答题。 */
    private void showParentGate() {
        if (!quizShowing || quizView == null || quizView.hasParentGate()) return;
        if (!st.hasPin()) {
            Toast.makeText(this, "未设置家长密码：请在 APP 的家长设置里先设置",
                    Toast.LENGTH_LONG).show();
            return;
        }
        PinVerifyView gate = new PinVerifyView(this, st,
                new PinVerifyView.Callback() {
                    @Override public void onVerified() {
                        // 摘窗口必须离开按键分发栈后异步执行，和 requestExit 同理。
                        handler.post(new Runnable() {
                            @Override public void run() {
                                parentOverride = true;
                                if (quizView != null) quizView.hideParentGate();
                                finishQuiz();
                            }
                        });
                    }
                    @Override public void onCancel() {
                        handler.post(new Runnable() {
                            @Override public void run() { hideParentGate(); }
                        });
                    }
                },
                "家长解锁 · 请输入密码",
                "验证通过可退出本次答题；按返回键或点「取消」继续作答");
        quizView.showParentGate(gate.view());
        gate.requestFocusFirst();
    }

    private void hideParentGate() {
        if (quizView != null) quizView.hideParentGate();
    }

    private void hideQuiz() {
        handler.removeCallbacks(focusCheck);
        focusRecovering = false;
        quizShowing = false;
        if (quizView != null) quizView.cancelPending();
        // 不依赖 quizShowing 标志：标志异常时也兜底移除，避免残留窗口卡住卸载流程。
        removeOverlayNow();
    }

    /**
     * 无条件尝试移除答题窗口。removeViewImmediate 在「未 attach」时会抛异常，
     * 此时本就没有残留窗口；但为防「已 attach 却移除失败」，额外 post 一次异步
     * removeView，并始终保留 View 引用到该 Runnable 中，不会因置空而丢失。
     */
    private void removeOverlayNow() {
        if (wm == null || quizView == null) return;
        final View v = quizView.view();
        quizView = null;
        try {
            wm.removeViewImmediate(v);
        } catch (Exception e) {
            Log.w(TAG, "removeViewImmediate failed, retry async", e);
            handler.post(new Runnable() {
                @Override public void run() {
                    try { wm.removeView(v); } catch (Exception ignored) { }
                }
            });
        }
    }
}
