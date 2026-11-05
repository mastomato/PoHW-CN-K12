package com.jager.tvquiz;

/**
 * 家长密码门禁状态机。
 *
 * 刻意不依赖任何 Android API，便于在普通 JVM 上做真实单元测试。
 * Activity 只负责把按键喂进来、把 title/mask/message/toast 渲染出去。
 *
 * 修复要点：
 *  - 首次设置成功后 mode 归位 VERIFY（老版本停在 SET_CONFIRM，返回门禁页会状态错乱）
 *  - 密码落盘失败有明确提示，不会静默当作设置成功
 *  - 支持遥控器实体数字键、DEL 退格、HOME 重新判定 PIN 是否已存在
 */
public final class PinGate {

    public interface Store {
        boolean hasPin();
        boolean checkPin(String pin);
        /** 同步落盘并回读校验，失败必须返回 false。 */
        boolean setPin(String pin);
    }

    public static final int VERIFY = 0, SET_NEW = 1, SET_CONFIRM = 2,
            CHG_OLD = 3, CHG_NEW = 4, CHG_CONFIRM = 5;

    public static final int MSG_NONE = 0, MSG_TOO_SHORT = 1, MSG_WRONG_PIN = 2,
            MSG_MISMATCH = 3, MSG_SAVE_FAILED = 4;

    private final Store store;
    private final StringBuilder input = new StringBuilder();
    private int mode;
    private String pending = "";
    private int message = MSG_NONE;
    private String toast = "";
    private boolean enterSettings = false;

    public PinGate(Store s) {
        store = s;
        mode = s.hasPin() ? VERIFY : SET_NEW;
    }

    public int mode() { return mode; }

    public String title() {
        switch (mode) {
            case VERIFY:      return "家长设置 · 请输入密码";
            case SET_NEW:     return "首次使用 · 请设置 4~8 位家长密码";
            case SET_CONFIRM: return "再输入一次确认新密码";
            case CHG_OLD:     return "修改密码 · 请输入旧密码";
            case CHG_NEW:     return "修改密码 · 请输入新密码";
            default:          return "修改密码 · 再输入一次新密码";
        }
    }

    public String mask() {
        if (input.length() == 0) return "—";
        StringBuilder m = new StringBuilder();
        for (int i = 0; i < input.length(); i++) m.append("● ");
        return m.toString();
    }

    public String messageText() {
        switch (message) {
            case MSG_TOO_SHORT:   return "密码至少 4 位数字";
            case MSG_WRONG_PIN:   return "密码不对，请重试";
            case MSG_MISMATCH:    return "两次输入不一致，请重新设置";
            case MSG_SAVE_FAILED: return "密码保存失败，请重试或重启应用";
            default:              return "";
        }
    }

    /** 取走一次性 Toast，取完即清空。 */
    public String consumeToast() {
        String t = toast;
        toast = "";
        return t;
    }

    /** 取走「允许进入设置页」事件。 */
    public boolean consumeEnterSettings() {
        boolean e = enterSettings;
        enterSettings = false;
        return e;
    }

    /** 设置页点击「修改家长密码」。 */
    public void startChange() {
        mode = CHG_OLD;
        pending = "";
        input.setLength(0);
        message = MSG_NONE;
    }

    /** 从设置页退回门禁：重新判定是否已有密码，避免沿用旧 mode。 */
    public void backToGate() {
        mode = store.hasPin() ? VERIFY : SET_NEW;
        pending = "";
        input.setLength(0);
        message = MSG_NONE;
    }

    public void key(String k) {
        if (k == null) return;
        if ("清空".equals(k)) { input.setLength(0); message = MSG_NONE; return; }
        if ("退格".equals(k)) { if (input.length() > 0) input.deleteCharAt(input.length() - 1); message = MSG_NONE; return; }
        if ("确定".equals(k)) { submit(); return; }
        if (k.length() == 1 && k.charAt(0) >= '0' && k.charAt(0) <= '9') {
            if (input.length() < 8) { input.append(k); message = MSG_NONE; }
        }
    }

    private void submit() {
        String pin = input.toString();
        if (pin.length() < 4) {
            message = MSG_TOO_SHORT;
            input.setLength(0);
            return;
        }
        switch (mode) {
            case VERIFY:
                if (store.checkPin(pin)) {
                    message = MSG_NONE;
                    input.setLength(0);
                    enterSettings = true;
                } else {
                    message = MSG_WRONG_PIN;
                    input.setLength(0);
                }
                break;
            case SET_NEW:
                pending = pin;
                mode = SET_CONFIRM;
                message = MSG_NONE;
                input.setLength(0);
                toast = "请再输入一次确认";
                break;
            case SET_CONFIRM:
                if (pending.equals(pin)) {
                    if (store.setPin(pin)) {
                        pending = "";
                        input.setLength(0);
                        message = MSG_NONE;
                        mode = VERIFY;
                        toast = "家长密码已设置";
                        enterSettings = true;
                    } else {
                        message = MSG_SAVE_FAILED;
                        input.setLength(0);
                    }
                } else {
                    mode = SET_NEW;
                    pending = "";
                    input.setLength(0);
                    message = MSG_MISMATCH;
                    toast = "两次输入不一致，请重新设置";
                }
                break;
            case CHG_OLD:
                if (store.checkPin(pin)) {
                    mode = CHG_NEW;
                    message = MSG_NONE;
                    toast = "请输入新密码";
                } else {
                    message = MSG_WRONG_PIN;
                }
                input.setLength(0);
                break;
            case CHG_NEW:
                pending = pin;
                mode = CHG_CONFIRM;
                message = MSG_NONE;
                input.setLength(0);
                toast = "请再输入一次确认新密码";
                break;
            default: // CHG_CONFIRM
                if (pending.equals(pin)) {
                    if (store.setPin(pin)) {
                        pending = "";
                        input.setLength(0);
                        message = MSG_NONE;
                        mode = VERIFY;
                        toast = "家长密码已更新";
                        enterSettings = true;
                    } else {
                        message = MSG_SAVE_FAILED;
                        input.setLength(0);
                    }
                } else {
                    mode = CHG_NEW;
                    pending = "";
                    input.setLength(0);
                    message = MSG_MISMATCH;
                    toast = "两次不一致，请重新输入新密码";
                }
                break;
        }
    }
}
