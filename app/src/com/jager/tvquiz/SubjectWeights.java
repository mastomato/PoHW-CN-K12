package com.jager.tvquiz;

import java.util.Random;

/**
 * 四槽学科权重（数学 / 语文 / 英语 / 其他学科）。
 * 纯 Java、零 Android 依赖，便于 JVM 单测用大样本验证真实分布。
 *
 * <p>语义：
 * <ul>
 *   <li>allSubjects=false：第 4 槽有效权重恒为 0，只在三科里抽题；</li>
 *   <li>allSubjects=true 且 otherAvailable=true：第 4 槽参与轮盘赌；</li>
 *   <li>otherAvailable=false（内置题库尚未打开、且没有其他学科在线题包）：
 *       第 4 槽同样不参与，避免选中 OTHER 后只能回退成数学题、破坏配比。</li>
 * </ul>
 * 旧的 gradeWide 全年级跨科抽样与硬编码 15% 分支都由本类取代：
 * 家长设置的配比现在真正决定题目来自哪个学科池。
 */
public final class SubjectWeights {

    /** 与 SettingsStore.weight() 的 0..5 范围一致，防止异常值撑爆 total。 */
    public static final int MAX_WEIGHT = 5;

    private final int[] w = new int[Subjects.COUNT];
    private boolean allSubjects;
    // 默认不可用：只有 QuizEngine 确认题库/题包里真有非三科题后才显式置位。
    private boolean otherAvailable = false;

    public SubjectWeights(int math, int chinese, int english, int other) {
        w[Subjects.MATH] = clampWeight(math);
        w[Subjects.CHINESE] = clampWeight(chinese);
        w[Subjects.ENGLISH] = clampWeight(english);
        w[Subjects.OTHER] = clampWeight(other);
    }

    private static int clampWeight(int v) {
        return v < 0 ? 0 : (v > MAX_WEIGHT ? MAX_WEIGHT : v);
    }

    public void setAllSubjects(boolean v) { this.allSubjects = v; }
    public void setOtherAvailable(boolean v) { this.otherAvailable = v; }
    public boolean isAllSubjects() { return allSubjects; }
    public boolean isOtherAvailable() { return otherAvailable; }

    public int raw(int subject) { return w[Subjects.norm(subject)]; }

    /** 其他学科在全学科关闭、或题库不可用时有效权重为 0。 */
    public int effective(int subject) {
        int s = Subjects.norm(subject);
        if (s == Subjects.OTHER && !(allSubjects && otherAvailable)) return 0;
        return w[s];
    }

    public int total() {
        int t = 0;
        for (int i = 0; i < w.length; i++) t += effective(i);
        return t;
    }

    /** 按有效权重轮盘赌；全为 0 时兜底返回数学，保证一定能出题。 */
    public int pick(Random r) {
        int t = total();
        if (t <= 0) return Subjects.MATH;
        int n = r.nextInt(t);
        for (int i = 0; i < w.length; i++) {
            n -= effective(i);
            if (n < 0) return i;
        }
        return Subjects.ENGLISH;
    }
}
