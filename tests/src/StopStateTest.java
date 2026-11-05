import android.content.Context;
import com.jager.tvquiz.GuardPolicy;
import com.jager.tvquiz.GuardState;
import com.jager.tvquiz.PinGate;
import com.jager.tvquiz.SettingsStore;

public class StopStateTest {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    static void keys(PinGate g, String s) {
        for (int i = 0; i < s.length(); i++) g.key(String.valueOf(s.charAt(i)));
    }

    public static void main(String[] args) {
        ok("运行中：开启按钮灰 / 关闭按钮红",
                GuardState.startColor(true) == GuardState.OFF_GRAY
                        && GuardState.stopColor(true) == GuardState.STOP_RED);
        ok("已关闭：开启按钮绿 / 关闭按钮灰",
                GuardState.startColor(false) == GuardState.ON_GREEN
                        && GuardState.stopColor(false) == GuardState.OFF_GRAY);
        ok("点击关闭后两个按钮颜色都发生变化",
                GuardState.startColor(true) != GuardState.startColor(false)
                        && GuardState.stopColor(true) != GuardState.stopColor(false));
        ok("两态均只有一个按钮是强调色",
                GuardState.mutuallyExclusive(true) && GuardState.mutuallyExclusive(false));
        ok("按钮文字随状态切换",
                "运行中".equals(GuardState.startLabel(true))
                        && "开启守护".equals(GuardState.startLabel(false))
                        && "关闭守护".equals(GuardState.stopLabel(true))
                        && "已关闭".equals(GuardState.stopLabel(false)));
        ok("合并开关：未运行为绿色 + 开启守护",
                GuardState.toggleColor(false) == GuardState.ON_GREEN
                        && "开启守护".equals(GuardState.toggleLabel(false)));
        ok("合并开关：运行中转为红色 + 关闭守护",
                GuardState.toggleColor(true) == GuardState.STOP_RED
                        && "关闭守护".equals(GuardState.toggleLabel(true)));
        ok("合并开关两态颜色与文案均可辨", GuardState.toggleDistinct());
        ok("合并开关任何时刻都无灰色停用态", GuardState.toggleAlwaysAccented());

        Context.resetAll();
        SettingsStore st = new SettingsStore(new Context());
        PinGate setup = new PinGate(st);
        keys(setup, "1234"); setup.key("确定");
        keys(setup, "1234"); setup.key("确定");
        ok("前置条件：家长密码已设置", st.hasPin());
        ok("有密码时关闭守护需要先验证", GuardPolicy.requiresPinToStop(st.hasPin()));

        PinGate gate = new PinGate(st);
        ok("就地验证层进入 VERIFY 态", gate.mode() == PinGate.VERIFY);
        keys(gate, "0000"); gate.key("确定");
        ok("错误密码不关闭守护，按钮保持运行状态",
                !GuardPolicy.allowStop(st.hasPin(), gate.consumeEnterSettings()));
        ok("验证失败后仍停留在验证态", gate.mode() == PinGate.VERIFY);
        ok("按返回键取消验证不触发关闭", !GuardPolicy.allowStop(st.hasPin(), false));

        keys(gate, "1234"); gate.key("确定");
        ok("仅正确密码放行关闭",
                GuardPolicy.allowStop(st.hasPin(), gate.consumeEnterSettings()));
        ok("放行后按钮切换为已关闭配色",
                GuardState.stopColor(false) == GuardState.OFF_GRAY
                        && "已关闭".equals(GuardState.stopLabel(false)));

        Context.resetAll();
        SettingsStore st2 = new SettingsStore(new Context());
        ok("未设置密码：不弹验证层直接关闭",
                !GuardPolicy.requiresPinToStop(st2.hasPin())
                        && GuardPolicy.allowStop(st2.hasPin(), false));
        ok("绕过界面直发未验证 STOP 仍被服务侧拒绝",
                !GuardPolicy.allowStop(true, false));

        System.out.println("----");
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail == 0) System.out.println("STOP_STATE_VERIFY=PASS");
        else { System.out.println("STOP_STATE_VERIFY=FAIL"); System.exit(1); }
    }
}
