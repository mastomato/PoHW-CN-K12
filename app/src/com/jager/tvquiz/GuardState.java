package com.jager.tvquiz;

/**
 * 控制台守护开关的视觉状态：单一规则源，零 Android 依赖，便于 JVM 单测。
 *
 * 双按钮时代：当前可执行的动作给强调色，已完成/不可执行的动作给灰色。
 * v1.0.1 起两个按钮合并为一个切换开关，颜色改为表达「当前可执行的动作」：
 * 绿 = GO（开启守护），红 = STOP（关闭守护，且需密码）。原有四个方法与
 * mutuallyExclusive 保留给旧版单测兼容，新界面只使用 toggleColor / toggleLabel。
 */
public final class GuardState {

    private GuardState() {}

    public static final int ON_GREEN = 0xFF1D9E75;
    public static final int OFF_GRAY = 0xFF37425A;
    public static final int STOP_RED = 0xFFA32D2D;

    /** 运行中「开启守护」已生效 -> 灰；未运行 -> 绿（可点）。 */
    public static int startColor(boolean running) { return running ? OFF_GRAY : ON_GREEN; }
    /** 运行中「关闭守护」可点 -> 红；已关闭 -> 灰。 */
    public static int stopColor(boolean running) { return running ? STOP_RED : OFF_GRAY; }

    public static String startLabel(boolean running) { return running ? "运行中" : "开启守护"; }
    public static String stopLabel(boolean running) { return running ? "关闭守护" : "已关闭"; }

    /** 两个开关永远只有一个处于强调色，避免用户误判当前状态。 */
    public static boolean mutuallyExclusive(boolean running) {
        int a = startColor(running), b = stopColor(running);
        return (a == OFF_GRAY) != (b == OFF_GRAY);
    }

    /** 合并开关：未运行=绿+开启守护，运行中=红+关闭守护。 */
    public static int toggleColor(boolean running) { return running ? STOP_RED : ON_GREEN; }

    public static String toggleLabel(boolean running) { return running ? "关闭守护" : "开启守护"; }

    /** 两态的颜色与文案必须都不同，否则 TV 远距离看不出状态变化。 */
    public static boolean toggleDistinct() {
        return toggleColor(true) != toggleColor(false)
                && !toggleLabel(true).equals(toggleLabel(false));
    }

    /** 合并开关不再有灰色停用态：任何时刻都可点。 */
    public static boolean toggleAlwaysAccented() {
        return toggleColor(true) != OFF_GRAY && toggleColor(false) != OFF_GRAY;
    }
}
