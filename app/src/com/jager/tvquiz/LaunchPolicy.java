package com.jager.tvquiz;

/**
 * 「启动即守护」策略：APP 冷启动时是否自动拉起守护服务。
 * 纯 Java，便于 JVM 单测；真机拉起仍由 MainActivity 负责。
 *
 * 四个条件缺一不可：
 *  - coldStart：只在真正的冷启动（savedInstanceState 为空）触发。从后台返回前台
 *    只走 onResume，既不会重复 startService，也不会把家长手动关掉的守护又打开；
 *  - autoGuardOnLaunch：家长可在「家长设置」里关掉；
 *  - canDrawOverlays：未授权悬浮窗时不能拉起前台服务；
 *  - !alreadyRunning：服务已在跑就不重复拉起。
 *
 * 开机广播链路仍由 BootPolicy 管；两条链路共用 SettingsStore.autoGuardOnBoot()。
 */
public final class LaunchPolicy {

    private LaunchPolicy() {}

    public static boolean shouldAutoGuard(boolean coldStart,
                                          boolean autoGuardOnLaunch,
                                          boolean canDrawOverlays,
                                          boolean alreadyRunning) {
        return coldStart && autoGuardOnLaunch && canDrawOverlays && !alreadyRunning;
    }
}
