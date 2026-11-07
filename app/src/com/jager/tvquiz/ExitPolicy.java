package com.jager.tvquiz;

/**
 * 退出答题的门禁：一次弹题会话内，必须至少答对一道题才允许按返回键退出。
 *
 * 规则刻意放在纯 Java 类里，作为「单一事实来源」：View 用它决定返回键是否吞掉，
 * Service 用它做第二道防线（任何绕过 View 的 onExit 都拦得住），JVM 单测直接覆盖。
 * 计数按「弹题会话」而不是「轮次」——连做几轮时，中途 reset 轮次不能把已答对的
 * 成绩清零重新上锁；新一次弹题（showQuiz）才重置。
 */
public final class ExitPolicy {

    private ExitPolicy() {}

    /** 允许退出所需的最少答对题数。 */
    public static final int REQUIRED_CORRECT = 1;

    /** @param sessionCorrect 本次弹题会话内累计答对题数（负数按 0 处理）。 */
    public static boolean canExit(int sessionCorrect) {
        return Math.max(0, sessionCorrect) >= REQUIRED_CORRECT;
    }

    /** 顶部操作提示，锁定/解锁两态必须给出不同文案，TV 远距离才看得懂。 */
    public static String tip(boolean canExit, boolean parentPinSet) {
        if (canExit) return "方向键选择，按确定作答；已答对，按返回键退出答题。";
        String base = "方向键选择，按确定作答；本次至少答对 "
                + REQUIRED_CORRECT + " 题才能按返回退出。";
        return parentPinSet ? base + "家长可按菜单键输密码退出。" : base;
    }

    /** 按了返回但不满足门禁时的一次性提示。 */
    public static String blockedHint() {
        return "还没答对题目：请先答对 " + REQUIRED_CORRECT
                + " 题，才能退出答题继续观看。";
    }
}
