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

    // 与答题页共用的浅色底、白色卡片和紫色遥控器焦点。
    public static final int BG = 0xFFF7F8FC;
    public static final int CARD = 0xFFFFFFFF;
    public static final int TEXT = 0xFF222339;
    public static final int MUTED = 0xFF85879D;
    public static final int PURPLE = 0xFF7058DE;
    public static final int PURPLE_DARK = 0xFF5840C1;
    public static final int PURPLE_LIGHT = 0xFFEBE6FF;
    public static final int BORDER = 0xFFE9E8F0;
    public static final int BLUE = PURPLE;
    public static final int BLUE_LIGHT = PURPLE_LIGHT;
    public static final int GREEN = 0xFF2AA377;
    public static final int RED = 0xFFC25252;
    public static final int AMBER = PURPLE;
    public static final int BTN_BG = 0xFFF7F7FB;

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

    public static GradientDrawable roundBorder(int color, int stroke, int radiusDp, Context c) {
        GradientDrawable g = round(color, radiusDp, c);
        g.setStroke(dp(c, 2), stroke);
        return g;
    }

    public static boolean isLightButton(int bg) {
        return bg == CARD || bg == BTN_BG || bg == PURPLE_LIGHT;
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
        b.setBackground(isLightButton(bg) ? roundBorder(bg, BORDER, 10, c) : round(bg, 10, c));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        return b;
    }

    /** 遥控器 DPAD 焦点：浅色按钮使用与答题选项相同的浅紫底与紫色描边。 */
    public static void focus(final Button b, final Context c,
                             final int normalBg, final int focusedBg, final int normalFg) {
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (isLightButton(normalBg)) {
                    b.setBackground(roundBorder(has ? PURPLE_LIGHT : normalBg,
                            has ? PURPLE : BORDER, 10, c));
                    b.setTextColor(has ? PURPLE_DARK : normalFg);
                } else {
                    b.setBackground(round(has ? focusedBg : normalBg, 10, c));
                    b.setTextColor(has ? Color.WHITE : normalFg);
                }
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
