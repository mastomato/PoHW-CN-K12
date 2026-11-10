package com.jager.tvquiz;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

/**
 * 16:9 画布自适应：把任何电视的逻辑尺寸统一到 1920×1080 设计基准。
 *
 * 做法是覆盖 density，让「960×540dp」始终等于整块屏幕：
 *  - 1080p（1920×1080）→ density 2，与设计稿 1:1；
 *  - 4K（3840×2160）→ density 4，所有 dp / 字号一起翻倍，画面与 1080p 完全等比；
 *  - 720p（1280×720）→ density ≈1.33，等比缩小；
 *  - 非 16:9 屏幕 → 按短边取比例，宁可留边也不裁内容。
 *
 * 因为只在 Activity / 悬浮窗的 Context 上覆盖，系统全局显示设置不受影响。
 */
public final class TvDisplay {

    private TvDisplay() {}

    public static Context wrap(Context base) {
        if (base == null) return null;
        DisplayMetrics dm = base.getResources().getDisplayMetrics();
        float scale = QuizTextSizer.canvasScale(dm.widthPixels, dm.heightPixels);
        int dpi = Math.round(160f * QuizTextSizer.BASE_DENSITY * scale);
        // densityDpi 有合法区间；极端分辨率（8K 等）钳一下，避免资源系统拒绝。
        if (dpi < 120) dpi = 120;
        if (dpi > 640) dpi = 640;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.densityDpi = dpi;
        return base.createConfigurationContext(cfg);
    }
}
