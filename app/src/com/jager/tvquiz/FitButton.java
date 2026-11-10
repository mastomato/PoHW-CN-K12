package com.jager.tvquiz;

import android.content.Context;
import android.util.TypedValue;
import android.widget.Button;

/**
 * 自适应字号按钮：与 {@link FitTextView} 同一套测量逻辑，保证按钮文案
 * （如「厂商自启设置」「错题库 · 12 题」）在任何屏宽下都完整显示。
 */
public class FitButton extends Button {

    private float fitMinPx = -1f;
    private float fitMaxPx = -1f;
    private int cacheKey;
    private float cachePx = -1f;

    public FitButton(Context c) {
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
