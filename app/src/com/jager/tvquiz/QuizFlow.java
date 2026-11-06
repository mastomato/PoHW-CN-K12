package com.jager.tvquiz;

/**
 * 单题答题状态机：纯 Java，零 Android 依赖。
 * 每题只有一次作答：答对短暂反馈后自动下一题，答错显示解析并由用户继续。
 */
public final class QuizFlow {

    public static final int IGNORE = 0;   // 已答对 / 选项已禁用 / 下标非法
    public static final int WRONG = 1;    // 答错，显示正确答案与解析
    public static final int CORRECT = 3;  // 答对

    private int selected = 0;
    private boolean answered = false;

    /** 新题时清除上一题的选择和作答状态。 */
    public void reset() {
        selected = 0;
        answered = false;
    }

    /** 提交一次作答，返回 IGNORE / WRONG / CORRECT。 */
    public int submit(int idx, int answerIndex) {
        if (answered || idx < 0 || idx > 3) return IGNORE;
        selected = idx;
        answered = true;
        if (AnswerPolicy.isCorrect(idx, answerIndex)) {
            return CORRECT;
        }
        return WRONG;
    }

    public int move(int dir) {
        if (!answered) selected = KeyRouter.move(selected, dir, null);
        return selected;
    }

    public int selected() { return selected; }
    public boolean isAnswered() { return answered; }
}
