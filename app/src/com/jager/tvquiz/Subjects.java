package com.jager.tvquiz;

/**
 * 学科常量与标签：0/1/2 与 QuizEngine.MATH/CHINESE/ENGLISH 一致，
 * 3 代表「其他学科」（全学科模式下物理/历史等题目归入此组）。
 * 纯 Java，无 Android 依赖，便于 JVM 单测。
 */
public final class Subjects {

    private Subjects() {}

    public static final int MATH = 0;
    public static final int CHINESE = 1;
    public static final int ENGLISH = 2;
    public static final int OTHER = 3;
    public static final int COUNT = 4;

    public static final String[] NAMES = {"数学", "语文", "英语", "其他学科"};

    public static int norm(int s) {
        return (s < 0 || s >= COUNT) ? OTHER : s;
    }

    public static String name(int s) {
        return NAMES[norm(s)];
    }

    /** 分学科教材版本在 SharedPreferences 中的键，四个学科彼此独立。 */
    public static String editionKey(int s) {
        return "edition_s" + norm(s);
    }
}
