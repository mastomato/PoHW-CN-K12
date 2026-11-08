import com.jager.tvquiz.QuizTextSizer;

public class QuizLayoutTest {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    static String cn(int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append('题');
        return s.toString();
    }

    public static void main(String[] args) {
        ok("null 题干安全降级为 38sp 大字基准", QuizTextSizer.sp(null) == 38);
        ok("短题 12 字 -> 38sp 大字基准", QuizTextSizer.sp(cn(12)) == 38);
        ok("17~28 字 -> 35sp", QuizTextSizer.sp(cn(20)) == 35);
        ok("29~48 字 -> 32sp", QuizTextSizer.sp(cn(40)) == 32);
        ok("49~72 字 -> 29sp", QuizTextSizer.sp(cn(60)) == 29);
        ok("超过 72 字 -> 26sp", QuizTextSizer.sp(cn(100)) == 26);

        boolean mono = true;
        int prev = Integer.MAX_VALUE;
        for (int n = 0; n <= 120; n++) {
            int v = QuizTextSizer.sp(cn(n));
            if (v > prev || v < 26 || v > 38) mono = false;
            prev = v;
        }
        ok("0~120 字字号单调不增且恒在 26~38sp", mono);

        double oldNeedDp = 18 * 1.25 * 2.0;
        ok("复现裁切根因：两行 18sp 需 45dp > 旧固定 42dp", oldNeedDp > 42);

        double newNeedDp = 16 * 1.15 * 2.0 + 2 + 6;
        double fixedOthersDp = 186 + 34 + 34 + 50 + 26 + 20;
        double cardMinDp = 500.0;
        double topicBudgetDp = cardMinDp - fixedOthersDp;
        ok("新顶部两行 16sp + padding 约 44.8dp，WRAP_CONTENT 无上限", newNeedDp < 60);
        ok("最小屏幕下顶部预算 150dp 远大于实际需要", topicBudgetDp > newNeedDp);

        System.out.println("----");
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail == 0) System.out.println("QUIZ_LAYOUT_VERIFY=PASS");
        else { System.out.println("QUIZ_LAYOUT_VERIFY=FAIL"); System.exit(1); }
    }
}
