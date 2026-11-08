package com.jager.tvquiz;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 就地密码验证浮层：叠加在控制台 View 之上。
 * 宿主 Activity 不 startActivity、不 finish，因此界面不会退出或切换；
 * 验证通过/取消后只移除本浮层，由宿主刷新按钮颜色。
 */
public final class PinVerifyView {

    public interface Callback {
        void onVerified();
        void onCancel();
    }

    private final Context c;
    private final PinGate gate;
    private final Callback cb;
    private final FrameLayout layer;
    private final TextView mask, msg;
    private Button first;

    public PinVerifyView(Context ctx, PinGate.Store store, Callback callback) {
        this(ctx, store, callback, "关闭守护 · 请输入家长密码",
                "验证通过后守护才会关闭；按返回键或点「取消」退出验证");
    }

    /** 答题遮罩里的应急出口复用同一套密码键盘，只是文案不同。 */
    public PinVerifyView(Context ctx, PinGate.Store store, Callback callback,
                         String title, String footer) {
        c = ctx;
        cb = callback;
        gate = new PinGate(store);

        // 浮层必须能在自身 View 树内持有焦点，否则 DPAD 会落到控制台底层按钮上。
        layer = new FrameLayout(ctx) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int code = event.getKeyCode();
                    boolean nav = code == KeyEvent.KEYCODE_DPAD_UP
                            || code == KeyEvent.KEYCODE_DPAD_DOWN
                            || code == KeyEvent.KEYCODE_DPAD_LEFT
                            || code == KeyEvent.KEYCODE_DPAD_RIGHT
                            || code == KeyEvent.KEYCODE_DPAD_CENTER
                            || code == KeyEvent.KEYCODE_ENTER;
                    // 焦点若被系统抢走，先收回数字键盘，本次方向键不再向下穿透。
                    if (nav && !hasFocus()) {
                        requestFocus();
                        if (first != null) first.requestFocus();
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_BACK) return true; // 宿主 Activity 负责取消
                }
                return super.dispatchKeyEvent(event);
            }
        };
        layer.setBackgroundColor(0xE6050810);
        layer.setClickable(true);
        layer.setFocusable(true);
        layer.setFocusableInTouchMode(true);
        layer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        LinearLayout card = Ui.vbox(ctx);
        card.setBackground(Ui.round(Ui.CARD, Ui.RADIUS_DP, ctx));
        card.setPadding(Ui.dp(ctx, 30), Ui.dp(ctx, 20), Ui.dp(ctx, 30), Ui.dp(ctx, 18));
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        card.addView(Ui.tv(ctx, title, 24, Ui.TEXT), Ui.fixed(ctx, 48));
        mask = Ui.tv(ctx, "", 32, Ui.AMBER);
        card.addView(mask, Ui.fixed(ctx, 56));
        msg = Ui.tv(ctx, "", 18, Ui.AMBER);
        card.addView(msg, Ui.fixed(ctx, 36));

        String[] keys = {"1", "2", "3", "4", "5", "6",
                "7", "8", "9", "清空", "0", "确定"};
        for (int row = 0; row < 4; row++) {
            LinearLayout r = Ui.hbox(ctx);
            for (int col = 0; col < 3; col++) {
                final String k = keys[row * 3 + col];
                int bg = "确定".equals(k) ? Ui.PURPLE : Ui.BTN_BG;
                int fg = "确定".equals(k) ? Ui.CARD : Ui.TEXT;
                Button btn = Ui.button(ctx, k, bg, fg, 21);
                btn.setFocusableInTouchMode(false);
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { key(k); }
                });
                Ui.focus(btn, ctx, bg, fg);
                if (first == null) first = btn;
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                        Ui.dp(ctx, 132), Ui.dp(ctx, 64));
                p.setMargins(Ui.dp(ctx, 7), Ui.dp(ctx, 4), Ui.dp(ctx, 7), Ui.dp(ctx, 4));
                r.addView(btn, p);
            }
            card.addView(r, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 74)));
        }

        Button cancel = Ui.button(ctx, "取消", Ui.BTN_BG, Ui.TEXT, 19);
        cancel.setFocusableInTouchMode(false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cancel(); }
        });
        Ui.focus(cancel, ctx, Ui.BTN_BG, Ui.TEXT);
        LinearLayout.LayoutParams nb = new LinearLayout.LayoutParams(
                Ui.dp(ctx, 240), Ui.dp(ctx, 64));
        nb.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(cancel, nb);

        card.addView(Ui.tv(ctx, footer, 16, Ui.MUTED), Ui.fixed(ctx, 36));

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                Ui.dp(ctx, 560), ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.CENTER;
        layer.addView(card, cp);
        refresh();
    }

    public View view() { return layer; }

    public void requestFocusFirst() {
        // 必须等浮层 attach 完成后再抢焦点，构造期 requestFocus() 会被 ViewRoot 丢弃。
        layer.post(new Runnable() {
            @Override public void run() {
                layer.requestFocus();
                if (first != null) first.requestFocus();
            }
        });
    }

    public void key(String k) {
        gate.key(k);
        refresh();
    }

    public void cancel() { cb.onCancel(); }

    private void refresh() {
        mask.setText(gate.mask());
        msg.setText(gate.messageText());
        String t = gate.consumeToast();
        if (!t.isEmpty() && c instanceof android.app.Activity) {
            android.widget.Toast.makeText(c, t, android.widget.Toast.LENGTH_SHORT).show();
        }
        // VERIFY 模式下，只有密码正确才会产生该事件
        if (gate.consumeEnterSettings() && gate.mode() == PinGate.VERIFY) {
            cb.onVerified();
        }
    }
}
