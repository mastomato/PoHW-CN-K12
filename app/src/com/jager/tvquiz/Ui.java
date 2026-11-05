package com.jager.tvquiz;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 代码布局公共工具：统一尺寸、按钮与遥控器焦点高亮。 */
public final class Ui {
    private Ui() {}

    public static final int BG = 0xFF10141C;
    public static final int CARD = 0xFFF7F9FC;
    public static final int TEXT = 0xFF16233A;
    public static final int MUTED = 0xFF8A97AC;
    public static final int BLUE = 0xFF185FA5;
    public static final int BLUE_LIGHT = 0xFFE6F1FB;
    public static final int GREEN = 0xFF1D9E75;
    public static final int RED = 0xFFA32D2D;
    public static final int AMBER = 0xFFFFD166;
    public static final int BTN_BG = 0xFF243044;

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static LinearLayout.LayoutParams fill(int weight) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
    }

    public static LinearLayout.LayoutParams fixed(Context c, int dp) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, dp));
    }

    public static GradientDrawable round(int color, int radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static TextView tv(Context c, String text, int sp, int color, int gravity) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(gravity);
        return t;
    }

    public static TextView tv(Context c, String text, int sp, int color) {
        return tv(c, text, sp, color, Gravity.CENTER);
    }

    public static Button button(Context c, String text, int bg, int fg, int sp) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTextColor(fg);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        b.setBackground(round(bg, 10, c));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        return b;
    }

    /** 遥控器 DPAD 焦点：高亮底色 + 白字，失焦还原。 */
    public static void focus(final Button b, final Context c,
                             final int normalBg, final int focusedBg, final int normalFg) {
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                b.setBackground(round(has ? focusedBg : normalBg, 10, c));
                b.setTextColor(has ? Color.WHITE : normalFg);
            }
        });
    }

    public static void focus(final Button b, final Context c, final int normalBg, final int normalFg) {
        focus(b, c, normalBg, BLUE, normalFg);
    }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER);
        return l;
    }
}
