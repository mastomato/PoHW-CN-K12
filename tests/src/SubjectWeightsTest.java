import android.content.Context;
import com.jager.tvquiz.SettingsStore;
import com.jager.tvquiz.SubjectWeights;
import com.jager.tvquiz.Subjects;

import java.util.Random;

/** 方案 B：四槽学科权重的开关语义、真实分布与持久化。 */
public class SubjectWeightsTest {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    static int[] sample(SubjectWeights w, int n) {
        Random r = new Random(20261105L);
        int[] c = new int[Subjects.COUNT];
        for (int i = 0; i < n; i++) c[w.pick(r)]++;
        return c;
    }

    static double pct(int v, int n) { return 100.0 * v / n; }

    static boolean near(double actual, double expect, double tol) {
        return Math.abs(actual - expect) <= tol;
    }

    public static void main(String[] args) {
        int n = 100000;
        SubjectWeights w = new SubjectWeights(5, 3, 2, 2);
        // QuizEngine 确认题库有非三科题后会显式置位；单测直接模拟这一已就绪状态。
        w.setOtherAvailable(true);

        ok("B01 四槽原始权重 5/3/2/2",
                w.raw(Subjects.MATH) == 5 && w.raw(Subjects.CHINESE) == 3
                        && w.raw(Subjects.ENGLISH) == 2 && w.raw(Subjects.OTHER) == 2);
        ok("B02 全学科关闭时第 4 槽有效权重为 0", w.effective(Subjects.OTHER) == 0);
        int[] off = sample(w, n);
        ok("B03 全学科关闭时绝不抽到其他学科", off[Subjects.OTHER] == 0);
        ok("B04 全学科关闭时三科按 5:3:2 分布",
                near(pct(off[Subjects.MATH], n), 50.0, 1.0)
                        && near(pct(off[Subjects.CHINESE], n), 30.0, 1.0)
                        && near(pct(off[Subjects.ENGLISH], n), 20.0, 1.0));

        w.setAllSubjects(true);
        ok("B05 全学科开启时第 4 槽生效，总权重 12",
                w.effective(Subjects.OTHER) == 2 && w.total() == 12);
        int[] on = sample(w, n);
        ok("B06 全学科开启时四科按 5:3:2:2 分布",
                near(pct(on[Subjects.MATH], n), 41.67, 1.0)
                        && near(pct(on[Subjects.CHINESE], n), 25.0, 1.0)
                        && near(pct(on[Subjects.ENGLISH], n), 16.67, 1.0)
                        && near(pct(on[Subjects.OTHER], n), 16.67, 1.0));
        ok("B07 其他学科确实能抽到", on[Subjects.OTHER] > 0);
        ok("B08 配比不会被全学科开关旁路", on[Subjects.MATH] > on[Subjects.OTHER] * 2);

        w.setOtherAvailable(false);
        ok("B09 题库不可用时第 4 槽自动失效",
                w.effective(Subjects.OTHER) == 0 && w.total() == 10);
        int[] noDb = sample(w, n);
        ok("B10 题库不可用时绝不抽到 OTHER", noDb[Subjects.OTHER] == 0);

        w.setAllSubjects(false);
        ok("B11 开关可逆，关闭后第 4 槽再次失效",
                !w.isAllSubjects() && w.effective(Subjects.OTHER) == 0);

        SubjectWeights zero = new SubjectWeights(0, 0, 0, 0);
        zero.setAllSubjects(true);
        ok("B12 全零权重 total=0", zero.total() == 0);
        ok("B13 全零时兜底返回数学，保证能出题",
                zero.pick(new Random(7)) == Subjects.MATH);

        SubjectWeights single = new SubjectWeights(0, 0, 0, 4);
        single.setAllSubjects(true);
        single.setOtherAvailable(true);
        ok("B14 只有第 4 槽非零时 100% 抽其他学科",
                sample(single, 1000)[Subjects.OTHER] == 1000);

        SubjectWeights noOther = new SubjectWeights(5, 3, 2, 0);
        noOther.setAllSubjects(true);
        ok("B15 第 4 槽设为 0 时全学科模式也不出其他题",
                sample(noOther, n)[Subjects.OTHER] == 0);

        SubjectWeights clamped = new SubjectWeights(-3, 99, 2, 7);
        ok("B16 权重 clamp 到 0..5",
                clamped.raw(Subjects.MATH) == 0 && clamped.raw(Subjects.CHINESE) == 5
                        && clamped.raw(Subjects.ENGLISH) == 2
                        && clamped.raw(Subjects.OTHER) == 5);

        Context.resetAll();
        SettingsStore st = new SettingsStore(new Context());
        ok("B17 四科默认配比为 5/3/2/2",
                st.weight(Subjects.MATH) == 5 && st.weight(Subjects.CHINESE) == 3
                        && st.weight(Subjects.ENGLISH) == 2 && st.weight(Subjects.OTHER) == 2);
        st.weight(Subjects.OTHER, 4);
        ok("B18 第 4 槽配比可持久化",
                new SettingsStore(new Context()).weight(Subjects.OTHER) == 4);
        st.weight(Subjects.OTHER, 99);
        ok("B19 第 4 槽配比上限仍为 5", st.weight(Subjects.OTHER) == 5);

        SubjectWeights cold = new SubjectWeights(5, 3, 2, 2);
        cold.setAllSubjects(true);
        ok("B20 未经 QuizEngine 置位时 OTHER 默认不参与轮盘赌",
                !cold.isOtherAvailable() && cold.effective(Subjects.OTHER) == 0);

        System.out.println("VERIFY=" + (fail == 0 ? "PASS" : "FAIL") + " total=" + pass);
    }
}
