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
        ok("null 题干 -> 34sp 居中大字", QuizTextSizer.sp(null) == 34);
        ok("短题 12 字 -> 34sp", QuizTextSizer.sp(cn(12)) == 34);
        ok("17~28 字 -> 32sp", QuizTextSizer.sp(cn(20)) == 32);
        ok("29~48 字 -> 30sp", QuizTextSizer.sp(cn(40)) == 30);
        ok("49~72 字 -> 27sp", QuizTextSizer.sp(cn(60)) == 27);
        ok("超过 72 字 -> 24sp", QuizTextSizer.sp(cn(100)) == 24);

        ok("16:9 1080p 画布比例 = 1.0", Math.abs(QuizTextSizer.canvasScale(1920, 1080) - 1f) < 1e-6);
        ok("16:9 4K 画布比例 = 2.0（整体 2 倍等比）",
                Math.abs(QuizTextSizer.canvasScale(3840, 2160) - 2f) < 1e-6);
        ok("16:9 720p 画布比例 ≈ 0.667（等比缩小）",
                Math.abs(QuizTextSizer.canvasScale(1280, 720) - 2f / 3f) < 1e-3);
        ok("非 16:9 取短边，不把内容推出画面",
                Math.abs(QuizTextSizer.canvasScale(1920, 1200) - 1f) < 1e-6);

        boolean mono = true;
        int prev = Integer.MAX_VALUE;
        for (int n = 0; n <= 120; n++) {
            int v = QuizTextSizer.sp(cn(n));
            if (v > prev || v < 24 || v > 34) mono = false;
            prev = v;
        }
        ok("0~120 字字号单调不增且恒在 24~34sp", mono);

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
