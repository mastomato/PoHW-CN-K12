#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""小学奥数 / 思维训练题生成器。

设计原则（和 PoHW 的弹题场景强相关）：
1. 答案一律由公式算出，不依赖任何外部题源，因此不存在"答案写错"；
2. 题干控制在 60 字内、无图、无「如图所示」，电视全屏一屏能读完；
3. 干扰项取常见错解（差一、忘除以 2、单位错、少加 1 等），不是随机数；
4. 每题只考一个知识点，解析一行说清思路。

用法：由 build_bank_merge.py 调用 build(add, rng)。
"""


def _fmt(ans, cands):
    """把答案与干扰项拼成 4 个互不相同的字符串选项。"""
    opts = [str(ans)]
    for c in cands:
        s = str(c)
        if isinstance(c, int) and c <= 0:
            continue
        if s not in opts and len(opts) < 4:
            opts.append(s)
    if len(opts) < 4:            # 兜底：数值型答案用 ±1/±2 补足
        try:
            base = int(ans)
            for d in (1, -1, 2, -2, 3, -3, 5, -5):
                s = str(base + d)
                if s not in opts and len(opts) < 4 and base + d > 0:
                    opts.append(s)
        except (TypeError, ValueError):
            pass
    return opts


def _q(add, rng, grade, topic, text, ans, cands, explain):
    opts = _fmt(ans, cands)
    if len(opts) != 4 or len(set(opts)) != 4:
        return False
    rng.shuffle(opts)
    return add(grade, "全", "数学", "奥数思维", "思维·" + topic, text,
               opts, opts.index(str(ans)), explain, "generated-olympiad")


# ---------------------------------------------------------------- 各专题

def t_hecha(rng, g):
    """和差问题：已知两数和与差。"""
    s = rng.randrange(24, 100) if g <= 2 else rng.randrange(100, 300)
    d = rng.randrange(2, max(4, s // 2))
    if (s + d) % 2:
        d += 1
    big, small = (s + d) // 2, (s - d) // 2
    if big <= 0 or small <= 0 or big == small:
        return None
    return (f"两个数的和是 {s}，差是 {d}，较大的数是多少？", big,
            [small, s - d, big + 1, big - 1],
            f"大数=(和+差)÷2=({s}+{d})÷2={big}")


def t_hebei(rng, g):
    """和倍问题：甲是乙的 k 倍，已知和。"""
    k = rng.choice([3, 4, 5, 6])
    b = rng.randrange(3, 40)
    a = b * k
    if a + b > 400:
        return None
    return (f"甲数是乙数的 {k} 倍，两数的和是 {a + b}，乙数是多少？", b,
            [a, b + 1, (a + b) // k, b - 1],
            f"乙数=和÷({k}+1)={a + b}÷{k + 1}={b}")


def t_chabei(rng, g):
    """差倍问题：甲比乙多 M，甲是乙的 k 倍。"""
    k = rng.choice([3, 4, 5])
    b = rng.randrange(3, 40)
    m = b * (k - 1)
    if m <= 0:
        return None
    return (f"甲数比乙数多 {m}，甲数是乙数的 {k} 倍，乙数是多少？", b,
            [b + 1, m // k, m - b, b - 1],
            f"乙数=差÷({k}-1)={m}÷{k - 1}={b}")


def t_jitu(rng, g):
    """鸡兔同笼。"""
    h = rng.randrange(8, 40)
    rabbit = rng.randrange(max(1, h // 4), max(2, h * 3 // 4))
    feet = rabbit * 4 + (h - rabbit) * 2
    return (f"笼子里鸡和兔共 {h} 个头、{feet} 只脚，兔有多少只？", rabbit,
            [h - rabbit, (feet - 2 * h) // 2 + 1, rabbit + 1, h],
            f"兔=(脚-2×头)÷2=({feet}-{2 * h})÷2={rabbit}")


def t_zhishu(rng, g):
    """植树问题：两端都栽。"""
    d = rng.choice([4, 5, 6, 8, 10])
    k = rng.randrange(4, 20)
    L = d * k
    return (f"一条 {L} 米的小路，每隔 {d} 米栽一棵树，两端都栽，共栽多少棵？", k + 1,
            [k, k + 2, k - 1, L // d],
            f"棵数=间隔数+1={L}÷{d}+1={k + 1}")


def t_yingkui(rng, g):
    """盈亏问题：每人 a 个多 m，每人 b 个少 n。"""
    a = rng.randrange(2, 6)
    b = a + rng.choice([1, 2, 3])
    kids = rng.randrange(3, 20)
    m = rng.randrange(1, 10)
    n = kids * (b - a) - m
    if n <= 0:
        return None
    return (f"老师分苹果，每人 {a} 个多 {m} 个，每人 {b} 个少 {n} 个，有多少个小朋友？", kids,
            [kids + 1, kids - 1, m + n, kids + 2],
            f"人数=(多+少)÷(每人差)=({m}+{n})÷({b}-{a})={kids}")


def t_nianling(rng, g):
    """年龄问题：几年后父是子的 k 倍。"""
    k = rng.choice([2, 3, 4])
    son = rng.randrange(4, 14)
    years = rng.randrange(1, 12)
    father = k * (son + years) - years
    if father <= son or father > 60:
        return None
    return (f"父亲今年 {father} 岁，儿子 {son} 岁，几年后父亲的年龄是儿子的 {k} 倍？", years,
            [years + 1, years - 1, years + 2, father - k * son],
            f"年龄差不变：({father}-{son})÷({k}-1)-{son}={years} 年")


def t_xingqi(rng, g):
    """周期问题：星期推算。"""
    names = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]
    start = rng.randrange(7)
    days = rng.randrange(10, 100)
    ans = names[(start + days) % 7]
    cands = [names[(start + days + 1) % 7], names[(start + days - 1) % 7],
             names[(start + days + 2) % 7]]
    return (f"今天是{names[start]}，再过 {days} 天是星期几？", ans, cands,
            f"{days}÷7 余 {days % 7}，往后数 {days % 7} 天")


def t_chouti(rng, g):
    """抽屉原理。"""
    m = rng.randrange(3, 7)
    k = rng.randrange(2, 6)
    n = m * (k - 1) + 1
    return (f"把 {n} 个苹果放进 {m} 个抽屉，总有一个抽屉至少放几个？", k,
            [k - 1, k + 1, n // m, n - m],
            f"{n}={m}×({k}-1)+1，所以至少 {k} 个")


def t_xianduan(rng, g):
    """数图形：直线上的点能连出多少条线段。"""
    n = rng.randrange(4, 8) if g <= 2 else rng.randrange(6, 13)
    ans = n * (n - 1) // 2
    return (f"一条直线上有 {n} 个点，一共可以数出多少条线段？", ans,
            [n, n - 1, ans + n, ans - 1],
            f"线段数={n}×{n - 1}÷2={ans}")


def t_guilv(rng, g):
    """找规律：低年级等差，中年级等差/等比，高年级等比或递增差。"""
    if g >= 4 and rng.random() < 0.5:
        a = rng.randrange(1, 6)
        seq = [a * (2 ** i) for i in range(4)]
        return ("找规律填数：" + "、".join(str(x) for x in seq) + "、（ ）", a * 16,
                [a * 8, a * 32, a * 16 + a, a * 16 - a],
                f"每个数是前一个的 2 倍，所以填 {a * 16}")
    a = rng.randrange(1, 10)
    d = rng.choice([2, 3]) if g <= 2 else rng.choice([4, 5, 6, 7])
    seq = [a + i * d for i in range(4)]
    return ("找规律填数：" + "、".join(str(x) for x in seq) + "、（ ）", a + 4 * d,
            [a + 5 * d, a + 3 * d, a + 4 * d + 1, a + 4 * d - 1],
            f"每次加 {d}，所以填 {a + 4 * d}")


def t_fangzhen(rng, g):
    """方阵问题：最外层人数。"""
    n = rng.randrange(5, 15)
    ans = 4 * n - 4
    return (f"同学们排成正方形方阵，每边 {n} 人，最外层一共有多少人？", ans,
            [4 * n, ans + 4, ans - 4, n * n],
            f"最外层=4×{n}-4={ans}（四个角重复算了一次）")


def t_xiangyu(rng, g):
    """行程：相遇。"""
    v1 = rng.choice([40, 45, 50, 55, 60])
    v2 = rng.choice([50, 60, 70, 80])
    t = rng.randrange(2, 6)
    D = (v1 + v2) * t
    return (f"两地相距 {D} 千米，甲车每小时 {v1} 千米，乙车每小时 {v2} 千米，"
            f"同时相向而行，几小时相遇？", t,
            [t + 1, t - 1, D // v1, t + 2],
            f"相遇时间=路程÷速度和={D}÷({v1}+{v2})={t} 小时")


def t_zhuiji(rng, g):
    """行程：追及。"""
    v1 = rng.choice([50, 60, 70, 80])
    dv = rng.choice([10, 15, 20])
    t = rng.randrange(2, 7)
    D = dv * t
    return (f"甲车在乙车后面 {D} 千米，两车同向行驶，甲每小时比乙快 {dv} 千米，"
            f"几小时追上？", t,
            [t + 1, t + 2, D // (v1 + dv), t + 3],
            f"追及时间=路程差÷速度差={D}÷{dv}={t} 小时")


def t_gongcheng(rng, g):
    """工程问题：两人合作。"""
    pairs = [(3, 6, 2), (4, 12, 3), (6, 12, 4), (5, 20, 4), (10, 15, 6), (12, 24, 8), (6, 3, 2)]
    a, b, t = rng.choice(pairs)
    return (f"一项工程，甲单独做要 {a} 天，乙单独做要 {b} 天，两人合作要几天完成？", t,
            [a + b, t + 1, t - 1, (a + b) // 2],
            f"合作时间=1÷(1/{a}+1/{b})={t} 天")


def t_nongdu(rng, g):
    """浓度问题：加水稀释。"""
    m = rng.choice([100, 200, 300, 400])
    p = rng.choice([20, 25, 30, 40])
    q = rng.choice([10, 5, 20])
    if q >= p:
        return None
    salt = m * p // 100
    if salt * 100 % q:
        return None
    total = salt * 100 // q
    add_water = total - m
    if add_water <= 0:
        return None
    return (f"有 {m} 克含盐 {p}% 的盐水，要变成含盐 {q}%，需要加水多少克？", add_water,
            [salt * 100 // q, add_water + 100, add_water // 2, m],
            f"盐={m}×{p}%={salt} 克，后来盐水={salt}÷{q}%={total} 克，加水 {add_water} 克")


def t_pingjun(rng, g):
    """平均数问题。"""
    n = rng.choice([3, 4, 5])
    avg = rng.randrange(10, 40)
    total = avg * n
    return (f"{n} 个数的平均数是 {avg}，它们的总和是多少？", total,
            [avg + n, avg, total + n, total - avg],
            f"总和=平均数×个数={avg}×{n}={total}")


def t_dengcha(rng, g):
    """等差数列求和：1+2+…+n。"""
    n = rng.choice([10, 20, 30, 50, 100])
    ans = n * (n + 1) // 2
    return (f"1+2+3+…+{n} 的和是多少？", ans,
            [n * n, ans + n, ans - n, n * (n + 1)],
            f"和=({n}+1)×{n}÷2={ans}")


def t_rongchi(rng, g):
    """容斥：两科都参加的人数。"""
    a = rng.randrange(15, 40)
    b = rng.randrange(15, 40)
    both = rng.randrange(3, min(a, b))
    total = a + b - both
    return (f"班里有 {a} 人参加语文小组，{b} 人参加数学小组，两组都参加的有 {both} 人，"
            f"全班共有多少人？", total,
            [a + b, total + both, total - both, a + b + both],
            f"总人数={a}+{b}-{both}={total}（都参加的算了两次）")


def t_yushu(rng, g):
    """余数问题：被除数最小是多少。"""
    d = rng.choice([5, 6, 7, 8, 9])
    q = rng.randrange(4, 15)
    r = rng.randrange(1, d)
    ans = d * q + r
    return (f"一个数除以 {d} 商 {q} 余 {r}，这个数是多少？", ans,
            [d * q, ans + d, ans - r, d * q - r],
            f"被除数=除数×商+余数={d}×{q}+{r}={ans}")


# 专题 → (适用年级, 每级题量)
SPECS = [
    ("找规律", [1, 2, 3, 4], t_guilv, 14),
    ("数线段", [2, 3, 4], t_xianduan, 10),
    ("和差问题", [2, 3, 4, 5], t_hecha, 10),
    ("和倍问题", [3, 4, 5], t_hebei, 10),
    ("差倍问题", [3, 4, 5], t_chabei, 10),
    ("鸡兔同笼", [3, 4, 5, 6], t_jitu, 12),
    ("植树问题", [3, 4, 5], t_zhishu, 10),
    ("周期问题", [3, 4, 5], t_xingqi, 8),
    ("方阵问题", [3, 4, 5], t_fangzhen, 8),
    ("盈亏问题", [4, 5, 6], t_yingkui, 10),
    ("年龄问题", [4, 5, 6], t_nianling, 10),
    ("平均数", [4, 5], t_pingjun, 8),
    ("抽屉原理", [5, 6], t_chouti, 8),
    ("相遇问题", [5, 6], t_xiangyu, 10),
    ("追及问题", [6], t_zhuiji, 10),
    ("工程问题", [6], t_gongcheng, 10),
    ("浓度问题", [6], t_nongdu, 8),
    ("等差数列", [5, 6], t_dengcha, 8),
    ("容斥原理", [5, 6], t_rongchi, 8),
    ("余数问题", [5, 6], t_yushu, 8),
]


def build(add, rng):
    """返回生成成功题数。"""
    total = 0
    for topic, grades, fn, per in SPECS:
        for g in grades:
            got, tries = 0, 0
            while got < per and tries < per * 12:
                tries += 1
                try:
                    r = fn(rng, g)
                except Exception:
                    r = None
                if not r:
                    continue
                text, ans, cands, explain = r
                if _q(add, rng, g, topic, text, ans, cands, explain):
                    got += 1
                    total += 1
    return total
