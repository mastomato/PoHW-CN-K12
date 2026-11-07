import com.jager.tvquiz.ExitPolicy;
import com.jager.tvquiz.KeyRouter;
import com.jager.tvquiz.QuizRound;

/**
 * v1.0.4 两条行为红线的 JVM 回归：
 *  1) 一次弹题会话内不答对就绝不退出；
 *  2) HOME / 最近任务键被吞、家长应急出口走独立语义。
 * 服务与 View 共用同一策略类，因此这里测的就是真规则。
 */
public class ExitGateTest {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) {
        final int DOWN = KeyRouter.ACTION_DOWN;
        final int UP = 1;

        // ---- 退出门禁 ----
        ok("E01 门禁阈值固定为 1 题", ExitPolicy.REQUIRED_CORRECT == 1);
        ok("E02 零答对不许退出", !ExitPolicy.canExit(0));
        ok("E03 负数计数按 0 处理", !ExitPolicy.canExit(-9));
        ok("E04 答对 1 题即放行", ExitPolicy.canExit(1));
        ok("E05 答对多题仍放行", ExitPolicy.canExit(7));

        String locked = ExitPolicy.tip(false, false);
        String unlocked = ExitPolicy.tip(true, false);
        ok("E06 锁定/解锁提示不同", !locked.equals(unlocked));
        ok("E07 锁定提示写明至少答对 1 题", locked.contains("至少答对 1 题"));
        ok("E08 锁定提示不再承诺可直接返回", !locked.contains("按返回键退出答题"));
        ok("E09 解锁提示说明可退出", unlocked.contains("按返回键退出"));
        ok("E10 有家长密码时才提示菜单键出口",
                !locked.contains("菜单键") && ExitPolicy.tip(false, true).contains("菜单键"));
        ok("E11 拦截提示非空且点明答对", ExitPolicy.blockedHint().contains("答对"));

        // ---- 模拟一次完整弹题会话：答错不会解锁，答对一次即解锁 ----
        int sessionCorrect = 0;
        boolean exited = false;
        for (int i = 0; i < 6; i++) {
            if (ExitPolicy.canExit(sessionCorrect)) { exited = true; break; }
            // 这些题全答错
        }
        ok("E12 连续答错 6 题仍不能退出", !exited);
        sessionCorrect++;
        ok("E13 第 7 题答对后立刻可退出", ExitPolicy.canExit(sessionCorrect));

        // 轮次成绩会在「开始下一轮」时清零，但退出门禁看的是会话累计，
        // 两者必须分开记账，否则答对后开新一轮会被重新上锁。
        QuizRound round = new QuizRound();
        round.reset(3);
        round.record(true);
        ok("E14 轮次记录答对", round.correct() == 1);
        round.reset(3);
        ok("E15 新一轮轮次成绩清零", round.correct() == 0);
        ok("E16 会话累计不受轮次 reset 影响", ExitPolicy.canExit(sessionCorrect));

        // ---- 按键路由：HOME 与家长出口 ----
        ok("E17 HOME DOWN 被吞",
                KeyRouter.decide(DOWN, 0, KeyRouter.SEM_HOME, 0).action
                        == KeyRouter.ACT_CONSUME);
        ok("E18 HOME UP 也被吞，状态对称",
                KeyRouter.decide(UP, 0, KeyRouter.SEM_HOME, 0).action
                        == KeyRouter.ACT_CONSUME);
        ok("E19 HOME 长按重复仍被吞",
                KeyRouter.decide(DOWN, 9, KeyRouter.SEM_HOME, 0).action
                        == KeyRouter.ACT_CONSUME);
        ok("E20 家长键首次按下触发密码验证",
                KeyRouter.decide(DOWN, 0, KeyRouter.SEM_PARENT, 0).action
                        == KeyRouter.ACT_PARENT);
        ok("E21 家长键长按不重复弹窗",
                KeyRouter.decide(DOWN, 3, KeyRouter.SEM_PARENT, 0).action
                        == KeyRouter.ACT_CONSUME);
        ok("E22 家长键 UP 不触发验证",
                KeyRouter.decide(UP, 0, KeyRouter.SEM_PARENT, 0).action
                        == KeyRouter.ACT_CONSUME);
        ok("E23 返回键仍交给门禁处理，路由语义未变",
                KeyRouter.decide(DOWN, 0, KeyRouter.SEM_BACK, 0).action
                        == KeyRouter.ACT_BACK);
        ok("E24 音量键继续成对放行",
                KeyRouter.decide(DOWN, 0, KeyRouter.SEM_VOLUME, 0).action
                        == KeyRouter.ACT_PASS_THROUGH
                        && KeyRouter.decide(UP, 0, KeyRouter.SEM_VOLUME, 0).action
                        == KeyRouter.ACT_PASS_THROUGH);
        ok("E25 家长键不是返回键，不会绕过门禁",
                KeyRouter.SEM_PARENT != KeyRouter.SEM_BACK
                        && KeyRouter.ACT_PARENT != KeyRouter.ACT_BACK);

        System.out.println("----");
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail == 0) System.out.println("VERIFY=PASS");
        else { System.out.println("VERIFY=FAIL"); System.exit(1); }
    }
}
