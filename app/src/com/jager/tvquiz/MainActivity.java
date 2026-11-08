package com.jager.tvquiz;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 控制台：悬浮窗授权、守护开关、立即出题、错题库、开机自启设置与题库状态。
 *  在线题库的检查更新入口统一放在「家长设置」，首页只保留只读状态信息。 */
public class MainActivity extends Activity {

    private static final String NL = "\n";
    private SettingsStore st;
    private TextView status, statusRight, heroStatus, guardDetail;
    private FrameLayout rootView;
    private Button btnGuard, btnWrong, btnBoot;
    /** 首页 3 列 × 2 排网格：显式维护同列上下 / 同行左右的 TV 遥控器焦点链。 */
    private static final int GRID_COLS = 3;
    private final java.util.List<Button[]> gridRows = new java.util.ArrayList<Button[]>();
    private PinVerifyView verifyView;
    private Boolean paintedRunning = null;
    private String appVersion = "?";
    private String appVersionCode = "?";
    private boolean alive = false;
    /** 悬浮窗授权只在 onResume / 权限回调刷新，render() 不再每秒查系统服务。 */
    private boolean canDrawCached = false;
    /** 题库聚合行按 QuizDb 实例 + 年级缓存，避免每秒重复取 5 个统计量。 */
    private QuizDb cachedDbRef = null;
    private int cachedBankGrade = -1;
    private String cachedBankLine = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 一次性刷新全部复用同一实例，onDestroy 可统一撤销，不再泄漏匿名 Runnable。 */
    private final Runnable renderOnce = new Runnable() {
        @Override public void run() { if (alive) render(); }
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!alive) return;
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        alive = true;
        st = new SettingsStore(this);
        canDrawCached = canDraw();
        preloadBank();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                   != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
        readVersion();
        setContentView(buildView());
        render();
        // 「启动即守护」：只在冷启动（savedInstanceState 为空）触发；从后台返回前台
        // 走 onResume，不会把家长手动关闭的守护又自动打开。
        if (LaunchPolicy.shouldAutoGuard(b == null, st.autoGuardOnBoot(),
                canDrawCached, QuizOverlayService.isRunning())) {
            send(QuizOverlayService.ACTION_START);
            Toast.makeText(this, "已按设置自动开启守护", Toast.LENGTH_SHORT).show();
            handler.postDelayed(renderOnce, 300);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        alive = true;
        canDrawCached = canDraw();
        refreshWrongBook();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

    @Override protected void onDestroy() {
        alive = false;
        handler.removeCallbacksAndMessages(null);
        verifyView = null;
        super.onDestroy();
    }

    /** 就地验证层显示时，数字键/退格/返回键由本 Activity 统一转发。 */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (verifyView != null && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK) { verifyView.cancel(); return true; }
            if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                verifyView.key(String.valueOf(code - KeyEvent.KEYCODE_0));
                return true;
            }
            if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) {
                verifyView.key(String.valueOf(code - KeyEvent.KEYCODE_NUMPAD_0));
                return true;
            }
            if (code == KeyEvent.KEYCODE_DEL) { verifyView.key("退格"); return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    /** 版本一律从 PackageManager 读真实安装包信息，禁止再硬编码。 */
    private void readVersion() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            appVersion = pi.versionName == null ? "?" : pi.versionName;
            appVersionCode = String.valueOf(pi.versionCode);
        } catch (Exception e) {
            appVersion = "?";
            appVersionCode = "?";
        }
    }

    /** 首次启动复制 ~4.5MB 题库并初始化错题库，都放到后台线程。 */
    private void preloadBank() {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                QuizDb.open(app);
                BankRepository.installBuiltin(app, st);
                WrongBook.refreshAsync(app);
                handler.post(renderOnce);
            }
        }, "pohw-db-preload").start();
    }

    /** 从错题库页面返回后，后台刷新缓存计数与最近错题摘要，完成后回主线程渲染一次。 */
    private void refreshWrongBook() {
        WrongBook.refreshAsync(getApplicationContext(), new WrongBook.RefreshCallback() {
            @Override public void onLoaded(java.util.List<WrongBook.Entry> entries, int total) {
                if (alive) render();
            }
        });
    }

    @Override public void onRequestPermissionsResult(int req, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(req, permissions, grantResults);
        if (req == 100 && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "未授予通知权限：守护仍会运行，但状态栏通知不可见",
                    Toast.LENGTH_LONG).show();
        }
        canDrawCached = canDraw();
    }

    private boolean canDraw() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return Settings.canDrawOverlays(this);
    }

    private void send(String action) {
        Intent i = new Intent(this, QuizOverlayService.class);
        i.setAction(action);
        try {
            if (!QuizOverlayService.isRunning() && Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            Toast.makeText(this, "服务启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT < 23) return;
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        try { startActivity(i); }
        catch (Exception e) { Toast.makeText(this, "该设备没有悬浮窗授权入口", Toast.LENGTH_LONG).show(); }
    }

    private Button actionButton(LinearLayout row, String label, int bg, View.OnClickListener l) {
        int fg = Ui.isLightButton(bg) ? Ui.TEXT : Ui.CARD;
        Button b = Ui.button(this, label, bg, fg, 20);
        b.setOnClickListener(l);
        Ui.focus(b, this, bg, Ui.PURPLE_DARK, fg);
        // 三列等宽：不写死 190dp，任意屏宽都对齐成 3 列。
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(this, 68), 1f);
        p.setMargins(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        row.addView(b, p);
        return b;
    }

    /** 3×2 网格的 TV DPAD 焦点：上下落在同列，左右落在同行，边缘不绕回。 */
    private void wireGrid() {
        for (int r = 0; r < gridRows.size(); r++) {
            Button[] row = gridRows.get(r);
            for (int c = 0; c < row.length; c++) {
                Button b = row[c];
                if (b == null) continue;
                if (c > 0) b.setNextFocusLeftId(ensureId(row[c - 1]));
                if (c < row.length - 1) b.setNextFocusRightId(ensureId(row[c + 1]));
                if (r > 0) b.setNextFocusUpId(ensureId(gridRows.get(r - 1)[c]));
                if (r < gridRows.size() - 1) b.setNextFocusDownId(ensureId(gridRows.get(r + 1)[c]));
            }
        }
    }

    private static int ensureId(View v) {
        if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
        return v.getId();
    }

    private View buildView() {
        rootView = new FrameLayout(this);
        LinearLayout l = Ui.vbox(this);
        l.setBackgroundColor(Ui.BG);
        l.setGravity(Gravity.TOP);
        l.setPadding(Ui.dp(this, 44), Ui.dp(this, 24), Ui.dp(this, 44), Ui.dp(this, 16));

        TextView title = Ui.tv(this, "知行答题守护", 38, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        l.addView(title, Ui.fixed(this, 64));
        l.addView(Ui.tv(this, "电视答题练习  ·  应用版本 " + appVersion,
                18, Ui.MUTED), Ui.fixed(this, 42));

        LinearLayout cards = Ui.hbox(this);
        cards.setGravity(Gravity.CENTER_VERTICAL);
        heroStatus = Ui.tv(this, "", 32, Ui.PURPLE, Gravity.LEFT);
        heroStatus.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        guardDetail = Ui.tv(this, "", 22, Ui.TEXT, Gravity.LEFT);
        addSummaryCard(cards, "守护状态", heroStatus, guardDetail);
        status = Ui.tv(this, "", 22, Ui.TEXT, Gravity.LEFT);
        status.setLineSpacing(Ui.dp(this, 8), 1.15f);
        addSummaryCard(cards, "学习配置", status);
        statusRight = Ui.tv(this, "", 22, Ui.TEXT, Gravity.LEFT);
        statusRight.setLineSpacing(Ui.dp(this, 8), 1.15f);
        addSummaryCard(cards, "题库与设备", statusRight);
        l.addView(cards, Ui.fill(1));

        // 首页 3 列 × 2 排：上排高频操作，下排权限 / 数据 / 厂商入口。
        // 单一守护开关的文案与底色仍由 GuardState.toggle* 给出；运行中点击先过
        // 就地 PIN 验证层，界面不退出、不跳转。
        LinearLayout row1 = Ui.hbox(this);
        Button[] r1 = new Button[GRID_COLS];
        r1[0] = btnGuard = actionButton(row1, GuardState.toggleLabel(false),
                GuardState.toggleColor(false),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { toggleGuard(); }
                });
        r1[1] = actionButton(row1, "立即出题", Ui.PURPLE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!canDraw()) { openOverlaySettings(); return; }
                send(QuizOverlayService.ACTION_QUIZ_NOW);
            }
        });
        r1[2] = actionButton(row1, "家长设置", Ui.CARD, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        l.addView(row1, Ui.fixed(this, 78));
        gridRows.add(r1);

        LinearLayout row2 = Ui.hbox(this);
        Button[] r2 = new Button[GRID_COLS];
        r2[0] = actionButton(row2, "授权悬浮窗", Ui.CARD, new View.OnClickListener() {
            @Override public void onClick(View v) { openOverlaySettings(); }
        });
        r2[1] = btnWrong = actionButton(row2, "错题库 · 0 题", Ui.CARD, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, WrongBookActivity.class));
            }
        });
        r2[2] = btnBoot = actionButton(row2, "厂商自启设置", Ui.CARD, new View.OnClickListener() {
            @Override public void onClick(View v) {
                BootSettings.open(MainActivity.this);
                Toast.makeText(MainActivity.this,
                        "请在厂商设置里允许本应用「自启动」，否则收不到开机广播",
                        Toast.LENGTH_LONG).show();
            }
        });
        l.addView(row2, Ui.fixed(this, 78));
        gridRows.add(r2);
        wireGrid();

        l.addView(Ui.tv(this,
                "开启守护后按 Home 切到任意应用，到点弹出全屏答题页；Home 键属系统级按键，第三方无法拦截。\n"
                        + "卸载前请先关闭守护；遮罩卡住可在电脑执行 adb shell am force-stop com.jager.tvquiz",
                16, Ui.MUTED), Ui.fixed(this, 56));

        rootView.addView(l, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return rootView;
    }

    private void addSummaryCard(LinearLayout row, String heading, TextView... values) {
        LinearLayout card = Ui.vbox(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 30), Ui.dp(this, 28), Ui.dp(this, 30), Ui.dp(this, 28));
        card.setBackground(Ui.round(Ui.CARD, Ui.RADIUS_DP, this));
        TextView caption = Ui.tv(this, heading, 20, Ui.MUTED, Gravity.LEFT);
        card.addView(caption);
        for (TextView value : values) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.topMargin = Ui.dp(this, 22);
            card.addView(value, p);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        p.setMargins(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 18));
        row.addView(card, p);
    }

    // ---------- 就地密码验证（不启动新 Activity、不 finish 本界面） ----------

    private void showVerify() {
        hideVerify();
        verifyView = new PinVerifyView(this, st, new PinVerifyView.Callback() {
            @Override public void onVerified() { doStop(); }
            @Override public void onCancel() {
                hideVerify();
                Toast.makeText(MainActivity.this, "已取消关闭守护", Toast.LENGTH_SHORT).show();
            }
        });
        // 浮层与控制台在同一 View 树里：显式 block 底层后代焦点，
        // DPAD/确认键才不会穿透到被遮住的「守护开关 / 立即出题」等按钮。
        View base = rootView.getChildAt(0);
        if (base instanceof ViewGroup) {
            ((ViewGroup) base).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        rootView.addView(verifyView.view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        verifyView.requestFocusFirst();
    }

    private void hideVerify() {
        if (rootView != null) {
            View base = rootView.getChildAt(0);
            if (base instanceof ViewGroup) {
                ((ViewGroup) base).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            }
        }
        if (verifyView != null) {
            try { rootView.removeView(verifyView.view()); } catch (Exception ignored) { }
            verifyView = null;
        }
    }

    /** 关闭动作：只移除验证层并请求服务停止，界面保持，按钮颜色随后刷新。 */
    private void doStop() {
        hideVerify();
        QuizOverlayService.requestStop(this, true);
        render();
        handler.postDelayed(renderOnce, 350);
    }

    /** 单一开关：未运行则开启，运行中则先过密码验证再关闭。 */
    private void toggleGuard() {
        if (QuizOverlayService.isRunning()) {
            // 已设密码：在当前界面叠加验证层，不跳转、不退出
            if (GuardPolicy.requiresPinToStop(st.hasPin())) showVerify();
            else doStop();
            return;
        }
        if (!canDraw()) { openOverlaySettings(); return; }
        send(QuizOverlayService.ACTION_START);
        handler.postDelayed(renderOnce, 300);
    }

    private void paintGuardButton(boolean running) {
        paintButton(btnGuard, GuardState.toggleColor(running), GuardState.toggleLabel(running));
    }

    private static void setButtonText(Button b, String label) {
        if (b != null && !label.equals(b.getText().toString())) b.setText(label);
    }

    private void paintButton(Button b, int bg, String label) {
        setButtonText(b, label);
        Ui.focus(b, this, bg, Ui.PURPLE_DARK, Ui.CARD);
        b.setBackground(Ui.round(bg, 10, this));
        b.setTextColor(Ui.CARD);
    }

    private static String ts(long ms) {
        if (ms <= 0) return "-";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ms));
    }

    /** 题库聚合行：按 QuizDb 实例与年级缓存，年级变化时才重新计算。 */
    private String bankLine() {
        QuizDb qd = QuizDb.peek();
        if (qd == null) return "正在加载…（首次启动需复制题库）";
        int grade = st.grade();
        if (qd != cachedDbRef || grade != cachedBankGrade) {
            cachedDbRef = qd;
            cachedBankGrade = grade;
            cachedBankLine = qd.count() + " 题 / " + qd.knowledgeCount() + " 知识点 · "
                    + qd.subjectCount() + " 学科 · 本年级 " + qd.countForGrade(grade) + " 题";
        }
        return cachedBankLine;
    }

    private void render() {
        if (!alive || status == null) return;
        boolean running = QuizOverlayService.isRunning();
        String heroText = running ? "守护运行中" : "守护已关闭";
        if (!heroText.equals(heroStatus.getText().toString())) heroStatus.setText(heroText);
        heroStatus.setTextColor(running ? Ui.PURPLE : Ui.MUTED);
        String guardText = (running
                ? (QuizOverlayService.isQuizShowing() ? "答题页已显示"
                        : "距离下次答题\n" + QuizOverlayService.remainSec() + " 秒")
                : "打开守护后，按设定间隔弹出答题页")
                + "\n\n开机自启：" + (st.bootAutostart() ? "开" : "关")
                + "\n启动即守护：" + (st.autoGuardOnBoot() ? "开" : "关");
        if (!guardText.equals(guardDetail.getText().toString())) guardDetail.setText(guardText);

        StringBuilder s = new StringBuilder();
        s.append(st.gradeLabel()).append(NL)
                .append("每 ").append(st.intervalSec()).append(" 秒一题").append(NL)
                .append("每轮 ").append(st.roundCount()).append(" 题").append(NL)
                .append("答对才能返回：").append(st.requiredCorrect())
                .append(" 题").append(NL);
        s.append("学科权重：")
                .append(st.allSubjects()
                        ? ("数/语/英/其他 " + st.weight(0) + "/" + st.weight(1)
                                + "/" + st.weight(2) + "/" + st.weight(3))
                        : ("数/语/英 " + st.weight(0) + "/" + st.weight(1) + "/" + st.weight(2)))
                .append(NL);
        s.append("数学教材：").append(st.editionFor(Subjects.MATH));
        StringBuilder right = new StringBuilder();
        right.append("离线题库：").append(bankLine()).append(NL)
                .append("答对 ").append(st.correct()).append(" / ").append(st.questioned())
                .append(" 题  ·  错题 ").append(WrongBook.cachedCount()).append(" 题").append(NL)
                .append("悬浮窗：").append(canDrawCached ? "已授权" : "未授权").append(NL)
                .append("家长密码：").append(st.hasPin() ? "已设置" : "未设置");
        // 每秒轮询只在文本真的变化时才触碰 TextView / Button，避免无意义的重绘。
        String text = s.toString();
        if (!text.equals(status.getText().toString())) status.setText(text);
        String rightText = right.toString();
        if (!rightText.equals(statusRight.getText().toString())) statusRight.setText(rightText);

        setButtonText(btnWrong, "错题库 · " + WrongBook.cachedCount() + " 题");
        setButtonText(btnBoot, st.bootAutostart() ? "厂商自启设置" : "自启已关闭");

        if (paintedRunning == null || paintedRunning.booleanValue() != running) {
            paintedRunning = Boolean.valueOf(running);
            paintGuardButton(running);
        }
    }
}
