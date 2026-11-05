import android.content.Context;
import com.jager.tvquiz.AnswerPolicy;
import com.jager.tvquiz.GuardPolicy;
import com.jager.tvquiz.KeyRouter;
import com.jager.tvquiz.QuizFlow;
import com.jager.tvquiz.SettingsStore;

/**
 * 行为级回归：遥控器按键时序、答题状态机、一次性 STOP 令牌。
 * 这些类是开发期 v6.0.0 审查指出的空白区（当时只测策略函数，不测行为序列）。
 * 类名中的 V601 是历史标识，不代表对外发布版本。
 */
public class BehaviorV601Test {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    static KeyRouter.Decision d(int action, int repeat, int semantic, int value) {
        return KeyRouter.decide(action, repeat, semantic, value);
    }

    public static void main(String[] args) {
        final int DOWN = KeyRouter.ACTION_DOWN;
        final int UP = 1;

        // ---------- KeyRouter: action 时序 ----------
        ok("K1 volume DOWN passed through",
                d(DOWN, 0, KeyRouter.SEM_VOLUME, 0).action == KeyRouter.ACT_PASS_THROUGH);
        ok("K2 volume UP passed through (symmetric)",
                d(UP, 0, KeyRouter.SEM_VOLUME, 0).action == KeyRouter.ACT_PASS_THROUGH);
        ok("K3 volume repeat passed through",
                d(DOWN, 7, KeyRouter.SEM_VOLUME, 0).action == KeyRouter.ACT_PASS_THROUGH);
        ok("K4 non-DOWN confirm consumed",
                d(UP, 0, KeyRouter.SEM_CONFIRM, 0).action == KeyRouter.ACT_CONSUME);
        ok("K5 confirm first DOWN fires",
                d(DOWN, 0, KeyRouter.SEM_CONFIRM, 0).action == KeyRouter.ACT_CONFIRM);
        ok("K6 confirm long-press repeat suppressed",
                d(DOWN, 1, KeyRouter.SEM_CONFIRM, 0).action == KeyRouter.ACT_CONSUME);
        ok("K7 answer first DOWN fires with value",
                d(DOWN, 0, KeyRouter.SEM_ANSWER, 3).action == KeyRouter.ACT_ANSWER
                        && d(DOWN, 0, KeyRouter.SEM_ANSWER, 3).value == 3);
        ok("K8 answer long-press repeat suppressed",
                d(DOWN, 2, KeyRouter.SEM_ANSWER, 1).action == KeyRouter.ACT_CONSUME);
        ok("K9 move first DOWN fires with value",
                d(DOWN, 0, KeyRouter.SEM_MOVE, 2).action == KeyRouter.ACT_MOVE
                        && d(DOWN, 0, KeyRouter.SEM_MOVE, 2).value == 2);
        ok("K10 move long-press repeat suppressed",
                d(DOWN, 1, KeyRouter.SEM_MOVE, 0).action == KeyRouter.ACT_CONSUME);
        ok("K11 back consumed",
                d(DOWN, 0, KeyRouter.SEM_BACK, 0).action == KeyRouter.ACT_CONSUME);
        ok("K12 media keys consumed",
                d(DOWN, 0, KeyRouter.SEM_MEDIA, 0).action == KeyRouter.ACT_CONSUME);
        ok("K13 unknown keys consumed",
                d(DOWN, 0, KeyRouter.SEM_OTHER, 0).action == KeyRouter.ACT_CONSUME);

        // ---------- KeyRouter: 2x2 高亮邻接表 ----------
        // 布局 [0][2] / [1][3]；方向 0左 1右 2上 3下
        ok("K14 left from top-right reaches top-left", KeyRouter.move(2, 0, null) == 0);
        ok("K15 right from bottom-left reaches bottom-right", KeyRouter.move(1, 1, null) == 3);
        ok("K16 up clamps at top row", KeyRouter.move(2, 2, null) == 2);
        ok("K17 down from top-left reaches bottom-left", KeyRouter.move(0, 3, null) == 1);
        ok("K18 disabled target skipped",
                KeyRouter.move(2, 0, new boolean[]{true, false, false, false}) == 1);
        ok("K19 all-but-one disabled chooses the survivor",
                KeyRouter.move(0, 1, new boolean[]{false, true, true, true}) == 0);
        ok("K20 out-of-range dir clamps", KeyRouter.move(2, 99, null) == 2);
        ok("K21 invalid selected clamps", KeyRouter.move(77, 0, null) == 0);

        // ---------- QuizFlow: 答错 2 次换题状态序列 ----------
        QuizFlow f = new QuizFlow();
        f.reset();
        ok("F1 reset starts clean",
                f.wrongAttempts() == 0 && f.selected() == 0 && !f.isAnswered()
                        && !f.isDisabled(0) && !f.isDisabled(1)
                        && !f.isDisabled(2) && !f.isDisabled(3));
        ok("F2 wrong #1 -> KEEP", f.submit(0, 2) == QuizFlow.KEEP);
        ok("F3 wrong #1 disables picked option", f.isDisabled(0) && f.wrongAttempts() == 1);
        ok("F4 selection moves to an enabled option", f.selected() != 0 && !f.isDisabled(f.selected()));
        ok("F5 disabled option cannot be resubmitted", f.submit(0, 2) == QuizFlow.IGNORE);
        ok("F6 wrong #2 -> SWAP", f.submit(1, 2) == QuizFlow.SWAP);
        ok("F7 wrong count reaches limit", f.wrongAttempts() == AnswerPolicy.WRONG_LIMIT);
        ok("F8 both wrong options disabled", f.isDisabled(0) && f.isDisabled(1));
        f.reset();
        ok("F9 reset after swap clears all state",
                f.wrongAttempts() == 0 && !f.isAnswered()
                        && !f.isDisabled(0) && !f.isDisabled(1));
        ok("F10 correct -> CORRECT", f.submit(2, 2) == QuizFlow.CORRECT);
        ok("F11 answered flag set", f.isAnswered());
        ok("F12 submit after correct ignored", f.submit(0, 0) == QuizFlow.IGNORE);
        ok("F13 move still callable but no crash after answer", f.move(1) >= 0 && f.move(1) <= 3);
        QuizFlow f2 = new QuizFlow();
        ok("F14 illegal index ignored",
                f2.submit(-1, 0) == QuizFlow.IGNORE && f2.submit(4, 0) == QuizFlow.IGNORE);
        ok("F15 invalid answerIndex never correct", f2.submit(1, 99) == QuizFlow.KEEP);

        // ---------- SettingsStore: 一次性 STOP 令牌 ----------
        Context.resetAll();
        SettingsStore st = new SettingsStore(new Context());
        ok("T1 no token -> stop not verified", !st.consumeStopToken(null));
        ok("T2 empty token -> stop not verified", !st.consumeStopToken(""));
        String t = st.issueStopToken();
        ok("T3 token is 48 hex chars", t != null && t.length() == 48 && t.matches("[0-9a-f]{48}"));
        String t2 = st.issueStopToken();
        ok("T4 reissue produces a different token", !t.equals(t2));
        ok("T5 wrong token rejected", !st.consumeStopToken("deadbeef"));
        ok("T6 failed attempt still burns the token", !st.consumeStopToken(t2));
        String t3 = st.issueStopToken();
        ok("T7 valid token accepted", st.consumeStopToken(t3));
        ok("T8 replay rejected (one-shot)", !st.consumeStopToken(t3));
        String t4 = st.issueStopToken();
        Context.restartProcess();
        SettingsStore st2 = new SettingsStore(new Context());
        ok("T9 token survives process restart (commit, not apply)", st2.consumeStopToken(t4));
        String t5 = st2.issueStopToken();
        // 手动把签发时间改成 2 分钟前，验证 TTL
        android.content.SharedPreferences sp =
                new Context().getSharedPreferences(SettingsStore.PREF, Context.MODE_PRIVATE);
        sp.edit().putLong("stop_token_at", System.currentTimeMillis() - 120000L).commit();
        ok("T10 expired token rejected", !st2.consumeStopToken(t5));
        ok("T11 expired token removed", "".equals(sp.getString("stop_token", "")));

        // ---------- GuardPolicy + token 组合 ----------
        ok("G1 hasPin + no token -> denied",
                !GuardPolicy.allowStop(true, st2.consumeStopToken(null)));
        String t6 = st2.issueStopToken();
        ok("G2 hasPin + valid token -> allowed",
                GuardPolicy.allowStop(true, st2.consumeStopToken(t6)));
        ok("G3 no pin + no token -> allowed",
                GuardPolicy.allowStop(false, st2.consumeStopToken(null)));
        ok("G4 forged boolean cannot replace a token",
                !GuardPolicy.allowStop(true, false));

        System.out.println("----");
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail == 0) System.out.println("BEHAVIOR_V601_VERIFY=PASS");
        else { System.out.println("BEHAVIOR_V601_VERIFY=FAIL"); System.exit(1); }
    }
}
