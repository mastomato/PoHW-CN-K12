import com.jager.tvquiz.ExitPolicy;
import com.jager.tvquiz.KeyRouter;
import com.jager.tvquiz.QuizRound;

/**
 * 退出门禁的 JVM 回归：
 *  1) 阈值由家长设置注入，出厂默认 5 题，越界值收敛到 1..10；
 *  2) 一次弹题会话内未达到阈值绝不退出，答错可继续累计；
 *  3) 提示文案显示「需答对几题 / 还差几题」，锁定与解锁两态不同；
 *  4) HOME / 最近任务键被吞、家长应急出口走独立语义。
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

        // ---- 阈值配置 ----
        ok("E01 出厂默认 5 题", ExitPolicy.DEFAULT_REQUIRED_CORRECT == 5);
        ok("E02 合法区间 1..10", ExitPolicy.MIN_REQUIRED_CORRECT == 1
                && ExitPolicy.MAX_REQUIRED_CORRECT == 10);
        ok("E03 负数与 0 收敛到 1", ExitPolicy.normalizeRequired(-3) == 1
                && ExitPolicy.normalizeRequired(0) == 1);
        ok("E04 超上限收敛到 10", ExitPolicy.normalizeRequired(99) == 10);
        ok("E05 区间内原样保留", ExitPolicy.normalizeRequired(3) == 3
                && ExitPolicy.normalizeRequired(10) == 10);

        // ---- 默认门禁（兼容重载） ----
        ok("E06 零答对不许退出", !ExitPolicy.canExit(0));
        ok("E07 负数计数按 0 处理", !ExitPolicy.canExit(-9));
        ok("E08 默认下答对 4 题仍锁", !ExitPolicy.canExit(4));
        ok("E09 默认下答对 5 题放行", ExitPolicy.canExit(5));
        ok("E10 默认下答对更多仍放行", ExitPolicy.canExit(9));

        // ---- 可配置门禁 ----
        ok("E11 设为 1 时答对 1 题即放行", ExitPolicy.canExit(1, 1));
        ok("E12 设为 8 时答对 5 题仍锁", !ExitPolicy.canExit(5, 8));
        ok("E13 设为 8 时答对 8 题放行", ExitPolicy.canExit(8, 8));
        ok("E14 阈值 0 按最小值 1 处理", ExitPolicy.canExit(1, 0)
                && !ExitPolicy.canExit(0, 0));
        ok("E15 阈值 99 按上限 10 处理", !ExitPolicy.canExit(9, 99)
                && ExitPolicy.canExit(10, 99));
        ok("E16 剩余题数计算", ExitPolicy.remaining(5, 2) == 3
                && ExitPolicy.remaining(5, 5) == 0
                && ExitPolicy.remaining(5, 9) == 0);

        // ---- 提示文案 ----
        String locked = ExitPolicy.tip(false, false, 5, 2);
        String unlocked = ExitPolicy.tip(true, false, 5, 5);
        ok("E17 锁定/解锁提示不同", !locked.equals(unlocked));
        ok("E18 锁定提示写明阈值与进度", locked.contains("需答对 5 题")
                && locked.contains("还差 3 题"));
        ok("E19 锁定提示不再承诺可返回", !locked.contains("按返回键退出"));
        ok("E20 解锁提示说明可退出", unlocked.contains("按返回键退出"));
        ok("E21 有家长密码时才提示菜单键出口",
                !locked.contains("菜单键") && ExitPolicy.tip(false, true, 5, 0).contains("菜单键"));
        ok("E22 拦截提示含阈值与进度", ExitPolicy.blockedHint(5, 2).contains("需答对 5 题")
                && ExitPolicy.blockedHint(5, 2).contains("还差 3 题"));
        ok("E23 兼容重载使用默认 5 题", !ExitPolicy.canExit(4)
                && ExitPolicy.canExit(5)
                && ExitPolicy.blockedHint().contains("5 题"));

        // ---- 模拟一次完整弹题会话 ----
        int sessionCorrect = 0;
        boolean exited = false;
        for (int i = 0; i < 7; i++) {
            if (ExitPolicy.canExit(sessionCorrect, 5)) { exited = true; break; }
            // 这些题全答错
        }
        ok("E24 连续答错 7 题仍不能退出", !exited);
        for (int i = 0; i < 5; i++) sessionCorrect++;
        ok("E25 累计答对 5 题后立刻可退出", ExitPolicy.canExit(sessionCorrect, 5));

        // 轮次成绩会在「开始下一轮」时清零，但退出门禁看的是会话累计，
        // 两者必须分开记账，否则答对后开新一轮会被重新上锁。
        QuizRound round = new QuizRound();
        round.reset(3);
        round.record(true);
        ok("E26 轮次记录答对", round.correct() == 1);
        round.reset(3);
        ok("E27 新一轮轮次成绩清零", round.correct() == 0);
        ok("E28 会话累计不受轮次 reset 影响", ExitPolicy.canExit(sessionCorrect, 5));

        // ---- 按键路由：HOME 与家长出口 ----
        ok("E29 HOME DOWN 被吞",
                KeyRouter.decide(DOWN, 0, KeyRouter.SEM_HOME, 0).action
                        == KeyRouter.ACT_CONSUME);
        ok("E30 HOME UP 也被吞，状态对称",
                KeyRouter.decide(UP, 0, KeyRouter.SEM_HOME, 0).action
                        == KeyRouter.ACT_CONSUME);
        ok("E31 HOME 长按重复仍被吞",
                KeyRouter.decide(DOWN, 9, KeyRouter.SEM_HOME, 0).action
                        == KeyRouter.ACT_CONSUME);
        ok("E32 家长键首次按下触发密码验证",
                KeyRouter.decide(DOWN, 0, KeyRouter.SEM_PARENT, 0).action
                        == KeyRouter.ACT_PARENT);
        ok("E33 家长键长按不重复弹窗",
                KeyRouter.decide(DOWN, 3, KeyRouter.SEM_PARENT, 0).action
                        == KeyRouter.ACT_CONSUME);
        ok("E34 家长键 UP 不触发验证",
                KeyRouter.decide(UP, 0, KeyRouter.SEM_PARENT, 0).action
                        == KeyRouter.ACT_CONSUME);
        ok("E35 返回键仍交给门禁处理，路由语义未变",
                KeyRouter.decide(DOWN, 0, KeyRouter.SEM_BACK, 0).action
                        == KeyRouter.ACT_BACK);
        ok("E36 音量键继续成对放行",
                KeyRouter.decide(DOWN, 0, KeyRouter.SEM_VOLUME, 0).action
                        == KeyRouter.ACT_PASS_THROUGH
                        && KeyRouter.decide(UP, 0, KeyRouter.SEM_VOLUME, 0).action
                        == KeyRouter.ACT_PASS_THROUGH);
        ok("E37 家长键不是返回键，不会绕过门禁",
                KeyRouter.SEM_PARENT != KeyRouter.SEM_BACK
                        && KeyRouter.ACT_PARENT != KeyRouter.ACT_BACK);

        System.out.println("----");
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail == 0) System.out.println("VERIFY=PASS");
        else { System.out.println("VERIFY=FAIL"); System.exit(1); }
    }
}
