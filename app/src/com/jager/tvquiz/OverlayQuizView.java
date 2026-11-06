package com.jager.tvquiz;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private final TextView topic, question, feedback, explain, sideProgress;
    private final Button next;
    private Runnable autoNext;
    private int phase = QUESTION;
    private int picked = -1;
    private int correctIndex = -1;
    private boolean transitionPending;
    private boolean exitPending;

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
        int viewport = c.getResources().getDisplayMetrics().widthPixels;
        int gutter = Math.max(dp(28), (viewport - dp(900)) / 2);
        body.setPadding(gutter, dp(24), gutter, dp(24));
        scroll.addView(body);
        body.addView(label("知行答题  /  答题守护", 13, MUTED, false));
        LinearLayout header = Ui.hbox(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(-1, -2);
        headerLp.topMargin = dp(7);
        body.addView(header, headerLp);
        TextView heading = label("答题练习", 29, INK, true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        sideProgress = label("", 15, PURPLE, true);
        sideProgress.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        header.addView(sideProgress, new LinearLayout.LayoutParams(dp(165), -2));
        body.addView(label("方向键选择，按确定直接作答；按返回键退出。", 14, MUTED, false));

        LinearLayout card = Ui.vbox(c);
        card.setPadding(dp(18), dp(16), dp(18), dp(18));
        card.setBackground(shape(WHITE, 0));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = dp(16);
        body.addView(card, cardLp);

        topic = label("", 14, PURPLE, true);
        card.addView(topic);
        question = label("", 26, INK, true);
        question.setPadding(0, dp(16), 0, dp(14));
        question.setLineSpacing(dp(4), 1.08f);
        card.addView(question, new LinearLayout.LayoutParams(-1, -2));

        for (int i = 0; i < opts.length; i++) {
            final int choice = i;
            Button b = Ui.button(c, "", 0xFFF7F7FB, INK, 20);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            b.setMinHeight(dp(56));
            b.setPadding(dp(18), dp(10), dp(18), dp(10));
            b.setFocusable(false);
            b.setFocusableInTouchMode(false);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { submit(choice); }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.topMargin = dp(8);
            card.addView(b, p);
            opts[i] = b;
        }

        feedback = label("", 14, MUTED, false);
        feedback.setPadding(0, dp(8), 0, dp(4));
        card.addView(feedback, new LinearLayout.LayoutParams(-1, -2));
        explain = label("", 14, MUTED, false);
        explain.setLineSpacing(dp(3), 1f);
        card.addView(explain, new LinearLayout.LayoutParams(-1, -2));
        next = Ui.button(c, "下一题  →", PURPLE, WHITE, 16);
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
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(dp(220), dp(52));
        nextLp.topMargin = dp(14);
        card.addView(next, nextLp);
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
        topic.setText(meta);
        question.setText(q.text == null ? "" : q.text);
        question.setTextSize(Math.min(27, QuizTextSizer.sp(q.text)));
        for (int i = 0; i < 4; i++) {
            String answer = q.options != null && i < q.options.length && q.options[i] != null
                    ? q.options[i] : "";
            opts[i].setText((char) ('A' + i) + "   " + answer);
            opts[i].setVisibility(View.VISIBLE);
        }
        feedback.setText("");
        explain.setText("");
        next.setVisibility(View.GONE);
        paint();
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
        topic.setText("本轮完成");
        question.setText("答对 " + correct + " / " + total + " 题");
        question.setTextSize(30);
        sideProgress.setText("本轮已完成");
        for (Button b : opts) b.setVisibility(View.GONE);
        feedback.setTextColor(PURPLE);
        feedback.setText("正确率 " + (total == 0 ? 0 : correct * 100 / total) + "%");
        explain.setText("按确定开始下一轮，按返回退出答题。");
        next.setText("开始下一轮  →");
        next.setVisibility(View.VISIBLE);
        root.requestFocus();
    }

    public void cancelPending() {
        if (autoNext != null) root.removeCallbacks(autoNext);
        autoNext = null;
    }

    private boolean onKey(KeyEvent e) {
        int[] value = new int[1];
        KeyRouter.Decision d = KeyRouter.decide(e.getAction(), e.getRepeatCount(),
                semanticOf(e.getKeyCode(), value), value[0]);
        switch (d.action) {
            case KeyRouter.ACT_PASS_THROUGH: return false;
            case KeyRouter.ACT_BACK: requestExit(); return true;
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
        if (k == KeyEvent.KEYCODE_BACK) return KeyRouter.SEM_BACK;
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
