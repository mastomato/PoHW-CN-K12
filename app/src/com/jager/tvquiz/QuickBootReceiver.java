package com.jager.tvquiz;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 厂商快速开机广播（小米 QUICKBOOT_POWERON / HTC quickboot）单独接收。
 * 不声明 RECEIVE_BOOT_COMPLETED permission，否则厂商广播会被过滤；
 * action 白名单与两个开关仍由 BootPolicy 严格校验，伪造 action 无法绕过设置。
 *
 * Android 12+ 上该广播不在后台启动 FGS 豁免白名单，拉起可能失败：
 * BootReceiver.tryStartGuard() 已显式 catch 并记录，BOOT_COMPLETED 是可靠兜底。
 */
public class QuickBootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context c, Intent i) {
        BootReceiver.tryStartGuard(c, i);
    }
}
