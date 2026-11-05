package com.jager.tvquiz;

/**
 * 开机广播策略：action 白名单 + 两个开关的组合判定。
 * 纯 Java，便于 JVM 单测；真机拉起服务仍由 BootReceiver 负责。
 * 「启动即守护」另有 APP 冷启动链路（见 LaunchPolicy），本类只管开机广播。
 */
public final class BootPolicy {

    private BootPolicy() {}

    public static final String BOOT = "android.intent.action.BOOT_COMPLETED";
    public static final String QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON";
    public static final String HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON";

    public static boolean isBootAction(String action) {
        return BOOT.equals(action) || QUICKBOOT.equals(action) || HTC_QUICKBOOT.equals(action);
    }

    /**
     * bootAutostart 关 -> 完全不自启；未授权悬浮窗 -> 不拉起（避免后台 FGS 空转）。
     * autoGuardOnBoot 开 -> 无论上次守护是否开启都自动开启；
     * 否则仅恢复上次开启的守护状态。
     */
    public static boolean shouldStartGuard(boolean bootAutostart, boolean autoGuardOnBoot,
                                           boolean wasGuardEnabled, boolean canDrawOverlays,
                                           String action) {
        if (!isBootAction(action)) return false;
        if (!bootAutostart) return false;
        if (!canDrawOverlays) return false;
        return wasGuardEnabled || autoGuardOnBoot;
    }
}
