package com.jager.tvquiz;

import android.content.Context;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * 自适应字号 TextView：测量时把字号收进容器宽高，文字不会被裁切。
 *
 * 上下限由 {@link Ui} 按「设计 sp × 屏幕归一比例」注入；这里只负责在
 * onMeasure 里用 {@link AutoFit} 求最优字号。带结果缓存，避免每次布局
 * 都重新二分。
 */
public class FitTextView extends TextView {

    private float fitMinPx = -1f;
    private float fitMaxPx = -1f;
    private int cacheKey;
    private float cachePx = -1f;

    public FitTextView(Context c) {
        super(c);
    }

    /** 注入像素上下限（已包含 density 与跨屏归一比例）。 */
    public void setFitRangePx(float minPx, float maxPx) {
        if (maxPx <= 0f) return;
        fitMinPx = Math.max(1f, minPx);
        fitMaxPx = maxPx;
        cacheKey = 0;
        cachePx = -1f;
        requestLayout();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        if (fitMaxPx > 0f) {
            int availW = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
            int hMode = MeasureSpec.getMode(hSpec);
            int availH = hMode == MeasureSpec.EXACTLY
                    ? MeasureSpec.getSize(hSpec) - getPaddingTop() - getPaddingBottom()
                    : Integer.MAX_VALUE;
            CharSequence text = getText();
            int key = (text == null ? 0 : text.hashCode() * 31)
                    + availW * 7
                    + (availH == Integer.MAX_VALUE ? 0 : availH * 13)
                    + (int) fitMaxPx;
            if (key != cacheKey) {
                cachePx = AutoFit.bestPx(this, availW, availH, fitMinPx, fitMaxPx);
                cacheKey = key;
            }
            if (cachePx > 0f && Math.abs(getTextSize() - cachePx) > 0.5f) {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, cachePx);
            }
        }
        super.onMeasure(wSpec, hSpec);
    }
}
