package com.jager.tvquiz;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 全屏答题遮罩。
 *
 * 遥控器输入不依赖 Android 的 View 焦点树：所有按键在窗口根视图的
 * dispatchKeyEvent 中由 KeyRouter 自主路由（方向键移动高亮、确定键/数字键提交），
 * 答题状态统一放在 QuizFlow（纯 Java，可单测）。老实现复用同一 View 实例反复
 * addView/removeView、答对时焦点残留在「继续观看」按钮上，第二次挂载后选项就选不中。
 *
 * QuizFlow 的推进由 QuizOverlayService.answer() 负责（判题需要题目的 answerIndex），
 * 本视图只负责渲染与按键语义翻译；bind() 会在每次换题时 reset()。
 *
 * 注意：按键仍要求遮罩窗口本身拿到窗口焦点。Service 侧做了有限次焦点重试与
 * 窗口重建，但 Android 没有强制夺取窗口焦点的公开 API，真机连续弹题仍需实测。
 */
public final class OverlayQuizView {

    public interface Listener {
        void onAnswer(int index);
        void onContinue();
        /** 遮罩窗口焦点变化：true=重新拿到遥控器按键，false=被通知栏/系统对话框抢走。 */
        void onWindowFocus(boolean hasFocus);
    }

    private static final class Root extends FrameLayout {
        private OverlayQuizView owner;

        Root(Context c) { super(c); }

        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            if (owner != null && owner.onKey(e)) return true;
            return super.dispatchKeyEvent(e);
        }

        @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            if (owner != null) owner.onWindowFocus(hasWindowFocus);
        }
    }

    private final Context c;
    private final Root root;
    private final TextView topic, text, result, explain, footer;
    private final Button[] opts = new Button[4];
    private final Button cont;
    private final Listener listener;
    private final QuizFlow flow = new QuizFlow();

    public OverlayQuizView(Context ctx, Listener l) {
        c = ctx;
        listener = l;

        root = new Root(ctx);
        root.owner = this;
        root.setBackgroundColor(0xF30B1018);
        // 根视图自己持有窗口焦点；子按钮不参与焦点树，避免 add/remove 后焦点残留。
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        LinearLayout card = Ui.vbox(c);
        card.setBackground(Ui.round(Ui.CARD, 18, c));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        cp.setMargins(Ui.dp(c, 88), Ui.dp(c, 22), Ui.dp(c, 88), Ui.dp(c, 22));
        card.setLayoutParams(cp);
        card.setPadding(Ui.dp(c, 26), Ui.dp(c, 12), Ui.dp(c, 26), Ui.dp(c, 8));

        topic = Ui.tv(c, "", 16, Ui.BLUE);
        topic.setLineSpacing(0, 1.15f);
        topic.setPadding(0, Ui.dp(c, 2), 0, Ui.dp(c, 6));
        card.addView(topic, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        text = Ui.tv(c, "", 30, Ui.TEXT);
        text.setGravity(Gravity.CENTER);
        text.setPadding(Ui.dp(c, 20), 0, Ui.dp(c, 20), 0);
        card.addView(text, Ui.fill(1));

        LinearLayout grid = Ui.hbox(c);
        grid.setBaselineAligned(false);
        LinearLayout col1 = Ui.vbox(c), col2 = Ui.vbox(c);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            Button b = Ui.button(c, "", Ui.BLUE_LIGHT, Ui.TEXT, 24);
            b.setFocusable(false);
            b.setFocusableInTouchMode(false);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { submit(idx); }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            p.setMargins(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
            (i < 2 ? col1 : col2).addView(b, p);
            opts[i] = b;
        }
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        grid.addView(col1, colLp);
        grid.addView(col2, colLp);
        card.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 186)));

        result = Ui.tv(c, "", 21, Ui.GREEN);
        card.addView(result, Ui.fixed(c, 34));
        explain = Ui.tv(c, "", 15, 0xFF5F6B7F);
        explain.setMinHeight(Ui.dp(c, 34));
        card.addView(explain, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cont = Ui.button(c, "继续观看", Ui.GREEN, 0xFFFFFFFF, 20);
        cont.setFocusable(false);
        cont.setFocusableInTouchMode(false);
        cont.setVisibility(View.INVISIBLE);
        cont.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestContinue(); }
        });
        LinearLayout.LayoutParams nb = new LinearLayout.LayoutParams(Ui.dp(c, 240), Ui.dp(c, 50));
        nb.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(cont, nb);

        footer = Ui.tv(c,
                "方向键选择 · 确定/数字键 1-4 作答 · 答错 " + AnswerPolicy.WRONG_LIMIT
                        + " 次自动换题 · 返回键无效 · 答对才能继续",
                14, Ui.MUTED);
        card.addView(footer, Ui.fixed(c, 26));

        root.addView(card, cp);
        paint();
    }

    public View view() { return root; }

    /** 服务与本视图共用同一个状态机，避免两处状态不同步。 */
    public QuizFlow flow() { return flow; }

    /** addView 之后 post 调用，确保窗口已完成首次 attach/traversal。 */
    public void focusFirst() {
        root.requestFocus();
        paint();
    }

    /** 窗口（重新）获得焦点时夺回根视图焦点、重绘高亮，并通知服务停止重试。 */
    public void onWindowFocus(boolean hasFocus) {
        if (hasFocus) {
            root.requestFocus();
            paint();
        }
        if (listener != null) listener.onWindowFocus(hasFocus);
    }

    /** 供服务的焦点重试任务判断是否已抢回窗口焦点。 */
    public boolean hasWindowFocus() { return root.hasWindowFocus(); }

    public void bind(QuizEngine.Question q, String meta) {
        String src = "bank".equals(q.source) ? "在线题库" : "本地生成";
        topic.setText("该答题啦  ·  " + q.topic + "  ·  " + src + "\n" + meta);
        text.setText(q.text);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, QuizTextSizer.sp(q.text));
        result.setText("");
        explain.setText("");
        cont.setVisibility(View.INVISIBLE);
        // 换题必须整体重置状态机：累计答错次数、禁用选项、高亮、答对标志。
        flow.reset();
        for (int i = 0; i < 4; i++) {
            opts[i].setText(q.options != null && i < q.options.length
                    ? (q.options[i] == null ? "" : q.options[i]) : "");
        }
        paint();
    }

    /** 答错一次：flow.submit() 已在服务侧禁用该选项，这里只负责提示与重绘。 */
    public void wrong(int idx, String msg) {
        result.setTextColor(Ui.RED);
        result.setText(msg);
        explain.setText("");
        paint();
    }

    /** 换题后给一句提示；bind() 已重绘，此处不再禁用任何选项。 */
    public void hint(String msg) {
        result.setTextColor(Ui.AMBER);
        result.setText(msg);
        explain.setText("");
        paint();
    }

    public void correct(String msg, String exp) {
        result.setTextColor(Ui.GREEN);
        result.setText(msg);
        explain.setText(exp);
        cont.setVisibility(View.VISIBLE);
        paint();
    }

    // ---------- 键盘路由：把 Android KeyEvent 翻成语义后交给 KeyRouter ----------

    private boolean onKey(KeyEvent e) {
        int[] value = new int[1];
        int semantic = semanticOf(e.getKeyCode(), value);
        KeyRouter.Decision d = KeyRouter.decide(
                e.getAction(), e.getRepeatCount(), semantic, value[0]);
        switch (d.action) {
            case KeyRouter.ACT_PASS_THROUGH:
                return false; // 音量键 DOWN/UP 都交回系统
            case KeyRouter.ACT_CONFIRM:
                if (flow.isAnswered()) requestContinue();
                else submit(flow.selected());
                return true;
            case KeyRouter.ACT_ANSWER:
                if (!flow.isAnswered()) submit(d.value);
                return true;
            case KeyRouter.ACT_MOVE:
                if (!flow.isAnswered()) flow.move(d.value);
                paint();
                return true;
            case KeyRouter.ACT_CONSUME:
            default:
                return true;
        }
    }

    /**
     * 答对后的「继续」统一 post 到消息队列：不能在 KeyEvent / click 分发栈内
     * 同步 removeViewImmediate()，否则会 detach 当前 ViewRootImpl，导致状态错乱。
     */
    private void requestContinue() {
        if (!flow.isAnswered()) return;
        root.post(new Runnable() {
            @Override public void run() {
                if (flow.isAnswered() && listener != null) listener.onContinue();
            }
        });
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
            value[0] = k - KeyEvent.KEYCODE_1;
            return KeyRouter.SEM_ANSWER;
        }
        if (k >= KeyEvent.KEYCODE_NUMPAD_1 && k <= KeyEvent.KEYCODE_NUMPAD_4) {
            value[0] = k - KeyEvent.KEYCODE_NUMPAD_1;
            return KeyRouter.SEM_ANSWER;
        }
        return KeyRouter.SEM_OTHER;
    }

    private void submit(int idx) {
        if (flow.isAnswered() || idx < 0 || idx > 3 || flow.isDisabled(idx)) return;
        listener.onAnswer(idx);
    }

    /** 手动绘制高亮，不使用 OnFocusChangeListener。 */
    private void paint() {
        boolean answered = flow.isAnswered();
        for (int i = 0; i < 4; i++) {
            Button b = opts[i];
            b.setEnabled(!flow.isDisabled(i) && !answered);
            if (flow.isDisabled(i)) {
                b.setBackground(Ui.round(0xFFE3CFD4, 10, c));
                b.setTextColor(0xFF9A7C86);
            } else if (answered) {
                b.setBackground(Ui.round(0xFFDDE6F2, 10, c));
                b.setTextColor(0xFF6A7891);
            } else if (i == flow.selected()) {
                b.setBackground(Ui.round(Ui.BLUE, 10, c));
                b.setTextColor(0xFFFFFFFF);
            } else {
                b.setBackground(Ui.round(Ui.BLUE_LIGHT, 10, c));
                b.setTextColor(Ui.TEXT);
            }
        }
    }
}
