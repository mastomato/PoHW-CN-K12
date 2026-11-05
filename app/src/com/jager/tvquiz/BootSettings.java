package com.jager.tvquiz;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

/**
 * 打开厂商「自启动」设置页。小米/OPPO/华为/vivo 的入口各不相同，
 * 逐项尝试显式 ComponentName，最后降级到本应用详情页或系统设置。
 * 第三方应用拿不到开机自启权限，必须由家长在厂商设置里手动允许。
 */
public final class BootSettings {

    private BootSettings() {}

    private static final String[][] CANDIDATES = {
            {"com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
    };

    public static boolean open(Context c) {
        for (String[] p : CANDIDATES) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(p[0], p[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return true;
            } catch (Exception ignored) {
                // 该 ROM 没有这个入口，继续尝试下一个
            }
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception ignored) {
            // fall through
        }
        Toast.makeText(c,
                "未找到厂商自启动入口，请在系统设置搜索「自启动」并允许本应用",
                Toast.LENGTH_LONG).show();
        return false;
    }
}
