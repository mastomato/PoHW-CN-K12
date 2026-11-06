package com.jager.tvquiz;

/** 单次作答的答案判定，零 Android 依赖。 */
public final class AnswerPolicy {

    private AnswerPolicy() {}

    public static boolean isCorrect(int picked, int answerIndex) {
        return picked >= 0 && picked < 4 && answerIndex >= 0 && answerIndex < 4
                && picked == answerIndex;
    }
}
