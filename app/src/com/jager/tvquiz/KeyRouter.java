package com.jager.tvquiz;

/**
 * 遥控器按键决策：纯 Java，不引用任何 Android 类，可用 JVM 单测覆盖。
 * OverlayQuizView 负责把 KeyEvent 翻译成 semantic + value，本类负责
 * 「消费 / 放行 / 触发确认 / 移动高亮 / 直接选某项」以及长按重复过滤。
 */
public final class KeyRouter {

    private KeyRouter() {}

    public static final int ACTION_DOWN = 0;

    // 按键语义（由 View 层从 KeyEvent 翻译而来）
    public static final int SEM_OTHER = 0;
    public static final int SEM_BACK = 1;
    public static final int SEM_MEDIA = 2;
    public static final int SEM_VOLUME = 3;
    public static final int SEM_CONFIRM = 4;
    public static final int SEM_ANSWER = 5;   // value = 选项下标 0..3
    public static final int SEM_MOVE = 6;     // value = 方向 0左/1右/2上/3下

    // 决策结果
    public static final int ACT_PASS_THROUGH = 0;
    public static final int ACT_CONSUME = 1;
    public static final int ACT_CONFIRM = 2;
    public static final int ACT_ANSWER = 3;
    public static final int ACT_MOVE = 4;

    public static final class Decision {
        public final int action;
        public final int value;
        Decision(int action, int value) { this.action = action; this.value = value; }
    }

    /** 2x2 选项矩阵的方向键邻接表：LEFT / RIGHT / UP / DOWN。 */
    private static final int[][] NEXT = {
            {0, 1, 0, 1},
            {2, 3, 2, 3},
            {0, 0, 2, 2},
            {1, 1, 3, 3},
    };

    /**
     * @param keyAction  KeyEvent.getAction()
     * @param repeatCount KeyEvent.getRepeatCount()
     * @param semantic    按键语义
     * @param value       SEM_ANSWER 的选项下标 / SEM_MOVE 的方向
     */
    public static Decision decide(int keyAction, int repeatCount, int semantic, int value) {
        // 音量键无论 DOWN/UP 都成对放行给系统，避免吞掉 UP 造成状态不对称
        if (semantic == SEM_VOLUME) return new Decision(ACT_PASS_THROUGH, 0);
        // 非 DOWN 事件一律消费，答题期间不把按键透给底层应用
        if (keyAction != ACTION_DOWN) return new Decision(ACT_CONSUME, 0);
        if (semantic == SEM_CONFIRM) {
            return repeatCount > 0 ? new Decision(ACT_CONSUME, 0)
                    : new Decision(ACT_CONFIRM, 0);
        }
        if (semantic == SEM_ANSWER) {
            // 数字键与确定键一样要过滤长按重复，否则一次长按会连续换题
            return repeatCount > 0 ? new Decision(ACT_CONSUME, 0)
                    : new Decision(ACT_ANSWER, value);
        }
        if (semantic == SEM_MOVE) {
            // 方向键长按同样过滤 repeat：TV 遥控器长按会连发 DOWN，
            // 不过滤会导致高亮连续跳格、一次按键跨过多个选项。
            return repeatCount > 0 ? new Decision(ACT_CONSUME, 0)
                    : new Decision(ACT_MOVE, value);
        }
        // BACK / MEDIA / 其它：全部吞掉
        return new Decision(ACT_CONSUME, 0);
    }

    /** 按方向移动高亮；若目标选项已被禁用，顺延到下一个可用选项。 */
    public static int move(int selected, int dir, boolean[] disabled) {
        if (dir < 0 || dir > 3) return clampIndex(selected);
        int target = NEXT[dir][clampIndex(selected)];
        if (disabled != null && disabled[target]) {
            for (int i = 1; i < 4; i++) {
                int cand = (target + i) % 4;
                if (!disabled[cand]) { target = cand; break; }
            }
        }
        return target;
    }

    public static int clampIndex(int i) { return (i < 0 || i > 3) ? 0 : i; }
}
