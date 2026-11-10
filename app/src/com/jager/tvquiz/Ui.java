package com.jager.tvquiz;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 代码布局公共工具：统一尺寸、卡片圆角与遥控器焦点高亮。
 *
 * v1.0.7 起字号不再写死：每个控件按「设计 sp × 屏幕归一比例」算出像素上限，
 * 再由 FitTextView / FitButton 在测量阶段按容器宽高收缩。这样 720p / 1080p / 4K
 * 电视上的字号观感一致，系统字体放大也不会把界面撑破，文字始终完整显示。
 */
public final class Ui {
    private Ui() {}

    // 与答题页共用的浅色底、白色卡片和紫色遥控器焦点。
    public static final int BG = 0xFFF3F5FC;
    public static final int CARD = 0xFFFFFFFF;
    public static final int TEXT = 0xFF1D1E31;
    /** 加深次级文字，远距离也看得清；旧值 0xFF85879D 对比度不足。 */
    public static final int MUTED = 0xFF63667F;
    public static final int PURPLE = 0xFF7058DE;
    public static final int PURPLE_DARK = 0xFF5840C1;
    public static final int PURPLE_LIGHT = 0xFFEBE6FF;
    public static final int BORDER = 0xFFDCDCEA;
    public static final int BLUE = PURPLE;
    public static final int BLUE_LIGHT = PURPLE_LIGHT;
    public static final int GREEN = 0xFF1F946B;
    public static final int RED = 0xFFB84545;
    public static final int AMBER = PURPLE;
    public static final int BTN_BG = 0xFFF4F5FB;

    /** 全站统一的卡片圆角。 */
    public static final int RADIUS_DP = 14;

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 当前设备的字号归一比例（相对 960dp 设计基准，clamp 到 0.80–1.30）。 */
    public static float typeScale(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return QuizTextSizer.deviceScale(dm.widthPixels, dm.density);
    }

    public static LinearLayout.LayoutParams fill(int weight) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
    }

    public static LinearLayout.LayoutParams fixed(Context c, int dp) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, dp));
    }

    /** 高度随文字增长：配合 setMinimumHeight 使用，避免固定高度裁切内容。 */
    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static GradientDrawable round(int color, int radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable roundBorder(int color, int stroke, int radiusDp, Context c) {
        GradientDrawable g = round(color, radiusDp, c);
        g.setStroke(dp(c, 3), stroke);
        return g;
    }

    public static boolean isLightButton(int bg) {
        return bg == CARD || bg == BTN_BG || bg == PURPLE_LIGHT;
    }

    public static TextView tv(Context c, String text, int sp, int color, int gravity) {
        FitTextView t = new FitTextView(c);
        t.setText(text);
        t.setTextColor(color);
        t.setGravity(gravity);
        applyType(t, sp);
        return t;
    }

    public static TextView tv(Context c, String text, int sp, int color) {
        return tv(c, text, sp, color, Gravity.CENTER);
    }

    public static Button button(Context c, String text, int bg, int fg, int sp) {
        FitButton b = new FitButton(c);
        b.setText(text);
        b.setTextColor(fg);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
        b.setBackground(isLightButton(bg) ? roundBorder(bg, BORDER, RADIUS_DP, c)
                : round(bg, RADIUS_DP, c));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        applyType(b, sp);
        return b;
    }

    /**
     * 把「设计 sp」落到像素：乘以 density 与屏幕归一比例，并绕开系统 fontScale。
     * 同时把换算后的上下限交给自适应控件，供测量阶段按实际空间收缩。
     */
    public static void applyType(TextView v, int designSp) {
        DisplayMetrics dm = v.getResources().getDisplayMetrics();
        float maxPx = QuizTextSizer.px(designSp, dm.density, typeScale(v.getContext()));
        float minPx = maxPx * QuizTextSizer.MIN_RATIO;
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, maxPx);
        if (v instanceof FitTextView) ((FitTextView) v).setFitRangePx(minPx, maxPx);
        else if (v instanceof FitButton) ((FitButton) v).setFitRangePx(minPx, maxPx);
    }

    /** 运行期改字号上限（例如答题页按题面长度降档），同样走跨屏归一 + 自适应。 */
    public static void fitDesign(TextView v, int designSp) {
        applyType(v, designSp);
    }

    /** 遥控器 DPAD 焦点：浅色按钮使用与答题选项相同的浅紫底与加粗紫色描边。 */
    public static void focus(final Button b, final Context c,
                             final int normalBg, final int focusedBg, final int normalFg) {
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (isLightButton(normalBg)) {
                    b.setBackground(roundBorder(has ? PURPLE_LIGHT : normalBg,
                            has ? PURPLE : BORDER, RADIUS_DP, c));
                    b.setTextColor(has ? PURPLE_DARK : normalFg);
                } else {
                    b.setBackground(round(has ? focusedBg : normalBg, RADIUS_DP, c));
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
