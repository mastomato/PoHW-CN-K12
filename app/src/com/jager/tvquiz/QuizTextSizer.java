package com.jager.tvquiz;

public final class QuizTextSizer {

    private QuizTextSizer() {}

    /** v1.0.5 大字版：长题也有 26sp 基准，配合 Ui.TYPE_SCALE 后最小约 35sp。 */
    public static int sp(String s) {
        int n = s == null ? 0 : s.length();
        if (n > 72) return 26;
        if (n > 48) return 29;
        if (n > 28) return 32;
        if (n > 16) return 35;
        return 38;
    }
}
