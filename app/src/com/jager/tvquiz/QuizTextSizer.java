package com.jager.tvquiz;

public final class QuizTextSizer {

    private QuizTextSizer() {}

    public static int sp(String s) {
        int n = s == null ? 0 : s.length();
        if (n > 72) return 19;
        if (n > 48) return 22;
        if (n > 28) return 25;
        if (n > 16) return 28;
        return 30;
    }
}
