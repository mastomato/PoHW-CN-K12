package com.jager.tvquiz;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 与知行答题 TV 统一的全屏答题页；窗口根视图接管遥控器，避免覆盖层焦点丢失。 */
public final class OverlayQuizView {
    public interface Listener {
        void onAnswer(int index);
        void onNext();
        void onNewRound();
        void onExit();
        /** 家长应急出口：菜单键 / 数字 0 触发，由服务弹出密码验证。 */
        void onParentUnlock();
        void onParentCancel();
        void onWindowFocus(boolean hasFocus);
    }

    private static final int BG = 0xFFF7F8FC;
    private static final int INK = 0xFF222339;
    private static final int MUTED = 0xFF85879D;
    private static final int PURPLE = 0xFF7058DE;
    private static final int PURPLE_DARK = 0xFF5840C1;
    private static final int GREEN = 0xFF2AA377;
    private static final int RED = 0xFFC25252;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int QUESTION = 0;
    private static final int COMPLETE = 1;

    private static final class Root extends FrameLayout {
        private OverlayQuizView owner;
        Root(Context c) { super(c); }
        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            if (owner != null && owner.onKey(e)) return true;
            return super.dispatchKeyEvent(e);
        }
        @Override public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            if (owner != null) owner.onWindowFocus(hasFocus);
        }
    }

    private final Context c;
    private final Root root;
    private final Listener listener;
    private final QuizFlow flow = new QuizFlow();
    private final Button[] opts = new Button[4];
    private final TextView topic, question, feedback, explain, sideProgress, tip;
    private final Button next;
    /** 顶部进度条的可变部分；bind() 按本轮进度更新权重。 */
    private View progressFill, progressRest;
    /** 题干卡与「一轮结束」居中得分页互斥显示。 */
    private LinearLayout cardRow, completeView;
    private TextView scoreBig, scoreSub, scoreHint;
    /** 答题后要显示的解析面板；未作答时用引导文案占位，不再留空白。 */
    private static final String RESULT_HINT = "选出答案后，这里会显示正确答案与解析";
    private Runnable autoNext;
    private int phase = QUESTION;
    private int picked = -1;
    private int correctIndex = -1;
    private boolean transitionPending;
    private boolean exitPending;
    /** 本次弹题会话是否已满足退出门禁；换题、开新一轮都不清零。 */
    private boolean exitAllowed = false;
    private boolean parentPinSet = false;
    /** 家长设定的门禁阈值与本次会话进度，用于生成「还差几题」提示。 */
    private int requiredCorrect = ExitPolicy.DEFAULT_REQUIRED_CORRECT;
    private int sessionCorrect = 0;
    private View parentGate;

    public OverlayQuizView(Context ctx, Listener l) {
        c = ctx;
        listener = l;
        root = new Root(c);
        root.owner = this;
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout body = Ui.vbox(c);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(40), dp(16), dp(40), dp(16));
        scroll.addView(body);
        body.addView(label("知行答题  /  答题守护", 16, MUTED, false));
        LinearLayout header = Ui.hbox(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(-1, -2);
        headerLp.topMargin = dp(7);
        body.addView(header, headerLp);
        TextView heading = label("答题练习", 30, INK, true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        sideProgress = label("", 20, PURPLE, true);
        sideProgress.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        header.addView(sideProgress, new LinearLayout.LayoutParams(dp(165), -2));
        // 细进度条：把「第 N / M 题」变成一眼可见的进度，而不只是文字。
        LinearLayout progressTrack = Ui.hbox(c);
        progressTrack.setBackground(Ui.round(0xFFE4E3EF, 2, c));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(-1, dp(4));
        trackLp.topMargin = dp(10);
        body.addView(progressTrack, trackLp);
        progressFill = new View(c);
        progressFill.setBackground(Ui.round(PURPLE, 2, c));
        progressTrack.addView(progressFill, new LinearLayout.LayoutParams(0, -1, 0.05f));
        progressRest = new View(c);
        progressTrack.addView(progressRest, new LinearLayout.LayoutParams(0, -1, 0.95f));

        tip = label("", 16, MUTED, false);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(-1, -2);
        tipLp.topMargin = dp(8);
        body.addView(tip, tipLp);
        renderTip();

        // 题干卡外层：左侧紫色竖条作为视觉锚点，卡片吃掉整页剩余高度。
        cardRow = Ui.hbox(c);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        rowLp.topMargin = dp(16);
        body.addView(cardRow, rowLp);
        View accent = new View(c);
        accent.setBackground(Ui.round(PURPLE, 2, c));
        cardRow.addView(accent, new LinearLayout.LayoutParams(dp(4), -1));

        LinearLayout card = Ui.vbox(c);
        card.setPadding(dp(26), dp(18), dp(26), dp(18));
        card.setBackground(shape(WHITE, 0));
        cardRow.addView(card, new LinearLayout.LayoutParams(0, -1, 1f));

        topic = label("", 19, PURPLE, true);
        card.addView(topic);
        question = label("", 34, INK, true);
        question.setGravity(Gravity.CENTER);
        question.setPadding(0, dp(14), 0, dp(16));
        question.setLineSpacing(dp(4), 1.08f);
        question.setMaxLines(4);
        question.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(question, new LinearLayout.LayoutParams(-1, -2));

        // 选项区按 weight 撑满中间空间：仍是竖排四行，每行舒展。
        LinearLayout options = Ui.vbox(c);
        card.addView(options, new LinearLayout.LayoutParams(-1, 0, 1f));
        for (int i = 0; i < opts.length; i++) {
            final int choice = i;
            Button b = Ui.button(c, "", 0xFFF7F7FB, INK, 27);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            b.setMinHeight(dp(56));
            b.setPadding(dp(20), dp(11), dp(20), dp(11));
            b.setFocusable(false);
            b.setFocusableInTouchMode(false);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { submit(choice); }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 0, 1f);
            p.topMargin = dp(10);
            options.addView(b, p);
            opts[i] = b;
        }

        // 解析面板：固定区域，未作答时显示引导文案；答对/答错后能容纳
        // 正确答案、解析与「下一题」，不再让题目区下半部空着或临时撑爆布局。
        // 解析面板：左侧文字（答案 / 解析），「下一题」固定在右下角。
        LinearLayout resultBox = Ui.hbox(c);
        resultBox.setGravity(Gravity.BOTTOM);
        resultBox.setBackground(Ui.round(0xFFF5F5FB, 12, c));
        resultBox.setPadding(dp(18), dp(12), dp(18), dp(12));
        LinearLayout.LayoutParams resultLp = new LinearLayout.LayoutParams(-1, dp(96));
        resultLp.topMargin = dp(12);
        card.addView(resultBox, resultLp);
        LinearLayout resultText = Ui.vbox(c);
        resultText.setGravity(Gravity.CENTER_VERTICAL);
        feedback = label(RESULT_HINT, 20, MUTED, true);
        resultText.addView(feedback);
        explain = label("", 19, MUTED, false);
        explain.setLineSpacing(dp(3), 1f);
        explain.setMaxLines(3);
        explain.setEllipsize(TextUtils.TruncateAt.END);
        resultText.addView(explain);
        resultBox.addView(resultText, new LinearLayout.LayoutParams(0, -1, 1f));
        next = Ui.button(c, "下一题  →", PURPLE, WHITE, 20);
        next.setFocusable(false);
        next.setFocusableInTouchMode(false);
        next.setBackground(shape(PURPLE, 0));
        next.setVisibility(View.GONE);
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (phase == COMPLETE) requestNewRound();
                else if (flow.isAnswered() && picked != correctIndex) requestNext();
            }
        });
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(dp(220), dp(58));
        nextLp.leftMargin = dp(16);
        resultBox.addView(next, nextLp);

        // 一轮结束：整块居中显示得分，替代题干卡，视觉重心落在屏幕中央。
        completeView = Ui.vbox(c);
        completeView.setGravity(Gravity.CENTER);
        completeView.setBackground(shape(WHITE, 0));
        completeView.setVisibility(View.GONE);
        LinearLayout.LayoutParams completeLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        completeLp.topMargin = dp(16);
        body.addView(completeView, completeLp);
        scoreBig = label("", 54, INK, true);
        scoreBig.setGravity(Gravity.CENTER);
        completeView.addView(scoreBig);
        scoreSub = label("", 24, PURPLE, true);
        scoreSub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(12);
        completeView.addView(scoreSub, subLp);
        scoreHint = label("", 17, MUTED, false);
        scoreHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(18);
        completeView.addView(scoreHint, hintLp);
        Button nextRound = Ui.button(c, "开始下一轮  →", PURPLE, WHITE, 22);
        nextRound.setFocusable(false);
        nextRound.setFocusableInTouchMode(false);
        nextRound.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestNewRound(); }
        });
        LinearLayout.LayoutParams nrLp = new LinearLayout.LayoutParams(dp(280), dp(64));
        nrLp.topMargin = dp(24);
        completeView.addView(nextRound, nrLp);
    }

    private int dp(int value) { return Ui.dp(c, value); }

    private GradientDrawable shape(int fill, int stroke) {
        GradientDrawable d = Ui.round(fill, 12, c);
        if (stroke != 0) d.setStroke(dp(2), stroke);
        return d;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView t = Ui.tv(c, value, size, color, Gravity.CENTER_VERTICAL | Gravity.LEFT);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    public View view() { return root; }
    public QuizFlow flow() { return flow; }
    public boolean hasWindowFocus() { return root.hasWindowFocus(); }
    public void focusFirst() { root.requestFocus(); paint(); }

    /** 家长是否设置了密码：决定操作提示里要不要提示应急出口。 */
    public void setParentPinSet(boolean v) { parentPinSet = v; renderTip(); }

    /**
     * 由服务在「本次弹题会话累计答对数」变化后调用。只允许 false -> true：
     * 答对过的成绩不因换题 / 新一轮 / 重绘而倒退。
     */
    public void setExitAllowed(boolean v) {
        if (v) exitAllowed = true;
        renderTip();
    }

    /** 服务在弹题 / 每次答对后下发阈值与进度，View 与 Service 用同一策略判断。 */
    public void setExitGate(int required, int correct, boolean pinSet) {
        requiredCorrect = ExitPolicy.normalizeRequired(required);
        sessionCorrect = Math.max(0, correct);
        parentPinSet = pinSet;
        exitAllowed = ExitPolicy.canExit(sessionCorrect, requiredCorrect);
        renderTip();
    }

    private void renderTip() {
        if (tip == null) return;
        tip.setText(ExitPolicy.tip(exitAllowed, parentPinSet, requiredCorrect, sessionCorrect));
        tip.setTextColor(exitAllowed ? MUTED : PURPLE);
    }

    /** 服务侧第二道防线被触发时，给出可见反馈，避免用户以为遥控器坏了。 */
    public void nudgeExit() {
        feedback.setTextColor(RED);
        feedback.setText(ExitPolicy.blockedHint(requiredCorrect, sessionCorrect));
    }

    /** 家长密码浮层：作为子 View 叠在答题页上，不另开窗口、不摘遮罩。 */
    public void showParentGate(View gateView) {
        hideParentGate();
        parentGate = gateView;
        root.addView(gateView, new FrameLayout.LayoutParams(-1, -1));
        root.post(new Runnable() {
            @Override public void run() { if (parentGate != null) parentGate.requestFocus(); }
        });
    }

    public void hideParentGate() {
        if (parentGate == null) return;
        root.removeView(parentGate);
        parentGate = null;
        root.requestFocus();
    }

    public boolean hasParentGate() { return parentGate != null; }

    public boolean isAttached() { return root.isAttachedToWindow(); }

    public void onWindowFocus(boolean hasFocus) {
        if (hasFocus) focusFirst();
        if (listener != null) listener.onWindowFocus(hasFocus);
    }

    public void bind(QuizEngine.Question q, String meta, int number, int total) {
        cancelPending();
        phase = QUESTION;
        transitionPending = false;
        picked = -1;
        correctIndex = -1;
        flow.reset();
        sideProgress.setText("第 " + number + " / " + total + " 题");
        float ratio = total > 0 ? Math.max(0.03f, Math.min(1f, (float) number / total)) : 0.05f;
        if (progressFill != null) {
            progressFill.setLayoutParams(new LinearLayout.LayoutParams(0, -1, ratio));
            progressRest.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1f - ratio));
        }
        if (cardRow != null) cardRow.setVisibility(View.VISIBLE);
        if (completeView != null) completeView.setVisibility(View.GONE);
        topic.setText(meta);
        question.setText(q.text == null ? "" : q.text);
        question.setTextSize(Math.min(34, QuizTextSizer.sp(q.text)));
        for (int i = 0; i < 4; i++) {
            String answer = q.options != null && i < q.options.length && q.options[i] != null
                    ? q.options[i] : "";
            SpannableString caption = new SpannableString((char) ('A' + i) + "   " + answer);
            caption.setSpan(new StyleSpan(Typeface.BOLD), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            caption.setSpan(new ForegroundColorSpan(PURPLE_DARK), 0, 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            opts[i].setText(caption);
            opts[i].setVisibility(View.VISIBLE);
        }
        feedback.setTextColor(MUTED);
        feedback.setText(RESULT_HINT);
        explain.setText("");
        next.setVisibility(View.GONE);
        paint();
        // 换题不清 exitAllowed：答对过一次就保持解锁状态，直到本次弹题结束。
        renderTip();
        root.requestFocus();
    }

    public void correct(int answerIndex, String explanation) {
        picked = flow.selected();
        correctIndex = answerIndex;
        feedback.setTextColor(GREEN);
        feedback.setText("答对了 ✓  即将进入下一题…");
        explain.setText(explanation == null ? "" : explanation);
        next.setVisibility(View.GONE);
        paint();
        autoNext = new Runnable() {
            @Override public void run() {
                autoNext = null;
                requestNext();
            }
        };
        root.postDelayed(autoNext, 850);
    }

    public void wrong(int answerIndex, String answerText, String explanation) {
        picked = flow.selected();
        correctIndex = answerIndex;
        feedback.setTextColor(RED);
        feedback.setText("正确答案：" + (char) ('A' + answerIndex) + "  " + answerText);
        explain.setText(explanation == null ? "" : explanation);
        next.setText("下一题  →");
        next.setVisibility(View.VISIBLE);
        paint();
    }

    public void showRoundComplete(int correct, int total) {
        cancelPending();
        phase = COMPLETE;
        transitionPending = false;
        cardRow.setVisibility(View.GONE);
        completeView.setVisibility(View.VISIBLE);
        scoreBig.setText("答对 " + correct + " / " + total + " 题");
        scoreSub.setText("正确率 " + (total == 0 ? 0 : correct * 100 / total) + "%");
        scoreHint.setText(exitAllowed
                ? "按确定开始下一轮，按返回退出答题。"
                : ExitPolicy.blockedHint(requiredCorrect, sessionCorrect)
                        + " 按确定开始下一轮继续作答。");
        sideProgress.setText("本轮已完成");
        if (progressFill != null) {
            progressFill.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1f));
            progressRest.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 0f));
        }
        renderTip();
        root.requestFocus();
    }

    public void cancelPending() {
        if (autoNext != null) root.removeCallbacks(autoNext);
        autoNext = null;
    }

    private boolean onKey(KeyEvent e) {
        // 家长密码浮层自己处理数字键 / DPAD；这里只接管返回键做「取消验证」。
        // 注意父窗口的 dispatchKeyEvent 先于子 View，必须显式让路，否则密码永远输不进去。
        if (parentGate != null) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK
                    && e.getAction() == KeyEvent.ACTION_DOWN) {
                if (listener != null) listener.onParentCancel();
                return true;
            }
            return false;
        }
        int[] value = new int[1];
        KeyRouter.Decision d = KeyRouter.decide(e.getAction(), e.getRepeatCount(),
                semanticOf(e.getKeyCode(), value), value[0]);
        switch (d.action) {
            case KeyRouter.ACT_PASS_THROUGH: return false;
            case KeyRouter.ACT_BACK: requestExit(); return true;
            case KeyRouter.ACT_PARENT:
                if (listener != null) listener.onParentUnlock();
                return true;
            case KeyRouter.ACT_CONFIRM:
                if (phase == COMPLETE) requestNewRound();
                else if (flow.isAnswered()) {
                    if (picked != correctIndex) requestNext();
                } else submit(flow.selected());
                return true;
            case KeyRouter.ACT_ANSWER:
                if (phase == QUESTION && !flow.isAnswered()) submit(d.value);
                return true;
            case KeyRouter.ACT_MOVE:
                if (phase == QUESTION && !flow.isAnswered()) { flow.move(d.value); paint(); }
                return true;
            default: return true;
        }
    }

    private void requestNext() {
        if (transitionPending) return;
        transitionPending = true;
        root.post(new Runnable() {
            @Override public void run() { if (listener != null) listener.onNext(); }
        });
    }

    private void requestNewRound() {
        if (transitionPending) return;
        transitionPending = true;
        root.post(new Runnable() {
            @Override public void run() { if (listener != null) listener.onNewRound(); }
        });
    }

    private void requestExit() {
        // 核心门禁：本次弹题会话一道题都没答对，返回键只提示、不摘遮罩。
        if (!exitAllowed) { nudgeExit(); return; }
        if (exitPending) return;
        exitPending = true;
        transitionPending = true;
        cancelPending();
        root.post(new Runnable() {
            @Override public void run() { if (listener != null) listener.onExit(); }
        });
    }

    private void submit(int idx) {
        if (phase != QUESTION || flow.isAnswered() || idx < 0 || idx > 3) return;
        if (listener != null) listener.onAnswer(idx);
    }

    private void paint() {
        if (phase != QUESTION) return;
        for (int i = 0; i < opts.length; i++) {
            Button b = opts[i];
            boolean answered = flow.isAnswered();
            b.setEnabled(!answered);
            int fill = 0xFFF7F7FB, stroke = 0xFFE9E8F0, color = INK;
            if (answered && i == correctIndex) { fill = 0xFFDDF5E9; color = GREEN; stroke = 0; }
            else if (answered && i == picked) { fill = 0xFFFFE7E7; color = RED; stroke = 0; }
            else if (!answered && i == flow.selected()) {
                fill = 0xFFEBE6FF; stroke = PURPLE; color = PURPLE_DARK;
            }
            b.setBackground(shape(fill, stroke));
            b.setTextColor(color);
        }
    }

    private static int semanticOf(int k, int[] value) {
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN
                || k == KeyEvent.KEYCODE_VOLUME_MUTE) return KeyRouter.SEM_VOLUME;
        // 系统一般不给应用窗口派发 HOME，但派发到就一律吞掉，不让用户绕过答题。
        if (k == KeyEvent.KEYCODE_HOME || k == KeyEvent.KEYCODE_APP_SWITCH) {
            return KeyRouter.SEM_HOME;
        }
        // 家长应急出口：菜单键或数字 0，走密码验证，不是「直接返回」。
        if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_0
                || k == KeyEvent.KEYCODE_NUMPAD_0) {
            return KeyRouter.SEM_PARENT;
        }
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE
                || k == KeyEvent.KEYCODE_BUTTON_B) return KeyRouter.SEM_BACK;
        if (k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE
                || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_NEXT
                || k == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || k == KeyEvent.KEYCODE_MEDIA_REWIND) return KeyRouter.SEM_MEDIA;
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER) return KeyRouter.SEM_CONFIRM;
        if (k == KeyEvent.KEYCODE_DPAD_LEFT)  { value[0] = 0; return KeyRouter.SEM_MOVE; }
        if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { value[0] = 1; return KeyRouter.SEM_MOVE; }
        if (k == KeyEvent.KEYCODE_DPAD_UP)    { value[0] = 2; return KeyRouter.SEM_MOVE; }
        if (k == KeyEvent.KEYCODE_DPAD_DOWN)  { value[0] = 3; return KeyRouter.SEM_MOVE; }
        if (k >= KeyEvent.KEYCODE_1 && k <= KeyEvent.KEYCODE_4) {
            value[0] = k - KeyEvent.KEYCODE_1; return KeyRouter.SEM_ANSWER;
        }
        if (k >= KeyEvent.KEYCODE_NUMPAD_1 && k <= KeyEvent.KEYCODE_NUMPAD_4) {
            value[0] = k - KeyEvent.KEYCODE_NUMPAD_1; return KeyRouter.SEM_ANSWER;
        }
        return KeyRouter.SEM_OTHER;
    }
}
