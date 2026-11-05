package com.jager.tvquiz;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * 开机自启：只接收受 RECEIVE_BOOT_COMPLETED 保护的 BOOT_COMPLETED。
 *
 * 厂商 QUICKBOOT 广播拆到 QuickBootReceiver。原因：
 *  1) QUICKBOOT_POWERON 不是受 RECEIVE_BOOT_COMPLETED 保护的广播，
 *     在本 receiver 上声明该 permission 会把厂商广播直接过滤掉；
 *  2) 它不在 Android 12+ 后台启动前台服务的豁免白名单里，
 *     startForegroundService() 可能抛 ForegroundServiceStartNotAllowedException，
 *     单独接收后才能显式降级、不影响 BOOT_COMPLETED 这条可靠路径。
 * 厂商（尤其小米）还需要在系统「自启动」设置里允许本应用接收开机广播，
 * 入口见 BootSettings.open()。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "PoHW.BootReceiver";

    /** 两个 receiver 共用的判定 + 拉起逻辑；action 白名单由 BootPolicy 严格校验。 */
    static void tryStartGuard(Context c, Intent i) {
        String action = i == null ? null : i.getAction();
        SettingsStore st = new SettingsStore(c);
        boolean canDraw = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
        if (!BootPolicy.shouldStartGuard(st.bootAutostart(), st.autoGuardOnBoot(),
                st.guardEnabled(), canDraw, action)) {
            return;
        }
        Intent s = new Intent(c, QuizOverlayService.class);
        s.setAction(QuizOverlayService.ACTION_START);
        try {
            // BOOT_COMPLETED 属于后台启动 FGS 的豁免广播，Android 8+ 允许直接拉起。
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
        } catch (Exception e) {
            Log.e(TAG, "开机拉起守护服务失败", e);
        }
    }

    @Override public void onReceive(Context c, Intent i) {
        tryStartGuard(c, i);
    }
}
