package com.jager.tvquiz;

public final class QuizTextSizer {

    private QuizTextSizer() {}

    /** 答题页居中大字：24–34sp，按题面长度自适应，长题也不会挤掉选项网格。 */
    public static int sp(String s) {
        int n = s == null ? 0 : s.length();
        if (n > 72) return 24;
        if (n > 48) return 27;
        if (n > 28) return 30;
        if (n > 16) return 32;
        return 34;
    }
}
