package com.jager.tvquiz;

/** 一轮答题的进度。答错也算作已完成一题，退出由遥控器返回键单独控制。 */
public final class QuizRound {
    private int total = 10;
    private int answered;
    private int correct;

    public void reset(int count) {
        total = count > 0 ? count : 10;
        answered = 0;
        correct = 0;
    }

    public void record(boolean right) {
        if (complete()) return;
        answered++;
        if (right) correct++;
    }

    public boolean complete() { return answered >= total; }
    public int total() { return total; }
    public int answered() { return answered; }
    public int correct() { return correct; }
    public int questionNumber() { return Math.min(answered + 1, total); }
}
