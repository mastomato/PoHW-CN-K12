package com.jager.tvquiz;

/** 关闭守护的授权策略：单一规则源，便于 JVM 单测。 */
public final class GuardPolicy {

    private GuardPolicy() {}

    /** 已设置家长密码时，关闭守护必须先验证。 */
    public static boolean requiresPinToStop(boolean hasPin) {
        return hasPin;
    }

    /** 服务侧二次校验：有密码但未验证的 STOP 一律拒绝。 */
    public static boolean allowStop(boolean hasPin, boolean verified) {
        return !hasPin || verified;
    }
}
