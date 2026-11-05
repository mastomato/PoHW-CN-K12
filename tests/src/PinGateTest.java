import android.content.Context;
import android.content.MemPrefs;
import com.jager.tvquiz.PinGate;
import com.jager.tvquiz.SettingsStore;

public class PinGateTest {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    static void keys(PinGate g, String s) {
        for (int i = 0; i < s.length(); i++) g.key(String.valueOf(s.charAt(i)));
    }

    static int dots(PinGate g) {
        String m = g.mask();
        int n = 0;
        for (int i = 0; i < m.length(); i++) if (m.charAt(i) == '●') n++;
        return n;
    }

    public static void main(String[] args) {
        ok("SHA-256 标准向量 abc",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
                        .equals(SettingsStore.sha256Hex("abc")));

        Context.resetAll();
        SettingsStore st = new SettingsStore(new Context());
        ok("A1 新装无密码", !st.hasPin());
        PinGate g = new PinGate(st);
        ok("A2 首次进入 SET_NEW", g.mode() == PinGate.SET_NEW);

        keys(g, "123"); g.key("确定");
        ok("A3 少于 4 位被拒绝", g.messageText().contains("至少 4 位")
                && g.mode() == PinGate.SET_NEW && !st.hasPin() && !g.consumeEnterSettings());

        keys(g, "1234"); g.key("确定");
        ok("A4 首次输入进入确认态",
                g.mode() == PinGate.SET_CONFIRM && g.consumeToast().contains("再输入一次"));

        keys(g, "1234"); g.key("确定");
        ok("A5 确认后真正落盘且哈希可校验",
                st.hasPin() && st.checkPin("1234") && !st.checkPin("0000"));
        ok("A6 确认后放行进入设置页", g.consumeEnterSettings());
        ok("A7 首次设置成功后 mode 归位 VERIFY（老版本停在 SET_CONFIRM）",
                g.mode() == PinGate.VERIFY);
        ok("A8 成功有明确提示", g.consumeToast().equals("家长密码已设置"));

        Context.restartProcess();
        SettingsStore st2 = new SettingsStore(new Context());
        ok("B1 杀进程重启后密码仍在（commit 同步落盘）",
                st2.hasPin() && st2.checkPin("1234"));
        PinGate g2 = new PinGate(st2);
        ok("B2 重启后直接进入 VERIFY", g2.mode() == PinGate.VERIFY);
        keys(g2, "0000"); g2.key("确定");
        ok("B3 错误密码被拒且不进入设置页",
                g2.messageText().contains("密码不对") && !g2.consumeEnterSettings());
        keys(g2, "1234"); g2.key("确定");
        ok("B4 正确密码放行", g2.consumeEnterSettings());

        g2.startChange();
        ok("C1 修改密码进入 CHG_OLD", g2.mode() == PinGate.CHG_OLD);
        keys(g2, "0000"); g2.key("确定");
        ok("C2 错误旧密码被拒且不推进",
                g2.messageText().contains("密码不对") && g2.mode() == PinGate.CHG_OLD);
        keys(g2, "1234"); g2.key("确定");
        ok("C3 旧密码正确进入 CHG_NEW", g2.mode() == PinGate.CHG_NEW);
        keys(g2, "5678"); g2.key("确定");
        ok("C4 进入 CHG_CONFIRM", g2.mode() == PinGate.CHG_CONFIRM);
        keys(g2, "9999"); g2.key("确定");
        ok("C5 两次不一致回到 CHG_NEW",
                g2.mode() == PinGate.CHG_NEW && g2.messageText().contains("不一致"));
        keys(g2, "5678"); g2.key("确定");
        keys(g2, "5678"); g2.key("确定");
        ok("C6 修改后旧密码失效、新密码生效",
                st2.checkPin("5678") && !st2.checkPin("1234"));
        ok("C7 修改成功放行并有提示",
                g2.consumeEnterSettings() && g2.consumeToast().equals("家长密码已更新"));
        Context.restartProcess();
        ok("C8 重启后新密码仍有效", new SettingsStore(new Context()).checkPin("5678"));

        Context.resetAll();
        Context c3 = new Context();
        SettingsStore st3 = new SettingsStore(c3);
        MemPrefs mp = (MemPrefs) c3.getSharedPreferences(SettingsStore.PREF, Context.MODE_PRIVATE);
        mp.setFailCommit(true);
        PinGate g3 = new PinGate(st3);
        keys(g3, "1234"); g3.key("确定");
        keys(g3, "1234"); g3.key("确定");
        ok("D1 落盘失败有明确提示", g3.messageText().contains("保存失败"));
        ok("D2 落盘失败不会假装成功", !g3.consumeEnterSettings() && !st3.hasPin());

        Context.resetAll();
        PinGate g4 = new PinGate(new SettingsStore(new Context()));
        keys(g4, "12345678901234567890");
        ok("E1 输入上限 8 位", dots(g4) == 8);
        g4.key("退格");
        ok("E2 遥控器 DEL 退格生效", dots(g4) == 7);
        g4.key("清空");
        ok("E3 清空生效", g4.mask().equals("—"));
        g4.key("x");
        ok("E4 非数字输入被忽略", g4.mask().equals("—"));

        System.out.println("----");
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail == 0) System.out.println("PIN_GATE_VERIFY=PASS");
        else { System.out.println("PIN_GATE_VERIFY=FAIL"); System.exit(1); }
    }
}
