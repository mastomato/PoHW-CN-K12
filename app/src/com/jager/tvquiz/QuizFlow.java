package com.jager.tvquiz;

/**
 * 单题答题状态机：纯 Java，零 Android 依赖。
 * 遮罩的高亮/禁用状态与服务的「答错 2 次换题」计数共用同一个实例，
 * 避免两处状态各写一份导致换题后禁用项没恢复、计数没归零。
 */
public final class QuizFlow {

    public static final int IGNORE = 0;   // 已答对 / 选项已禁用 / 下标非法
    public static final int KEEP = 1;     // 答错但还没到换题阈值
    public static final int SWAP = 2;     // 已答错 2 次，需要换题
    public static final int CORRECT = 3;  // 答对

    private int wrongAttempts = 0;
    private int selected = 0;
    private boolean answered = false;
    private final boolean[] disabled = new boolean[4];

    /** 新题（首次弹题或换题）时调用：计数、禁用项、高亮、答对状态全部归零。 */
    public void reset() {
        wrongAttempts = 0;
        selected = 0;
        answered = false;
        for (int i = 0; i < 4; i++) disabled[i] = false;
    }

    /** 提交一次作答，返回 IGNORE / KEEP / SWAP / CORRECT。 */
    public int submit(int idx, int answerIndex) {
        if (answered || idx < 0 || idx > 3 || disabled[idx]) return IGNORE;
        selected = idx;
        if (AnswerPolicy.isCorrect(idx, answerIndex)) {
            answered = true;
            return CORRECT;
        }
        wrongAttempts++;
        disabled[idx] = true;
        selectEnabled();
        return AnswerPolicy.shouldSwap(wrongAttempts) ? SWAP : KEEP;
    }

    public int move(int dir) {
        selected = KeyRouter.move(selected, dir, disabled);
        return selected;
    }

    private void selectEnabled() {
        if (!disabled[selected]) return;
        for (int i = 0; i < 4; i++) {
            if (!disabled[i]) { selected = i; return; }
        }
    }

    public int selected() { return selected; }
    public int wrongAttempts() { return wrongAttempts; }
    public boolean isAnswered() { return answered; }
    public boolean isDisabled(int i) { return i >= 0 && i < 4 && disabled[i]; }
    public boolean[] disabledCopy() {
        return new boolean[]{disabled[0], disabled[1], disabled[2], disabled[3]};
    }
}
