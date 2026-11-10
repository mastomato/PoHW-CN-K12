package com.jager.tvquiz;

import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.widget.TextView;

/**
 * 测量驱动的字号自适应：在容器可用宽高内二分出「能完整显示文本的最大字号」。
 *
 * 与单纯按字数猜 sp 不同，这里用真正的排版结果（StaticLayout 的行数与总高）
 * 判断是否放得下，因此换行、加粗 span、行距都会被算进去；
 * 大于容器就降档，直到最小字号为止。
 */
public final class AutoFit {

    private AutoFit() {}

    /**
     * @param availW 可用宽度（已扣除左右 padding），像素
     * @param availH 可用高度（已扣除上下 padding），像素；{@link Integer#MAX_VALUE} 表示不限
     * @return 能完整显示的最大字号（px），必定落在 [minPx, maxPx] 内
     */
    public static float bestPx(TextView v, int availW, int availH, float minPx, float maxPx) {
        if (availW <= 0 || maxPx <= 0f) return maxPx;
        if (maxPx <= minPx) return maxPx;
        CharSequence text = v.getText();
        if (text == null || text.length() == 0) return maxPx;

        TextPaint paint = new TextPaint(v.getPaint());
        int maxLines = v.getMaxLines();
        // 绝大多数控件用设计字号就能放下，先试一次，避免无谓的二分。
        if (fits(v, paint, text, availW, availH, maxLines, maxPx)) return maxPx;

        float lo = minPx;
        float hi = maxPx;
        for (int i = 0; i < 12; i++) {
            float mid = (lo + hi) * 0.5f;
            if (fits(v, paint, text, availW, availH, maxLines, mid)) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** 给定字号下，文本能否在可用宽高内完整排版（不超行数、不超高度）。 */
    static boolean fits(TextView v, TextPaint paint, CharSequence text,
                        int availW, int availH, int maxLines, float px) {
        paint.setTextSize(px);
        StaticLayout layout = new StaticLayout(text, paint, Math.max(1, availW),
                Layout.Alignment.ALIGN_NORMAL, v.getLineSpacingMultiplier(),
                v.getLineSpacingExtra(), false);
        if (maxLines > 0 && layout.getLineCount() > maxLines) return false;
        return availH <= 0 || layout.getHeight() <= availH;
    }
}
