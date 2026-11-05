import android.content.Context;
import com.jager.tvquiz.GuardPolicy;
import com.jager.tvquiz.PinGate;
import com.jager.tvquiz.SettingsStore;

public class StopGuardTest {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    static void keys(PinGate g, String s) {
        for (int i = 0; i < s.length(); i++) g.key(String.valueOf(s.charAt(i)));
    }

    public static void main(String[] args) {
        ok("策略：已设置密码时关闭需验证", GuardPolicy.requiresPinToStop(true));
        ok("策略：未设置密码时关闭无需验证", !GuardPolicy.requiresPinToStop(false));
        ok("服务侧：有密码 + 未验证 -> 拒绝", !GuardPolicy.allowStop(true, false));
        ok("服务侧：有密码 + 已验证 -> 放行", GuardPolicy.allowStop(true, true));
        ok("服务侧：无密码 + 未验证 -> 放行", GuardPolicy.allowStop(false, false));
        ok("服务侧：无密码 + 已验证 -> 放行", GuardPolicy.allowStop(false, true));

        Context.resetAll();
        SettingsStore st = new SettingsStore(new Context());
        PinGate setup = new PinGate(st);
        keys(setup, "1234"); setup.key("确定");
        keys(setup, "1234"); setup.key("确定");
        ok("前置条件：家长密码已设置", st.hasPin());

        PinGate gate = new PinGate(st);
        ok("关闭验证页进入 VERIFY（不是首次设置）", gate.mode() == PinGate.VERIFY);

        String[] wrong = {"0000", "1111", "2222"};
        boolean neverAllowed = true;
        for (String w : wrong) {
            keys(gate, w);
            gate.key("确定");
            boolean entered = gate.consumeEnterSettings();
            if (GuardPolicy.allowStop(st.hasPin(), entered)) neverAllowed = false;
        }
        ok("连续 3 次错误密码均无法关闭守护", neverAllowed);
        ok("错误密码后仍停留在 VERIFY", gate.mode() == PinGate.VERIFY);

        keys(gate, "1234"); gate.key("确定");
        ok("仅正确密码放行关闭", GuardPolicy.allowStop(st.hasPin(), gate.consumeEnterSettings()));

        Context.resetAll();
        SettingsStore st2 = new SettingsStore(new Context());
        ok("无密码场景：直接放行关闭", GuardPolicy.allowStop(st2.hasPin(), false));

        System.out.println("----");
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail == 0) System.out.println("STOP_GUARD_VERIFY=PASS");
        else { System.out.println("STOP_GUARD_VERIFY=FAIL"); System.exit(1); }
    }
}
