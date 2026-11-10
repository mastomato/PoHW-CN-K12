package com.jager.tvquiz;

/**
 * 字号策略：设计 sp → 设备像素的换算与答题页字号上限。
 *
 * v1.0.7 起分三层：
 *  1. {@link #sp(String)} 按题面长度给出答题页的设计字号上限（长题不挤掉选项区）；
 *  2. {@link #deviceScale(int, float)} 把不同电视的逻辑宽度归一到 1920×1080 基准
 *     （density=2 时 960dp），并 clamp 到 [0.80, 1.30]，避免 720p 电视字太大、
 *     4K 电视字太小；
 *  3. {@link #px(float, float, float)} 用像素直接落字号，绕开系统 fontScale ——
 *     电视上改系统字号不会再让界面溢出，不同电视的观感也保持一致。
 *
 * 「能否完整显示」不靠猜，由 AutoFit 在测量阶段按容器可用宽高二分字号决定。
 * 本类保持纯 Java，JVM 单测可直接调用。
 */
public final class QuizTextSizer {

    /** 设计基准：1920px 宽、density=2 时的逻辑宽度。 */
    public static final float BASE_WIDTH_DP = 960f;
    /** 跨屏归一的上下限：再小不缩、再大不放，防止极端设备上失衡。 */
    public static final float MIN_SCALE = 0.80f;
    public static final float MAX_SCALE = 1.30f;
    /** 自动收缩的下限比例；低于此值改由换行 / 滚动消化，不再缩成蚂蚁字。 */
    public static final float MIN_RATIO = 0.55f;

    /** 16:9 设计画布：1920×1080、density=2（即 960×540dp）。 */
    public static final int BASE_WIDTH_PX = 1920;
    public static final int BASE_HEIGHT_PX = 1080;
    public static final float BASE_DENSITY = 2f;

    private QuizTextSizer() {}

    /**
     * 16:9 画布归一比例：1080p = 1.0，4K = 2.0，720p ≈ 0.667。
     *
     * 取宽、高两个比例的较小值，非 16:9（如 16:10、4:3、带鱼屏）时以短边为准，
     * 宁可留边也不把内容推出画面。TvDisplay 用它把整个界面统一到
     * 1920×1080 设计基准，1080p 与 4K 的排版比例完全一致。
     */
    public static float canvasScale(int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) return 1f;
        float w = widthPx / (float) BASE_WIDTH_PX;
        float h = heightPx / (float) BASE_HEIGHT_PX;
        return Math.min(w, h);
    }

    /** 答题页居中大字：24–34sp，按题面长度自适应，长题也不会挤掉选项网格。 */
    public static int sp(String s) {
        int n = s == null ? 0 : s.length();
        if (n > 72) return 24;
        if (n > 48) return 27;
        if (n > 28) return 30;
        if (n > 16) return 32;
        return 34;
    }

    /** 屏幕逻辑宽度相对 960dp 基准的缩放，clamp 后用于统一各电视的视觉字号。 */
    public static float deviceScale(int widthPx, float density) {
        if (widthPx <= 0 || density <= 0f) return 1f;
        float ratio = (widthPx / density) / BASE_WIDTH_DP;
        return clamp(ratio, MIN_SCALE, MAX_SCALE);
    }

    public static float px(float designSp, float density, float scale) {
        if (designSp <= 0f || density <= 0f || scale <= 0f) return 0f;
        return designSp * density * scale;
    }

    public static float minPx(float designSp, float density, float scale) {
        return px(designSp, density, scale) * MIN_RATIO;
    }

    public static float clamp(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
