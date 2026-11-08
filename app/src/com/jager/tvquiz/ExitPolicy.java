package com.jager.tvquiz;

/**
 * 退出答题的门禁：一次弹题会话内，必须答对家长设定的题数才允许按返回键退出。
 *
 * 阈值由家长设置注入（SettingsStore.requiredCorrect()），默认 5 题，可在
 * 1..10 之间调整。规则刻意放在纯 Java 类里，作为「单一事实来源」：
 * View 用它决定返回键是否吞掉，Service 用它做第二道防线（任何绕过 View 的
 * onExit 都拦得住），JVM 单测直接覆盖。计数按「弹题会话」而不是「轮次」——
 * 连做几轮时，中途 reset 轮次不能把已答对的成绩清零重新上锁；
 * 新一次弹题（showQuiz）才重置，同时重新读取家长设定的阈值。
 */
public final class ExitPolicy {

    private ExitPolicy() {}

    /** 可选范围下限：至少答对 1 题，避免退化成「按返回直接走」。 */
    public static final int MIN_REQUIRED_CORRECT = 1;
    /** 可选范围上限：不超过 10 题，防止家长误设成几乎不可能完成的值。 */
    public static final int MAX_REQUIRED_CORRECT = 10;
    /** 出厂默认：本次弹题至少答对 5 题才能返回。 */
    public static final int DEFAULT_REQUIRED_CORRECT = 5;

    /** 越界值收敛到合法区间，负数和 0 一律按最小值处理。 */
    public static int normalizeRequired(int required) {
        if (required < MIN_REQUIRED_CORRECT) return MIN_REQUIRED_CORRECT;
        if (required > MAX_REQUIRED_CORRECT) return MAX_REQUIRED_CORRECT;
        return required;
    }

    /** @param sessionCorrect 本次弹题会话内累计答对题数（负数按 0 处理）。 */
    public static boolean canExit(int sessionCorrect, int required) {
        return Math.max(0, sessionCorrect) >= normalizeRequired(required);
    }

    /** 兼容纯 JVM 调用与旧测试：按出厂默认阈值判断。服务里一律走两参版本。 */
    public static boolean canExit(int sessionCorrect) {
        return canExit(sessionCorrect, DEFAULT_REQUIRED_CORRECT);
    }

    /** 距离解锁还差几题，已解锁时为 0。 */
    public static int remaining(int required, int sessionCorrect) {
        return Math.max(0, normalizeRequired(required) - Math.max(0, sessionCorrect));
    }

    /** 顶部操作提示，锁定/解锁两态必须给出不同文案，TV 远距离才看得懂。 */
    public static String tip(boolean canExit, boolean parentPinSet,
                             int required, int sessionCorrect) {
        int need = normalizeRequired(required);
        int done = Math.max(0, sessionCorrect);
        if (canExit) {
            return "方向键选择，按确定作答；已答对 " + done
                    + " 题，按返回键退出答题。";
        }
        String base = "方向键选择，按确定作答；本次需答对 " + need
                + " 题才能返回（已答对 " + done + " 题，还差 "
                + remaining(required, sessionCorrect) + " 题）。";
        return parentPinSet ? base + "家长可按菜单键输密码退出。" : base;
    }

    public static String tip(boolean canExit, boolean parentPinSet, int required) {
        return tip(canExit, parentPinSet, required,
                canExit ? normalizeRequired(required) : 0);
    }

    /** 兼容旧调用：按出厂默认阈值生成文案。 */
    public static String tip(boolean canExit, boolean parentPinSet) {
        return tip(canExit, parentPinSet, DEFAULT_REQUIRED_CORRECT);
    }

    /** 按了返回但不满足门禁时的一次性提示，明确显示进度。 */
    public static String blockedHint(int required, int sessionCorrect) {
        return "还没答够：需答对 " + normalizeRequired(required)
                + " 题，已答对 " + Math.max(0, sessionCorrect)
                + " 题，还差 " + remaining(required, sessionCorrect)
                + " 题才能退出答题继续观看。";
    }

    public static String blockedHint(int required) {
        return blockedHint(required, 0);
    }

    public static String blockedHint() {
        return blockedHint(DEFAULT_REQUIRED_CORRECT, 0);
    }
}
