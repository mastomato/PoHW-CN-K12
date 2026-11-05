#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PoHW 题库重建：内置原库(仅保留真实题) + cn-math-taxonomy + 参数化算式/常识题。

输出与 PoHW 原版完全同构的 quiz.db：
  questions(grade,grade_label,term,subject,subject_group,chapter,topic,text,
            opt_a..opt_d,answer_index,explain,source,qhash)
  knowledge / chapters 原样保留（仅用于首页统计展示）
  meta 更新统计
"""
import json
import os
import random
import re
import sqlite3
import hashlib
import math
import collections
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
SRC_DB = os.path.join(BASE, "apk_raw", "assets", "quiz.db")
QJSON = os.path.join(BASE, "src_data", "questions.json")
TJSON = os.path.join(BASE, "src_data", "topics.json")
OUT_DB = os.path.join(BASE, "quiz_new.db")
SEED = 20261105

GRADE_LABEL = {
    0: "幼儿园", 1: "一年级上", 2: "二年级上", 3: "三年级上", 4: "四年级上",
    5: "五年级上", 6: "六年级上", 7: "七年级上", 8: "八年级上", 9: "九年级上",
}
GROUP = {"数学": "math", "语文": "chinese", "英语": "english"}

SCHEMA = [
    """CREATE TABLE knowledge (id INTEGER PRIMARY KEY, raw_id TEXT, grade INTEGER,
       grade_label TEXT, term TEXT, subject TEXT, subject_group TEXT, chapter TEXT,
       topic TEXT, description TEXT, example TEXT, explanation TEXT, key_points TEXT,
       content TEXT, origins TEXT, UNIQUE(grade_label, subject, chapter, topic))""",
    """CREATE TABLE chapters (id INTEGER PRIMARY KEY, grade INTEGER, term TEXT,
       subject TEXT, chapter TEXT, topic TEXT)""",
    """CREATE TABLE questions (id INTEGER PRIMARY KEY, grade INTEGER NOT NULL,
       grade_label TEXT, term TEXT, subject TEXT NOT NULL, subject_group TEXT,
       chapter TEXT, topic TEXT, text TEXT NOT NULL, opt_a TEXT, opt_b TEXT,
       opt_c TEXT, opt_d TEXT, answer_index INTEGER NOT NULL, explain TEXT,
       source TEXT, qhash TEXT)""",
    """CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)""",
    "CREATE INDEX idx_kn_gs ON knowledge(grade, subject)",
    "CREATE INDEX idx_kn_group ON knowledge(grade, subject_group)",
    "CREATE INDEX idx_ch_gs ON chapters(grade, subject)",
    "CREATE UNIQUE INDEX idx_qhash_grade ON questions(qhash, grade)",
    "CREATE INDEX idx_gs ON questions(grade, subject)",
    "CREATE INDEX idx_grade ON questions(grade)",
    "CREATE INDEX idx_group ON questions(grade, subject_group)",
]

rng = random.Random(SEED)
rows = []          # (grade, term, subject, chapter, topic, text, [4 opts], ai, explain, source)
stats = collections.Counter()


def add(grade, term, subject, chapter, topic, text, opts, ai, explain, source):
    """入库前按 App 端 Question.fromBank 的规则校验，避免抽到题时被判无效。"""
    text = re.sub(r"\s+", " ", str(text or "")).strip()
    opts = [re.sub(r"\s+", " ", str(o or "")).strip() for o in opts]
    if len(opts) != 4 or not (0 <= ai <= 3):
        stats["reject_shape"] += 1
        return False
    if len(text) < 3:
        stats["reject_short"] += 1
        return False
    if any(not o for o in opts) or len(set(opts)) != 4:
        stats["reject_dupopt"] += 1
        return False
    rows.append((grade, term, subject, chapter, topic, text, opts, ai,
                 str(explain or "").strip(), source))
    stats["ok_" + subject] += 1
    return True


# ---------------------------------------------------------------- 1. 原库真实题
def load_builtin():
    con = sqlite3.connect(SRC_DB)
    cur = con.execute(
        "SELECT grade,term,subject,chapter,topic,text,opt_a,opt_b,opt_c,opt_d,"
        "answer_index,explain FROM questions WHERE explain NOT LIKE '纲目定位%'")
    n = 0
    for g, term, subj, ch, tp, text, a, b, c, d, ai, ex in cur:
        if add(g, term or "全", subj, ch, tp, text, [a, b, c, d], ai, ex, "pohw-builtin"):
            n += 1
    con.close()
    print(f"[1] 原库真实题: {n}")


# ---------------------------------------------------------------- 2. cn-math-taxonomy
PASSAGE_KW = ["课文", "本文", "文中", "这段话", "这段文字", "上文", "下文", "选文",
              "语段", "文章", "诗中", "词中", "这首", "作者", "这段", "阅读",
              "节选", "片段", "故事中", "短文", "passage", "according to",
              "the text", "the article"]

# 这些知识点整类都建立在课文原文之上，题目脱离原文无法作答，整类剔除
TOPIC_BLOCK = ("阅读《", "文言文《", "快乐读书吧", "阅读：")


def load_cnmt():
    topics = {t["id"]: t for t in json.load(open(TJSON, encoding="utf-8"))}
    questions = json.load(open(QJSON, encoding="utf-8"))
    SUBJ = {None: "数学", "chinese": "语文", "english": "英语", "physics": "物理"}
    n = 0
    for q in questions:
        tp = topics.get(q.get("topicId"))
        if not tp:
            stats["cnmt_no_topic"] += 1
            continue
        if q.get("type") != "choice":
            continue
        grade = tp.get("grade")
        if grade is None or grade > 9:
            stats["cnmt_high_school"] += 1
            continue
        ch = q.get("choices") or []
        if len(ch) != 4:
            stats["cnmt_opt_not4"] += 1
            continue
        if q.get("figure") or q.get("hideChoiceText") or any(c.get("figure") for c in ch):
            stats["cnmt_figure"] += 1
            continue
        opts = [(c.get("text") or "").strip() for c in ch]
        ans = (q.get("answer") or "").strip().upper()
        if ans not in ("A", "B", "C", "D"):
            stats["cnmt_bad_answer"] += 1
            continue
        subject = SUBJ[tp.get("subject")]
        topic_name = tp.get("name") or ""
        if any(topic_name.startswith(b) or b in topic_name for b in TOPIC_BLOCK):
            stats["cnmt_passage_topic"] += 1
            continue
        # 依赖课文/短文的题在电视上无法作答，剔除
        blob = (q.get("prompt") or "") + " " + " ".join(opts)
        if any(k in blob.lower() for k in PASSAGE_KW):
            stats["cnmt_needs_passage"] += 1
            continue
        if add(grade, tp.get("semester") or "上", subject, tp.get("chapter") or "",
               tp.get("name") or "", q.get("prompt") or "", opts,
               "ABCD".index(ans), q.get("explanation") or "",
               "cn-math-taxonomy"):
            n += 1
    print(f"[2] cn-math-taxonomy 采纳: {n}")


# ---------------------------------------------------------------- 3. 参数化算式
def distractors(ans, spread=6, positive=True, count=3):
    """围绕正确答案生成互不相同的干扰项。"""
    out, guard = [], 0
    pool = list(range(-spread, spread + 1))
    rng.shuffle(pool)
    for d in pool:
        if d == 0:
            continue
        v = ans + d
        if positive and v < 0:
            continue
        if v in out:
            continue
        out.append(v)
        if len(out) == count:
            break
        guard += 1
        if guard > 500:
            break
    while len(out) < count:            # 兜底
        v = ans + len(out) + 1
        if v not in out:
            out.append(v)
    return out


def num_q(grade, topic, text, ans, explain, spread=6, positive=True):
    ds = distractors(ans, spread=spread, positive=positive)
    opts = [ans] + ds
    rng.shuffle(opts)
    return add(grade, "全", "数学", topic, topic, text, opts, opts.index(ans),
               explain, "generated")


def text_q(grade, topic, text, ans, cand, explain):
    """字符串答案（分数/幂/根式）：从候选干扰项里取 3 个互不相同的。"""
    ds = [c for c in cand if c != ans]
    rng.shuffle(ds)
    ds = ds[:3]
    if len(ds) < 3:
        stats["text_q_skip"] += 1
        return 0
    opts = [ans] + ds
    rng.shuffle(opts)
    return add(grade, "全", "数学", topic, topic, text, opts, opts.index(ans),
               explain, "generated")


def frac_cand(num, den):
    """分数答案的干扰项候选：改分子、改分母、约分错误等。"""
    out = []
    for d in (-2, -1, 1, 2):
        if num + d > 0:
            out.append(f"{num + d}/{den}")
    for d in (-1, 1, 2):
        if den + d > 0:
            out.append(f"{num}/{den + d}")
    out.append(f"{num}/{den + 1}")
    out.append(f"{num + 1}/{den + 1}")
    return out


def gen_math():
    n = 0
    # 一年级：20 以内加减
    for _ in range(70):
        a, b = rng.randint(1, 10), rng.randint(1, 10)
        if a + b <= 20:
            n += num_q(1, "加减法", f"{a} ＋ {b} ＝ ?", a + b, f"{a}＋{b}＝{a+b}", 5)
    for _ in range(70):
        a = rng.randint(2, 20)
        b = rng.randint(1, a)
        n += num_q(1, "加减法", f"{a} － {b} ＝ ?", a - b, f"{a}－{b}＝{a-b}", 5)
    # 二年级：100 以内加减 + 乘法口诀 + 表内除法
    for _ in range(60):
        a, b = rng.randint(10, 80), rng.randint(1, 19)
        if a + b <= 100:
            n += num_q(2, "加减法", f"{a} ＋ {b} ＝ ?", a + b, f"{a}＋{b}＝{a+b}", 9)
    for _ in range(40):
        a = rng.randint(20, 99)
        b = rng.randint(1, a)
        n += num_q(2, "加减法", f"{a} － {b} ＝ ?", a - b, f"{a}－{b}＝{a-b}", 9)
    for a in range(2, 10):
        for b in range(2, 10):
            n += num_q(2, "乘法口诀", f"{a} × {b} ＝ ?", a * b, f"{a}×{b}＝{a*b}", 8)
    for b in range(2, 10):
        for c in range(2, 10):
            n += num_q(2, "表内除法", f"{b*c} ÷ {b} ＝ ?", c, f"{b}×{c}＝{b*c}，所以 {b*c}÷{b}＝{c}", 4)
    # 三年级：三位数加减 + 两位数乘一位数 + 三位数除以一位数
    for _ in range(50):
        a, b = rng.randint(100, 800), rng.randint(50, 199)
        n += num_q(3, "三位数加减法", f"{a} ＋ {b} ＝ ?", a + b, f"{a}＋{b}＝{a+b}", 12)
    for _ in range(50):
        a = rng.randint(200, 999)
        b = rng.randint(50, a - 1)
        n += num_q(3, "三位数加减法", f"{a} － {b} ＝ ?", a - b, f"{a}－{b}＝{a-b}", 12)
    for _ in range(40):
        a, b = rng.randint(11, 99), rng.randint(2, 9)
        n += num_q(3, "两位数乘一位数", f"{a} × {b} ＝ ?", a * b, f"{a}×{b}＝{a*b}", 15)
    for _ in range(30):
        b, c = rng.randint(2, 9), rng.randint(20, 120)
        n += num_q(3, "除法", f"{b*c} ÷ {b} ＝ ?", c, f"{b}×{c}＝{b*c}，所以 {b*c}÷{b}＝{c}", 8)
    # 四年级：三位数乘两位数 + 小数加减
    for _ in range(45):
        a, b = rng.randint(101, 999), rng.randint(11, 99)
        n += num_q(4, "三位数乘两位数", f"{a} × {b} ＝ ?", a * b, f"{a}×{b}＝{a*b}", 30)
    for _ in range(45):
        a = round(rng.uniform(1, 50), 1)
        b = round(rng.uniform(1, 20), 1)
        n += num_q(4, "小数加减法", f"{a} ＋ {b} ＝ ?", round(a + b, 1),
                   f"{a}＋{b}＝{round(a+b,1)}", 3)
    # 五年级：小数乘除 + 分数同分母加减
    for _ in range(40):
        a = round(rng.uniform(1, 12), 1)
        b = rng.randint(2, 9)
        n += num_q(5, "小数乘法", f"{a} × {b} ＝ ?", round(a * b, 1),
                   f"{a}×{b}＝{round(a*b,1)}", 4)
    for _ in range(40):
        # 结果必须是既约分数，否则「12/8」这种答案会让孩子困惑
        for _try in range(60):
            b = rng.randint(4, 12)
            a1 = rng.randint(1, b - 1)
            a2 = rng.randint(1, b - a1)
            if math.gcd(a1 + a2, b) == 1:
                break
        n += text_q(5, "分数加减法", f"{a1}/{b} ＋ {a2}/{b} ＝ ?",
                    f"{(a1+a2)}/{b}", frac_cand(a1 + a2, b),
                    f"同分母相加，分母不变，{a1}＋{a2}＝{a1+a2}，得 {(a1+a2)}/{b}")
    # 六年级：分数乘除 + 百分数
    for _ in range(35):
        for _try in range(60):
            b = rng.randint(3, 9)
            a1 = rng.randint(1, b - 1)
            c = rng.randint(2, 5)
            num = a1 * c
            if math.gcd(num, b) == 1 and num < b * 2:
                break
        n += text_q(6, "分数乘法", f"{a1}/{b} × {c} ＝ ?",
                    f"{num}/{b}", frac_cand(num, b),
                    f"分子与整数相乘，{a1}×{c}＝{num}，得 {num}/{b}")
    for _ in range(35):
        p = rng.choice([10, 20, 25, 40, 50, 60, 75, 80])
        base = rng.choice([20, 40, 50, 60, 80, 100, 120, 200])
        n += num_q(6, "百分数", f"{base} 的 {p}% 是多少？", round(base * p / 100, 2),
                   f"{base}×{p}%＝{round(base*p/100,2)}", 6)
    # 七年级：有理数运算 + 一元一次方程
    for _ in range(50):
        a, b = rng.randint(-20, 20), rng.randint(-20, 20)
        if a == 0 or b == 0:
            continue
        n += num_q(7, "有理数加法", f"({a}) ＋ ({b}) ＝ ?", a + b, f"{a}＋{b}＝{a+b}", 6, False)
    for _ in range(50):
        a, b = rng.randint(-15, 15), rng.randint(-15, 15)
        if a == 0 or b == 0:
            continue
        n += num_q(7, "有理数乘法", f"({a}) × ({b}) ＝ ?", a * b, f"{a}×{b}＝{a*b}", 8, False)
    for _ in range(45):
        a = rng.choice([2, 3, 4, 5, 6])
        x = rng.randint(-9, 9)
        b = rng.randint(-20, 20)
        n += num_q(7, "一元一次方程", f"若 {a}x ＋ ({b}) ＝ {a*x+b}，则 x ＝ ?", x,
                   f"{a}x＝{a*x+b}－({b})＝{a*x}，x＝{x}", 4, False)
    # 八年级：平方根 + 幂运算
    for _ in range(40):
        r = rng.randint(2, 20)
        n += num_q(8, "平方根", f"√{r*r} ＝ ?", r, f"{r}×{r}＝{r*r}，所以 √{r*r}＝{r}", 4)
    for _ in range(40):
        base = rng.choice([2, 3, 5])
        e1, e2 = rng.randint(2, 4), rng.randint(2, 4)
        n += text_q(8, "幂的运算", f"{base}^{e1} × {base}^{e2} ＝ ?", f"{base}^{e1+e2}",
                    [f"{base}^{e1+e2+1}", f"{base}^{e1*e2}", f"{base*base}^{e1+e2}",
                     f"{base}^{e1+e2-1}", f"{base}^{e2-e1+2}"],
                    f"同底数幂相乘，指数相加：{e1}＋{e2}＝{e1+e2}")
    # 九年级：一元二次方程 + 二次根式
    for _ in range(40):
        r = rng.randint(2, 12)
        n += num_q(9, "一元二次方程", f"x² ＝ {r*r} 的正数解是 x ＝ ?", r,
                   f"x²＝{r*r}，正数解 x＝{r}", 4)
    for _ in range(35):
        k = rng.choice([2, 3, 5])
        m = rng.choice([2, 3, 5, 6, 7, 10])      # 必须无平方因子，否则答案未化简
        n += text_q(9, "二次根式", f"√({k*k*m}) ＝ ?", f"{k}√{m}",
                    [f"{m}√{k}", f"{k}√{k}", f"{k*k}√{m}", f"{k+1}√{m}", f"{k}√{m+1}"],
                    f"{k*k*m}＝{k*k}×{m}，所以 √{k*k*m}＝{k}√{m}")
    print(f"[3] 参数化算式: {n}")


# ---------------------------------------------------------------- 4. 常识题（防跨年级串题）
ANIMAL_GROUPS = {
    "哺乳动物": ["猫", "狗", "牛", "羊", "大象", "蝙蝠", "鲸", "兔子"],
    "鸟类": ["麻雀", "鸡", "鸭", "鸽子", "老鹰", "企鹅"],
    "鱼类": ["鲤鱼", "鲫鱼", "草鱼", "带鱼", "鲨鱼"],
    "昆虫": ["蝴蝶", "蜜蜂", "蚂蚁", "蜻蜓", "蚊子"],
    "两栖动物": ["青蛙", "蟾蜍", "蝾螈"],
    "爬行动物": ["蛇", "乌龟", "蜥蜴", "鳄鱼"],
}
CONDUCTORS = ["铁钉", "铜丝", "铝勺", "盐水", "石墨棒"]
INSULATORS = ["塑料尺", "木筷子", "玻璃杯", "橡皮", "干布"]
ORGANS = [("心脏", "泵送血液"), ("肺", "进行气体交换"), ("胃", "消化食物"),
          ("大脑", "思考和指挥身体"), ("肾脏", "过滤血液产生尿液"),
          ("小肠", "吸收营养")]
PLANT_PARTS = [("根", "吸收水分和无机盐"), ("叶", "进行光合作用制造养料"),
               ("茎", "运输水分和养料"), ("花", "繁殖后代"),
               ("种子", "长成新的植株")]
SEASON = [("春天", "桃花盛开"), ("夏天", "荷花盛开"), ("秋天", "菊花盛开"), ("冬天", "梅花盛开")]


def gen_science():
    n = 0
    qs = []
    # 动物分类
    for group, members in ANIMAL_GROUPS.items():
        for m in members:
            others = [x for g, ms in ANIMAL_GROUPS.items() if g != group for x in ms]
            rng.shuffle(others)
            opts = [m] + others[:3]
            rng.shuffle(opts)
            qs.append((f"下列哪种动物属于{group}？", opts, opts.index(m),
                       f"{m}属于{group}。", f"动物分类：{group}"))
    # 反向：给出动物，问类别
    for group, members in ANIMAL_GROUPS.items():
        for m in members:
            others = [g for g in ANIMAL_GROUPS if g != group]
            rng.shuffle(others)
            opts = [group] + others[:3]
            rng.shuffle(opts)
            qs.append((f"「{m}」属于下列哪一类动物？", opts, opts.index(group),
                       f"{m}属于{group}。", f"动物分类：{group}"))
    # 排除型：哪个不属于某类
    for group, members in ANIMAL_GROUPS.items():
        for _ in range(3):
            inside = rng.sample(members, 3)
            outside = rng.choice([x for g, ms in ANIMAL_GROUPS.items() if g != group
                                  for x in ms])
            opts = inside + [outside]
            rng.shuffle(opts)
            qs.append((f"下列动物中，哪一个不属于{group}？", opts, opts.index(outside),
                       f"{outside}不属于{group}，其余三种都属于{group}。",
                       f"动物分类：{group}"))
    # 导体绝缘体
    for m in CONDUCTORS:
        others = rng.sample(INSULATORS, 3)
        opts = [m] + others
        rng.shuffle(opts)
        qs.append(("下列物品中，属于导体的是？", opts, opts.index(m),
                   f"{m}容易导电，是导体。", "导体与绝缘体"))
    for m in INSULATORS:
        others = rng.sample(CONDUCTORS, 3)
        opts = [m] + others
        rng.shuffle(opts)
        qs.append(("下列物品中，属于绝缘体的是？", opts, opts.index(m),
                   f"{m}不容易导电，是绝缘体。", "导体与绝缘体"))
    # 人体器官
    for organ, func in ORGANS:
        others = [o for o, _ in ORGANS if o != organ]
        rng.shuffle(others)
        opts = [func] + [dict(ORGANS)[o] for o in others[:3]]
        rng.shuffle(opts)
        qs.append((f"人体中，{organ}的主要作用是什么？", opts, opts.index(func),
                   f"{organ}的主要作用是{func}。", "人体器官"))
    # 植物部位
    for part, func in PLANT_PARTS:
        others = [f for p, f in PLANT_PARTS if p != part]
        rng.shuffle(others)
        opts = [func] + others[:3]
        rng.shuffle(opts)
        qs.append((f"植物的{part}的主要作用是什么？", opts, opts.index(func),
                   f"{part}的主要作用是{func}。", "植物的组成"))
    # 季节与花
    for season, flower in SEASON:
        others = [f for s, f in SEASON if s != season]
        rng.shuffle(others)
        opts = [flower] + others[:3]
        rng.shuffle(opts)
        qs.append((f"{season}最典型的景象是下面哪一个？", opts, opts.index(flower),
                   f"{season}的代表性景象是{flower}。", "四季变化"))
    # 安全常识
    qs.append(("发生火灾时，应该拨打哪个电话？", ["119", "110", "120", "114"], 0,
               "火警电话是 119。", "安全常识"))
    qs.append(("有人受伤需要急救时，应该拨打哪个电话？", ["120", "119", "110", "122"], 0,
               "急救电话是 120。", "安全常识"))
    qs.append(("遇到坏人或者需要报警时，应该拨打哪个电话？", ["110", "119", "120", "114"], 0,
               "报警电话是 110。", "安全常识"))
    # 天文
    qs.append(("地球绕着哪一颗星体转？", ["太阳", "月亮", "火星", "北极星"], 0,
               "地球绕太阳公转，一年一圈。", "地球与宇宙"))
    qs.append(("月亮绕着哪一颗星体转？", ["地球", "太阳", "火星", "金星"], 0,
               "月亮是地球的卫星，绕地球转。", "地球与宇宙"))
    qs.append(("一天中，太阳从哪个方向升起？", ["东方", "西方", "南方", "北方"], 0,
               "太阳东升西落。", "地球与宇宙"))

    rng.shuffle(qs)
    per_grade = {g: [] for g in range(1, 8)}
    idx = 0
    for g in range(1, 8):
        for _ in range(18):
            if idx >= len(qs):
                break
            text, opts, ai, ex, topic = qs[idx]
            idx += 1
            if add(g, "全", "科学", topic, topic, text, opts, ai, ex, "generated"):
                per_grade[g].append(text)
                n += 1
    print(f"[4] 常识题: {n}")


# ---------------------------------------------------------------- 写库
def write_db():
    if os.path.exists(OUT_DB):
        os.remove(OUT_DB)
    con = sqlite3.connect(OUT_DB)
    for s in SCHEMA:
        con.execute(s)

    # knowledge / chapters 原样搬迁（仅首页统计用）
    con.execute("ATTACH DATABASE ? AS old", (SRC_DB,))
    con.execute("INSERT INTO knowledge SELECT * FROM old.knowledge")
    con.execute("INSERT INTO chapters SELECT * FROM old.chapters")
    con.commit()
    con.execute("DETACH DATABASE old")

    seen = set()
    kept = 0
    for g, term, subj, ch, tp, text, opts, ai, ex, source in rows:
        key = (g, subj, text, tuple(opts), ai)
        if key in seen:
            stats["dup"] += 1
            continue
        seen.add(key)
        qh = hashlib.sha256(
            f"{subj}|{g}|{text}|{opts[0]}|{opts[1]}|{opts[2]}|{opts[3]}|{ai}"
            .encode("utf-8")).hexdigest()
        try:
            con.execute(
                "INSERT INTO questions(grade,grade_label,term,subject,subject_group,"
                "chapter,topic,text,opt_a,opt_b,opt_c,opt_d,answer_index,explain,"
                "source,qhash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (g, GRADE_LABEL.get(g, f"{g}年级"), term, subj,
                 GROUP.get(subj, "other"), ch, tp, text, opts[0], opts[1], opts[2],
                 opts[3], ai, ex, source, qh))
            kept += 1
        except sqlite3.IntegrityError:
            stats["dup_hash"] += 1

    total = con.execute("SELECT COUNT(*) FROM questions").fetchone()[0]
    nsub = con.execute("SELECT COUNT(DISTINCT subject) FROM questions").fetchone()[0]
    meta = {
        "version": "pohw-2026.11.05-merge-cnmt-v3",
        "updatedAt": "2026-11-05 21:45",
        "schema": "2",
        "questions": str(total),
        "knowledge": str(con.execute("SELECT COUNT(*) FROM knowledge").fetchone()[0]),
        "subjects": str(nsub),
        "grades": "21",
        "source": "PoHW builtin(real only) + giszh86/cn-math-taxonomy + generated",
        "sourceFiles": "3",
        "note": ("剔除内置题库中 4319 道「纲目定位」元数据题；"
                 "并入 cn-math-taxonomy 的四选一选择题（剔除依赖课文原文的题）；"
                 "数学与科学补充参数化生成题，答案由程序计算确定。"),
        "licenseNote": ("cn-math-taxonomy 部分为 CC BY-NC 4.0（非商业用途）；"
                        "learning-content 未附 LICENSE；仅作个人非商业学习用途。"),
    }
    for k, v in meta.items():
        con.execute("INSERT INTO meta(k,v) VALUES(?,?)", (k, v))
    con.commit()
    con.execute("VACUUM")
    con.close()
    print(f"[5] 写入完成: {total} 题, {nsub} 学科 -> {OUT_DB}")


def report():
    con = sqlite3.connect(OUT_DB)
    print("\n--- 各年级学科分布 ---")
    print(f"{'年级':<8}", end="")
    subs = [r[0] for r in con.execute(
        "SELECT subject FROM questions GROUP BY subject ORDER BY COUNT(*) DESC")]
    for s in subs:
        print(f"{s:>6}", end="")
    print(f"{'合计':>8}")
    for g in range(1, 10):
        print(f"{GRADE_LABEL[g]:<8}", end="")
        tot = 0
        for s in subs:
            c = con.execute("SELECT COUNT(*) FROM questions WHERE grade=? AND subject=?",
                            (g, s)).fetchone()[0]
            tot += c
            print(f"{c:>6}", end="")
        print(f"{tot:>8}")
    print("\n--- 来源 ---")
    for src, c in con.execute("SELECT source,COUNT(*) FROM questions GROUP BY source"):
        print(f"  {src}: {c}")
    print("\n--- 答案索引分布（应大致均匀）---")
    for ai, c in con.execute("SELECT answer_index,COUNT(*) FROM questions GROUP BY answer_index"):
        print(f"  {ai}: {c}")
    print("\n--- 自检 ---")
    bad = con.execute("SELECT COUNT(*) FROM questions WHERE opt_a=opt_b OR opt_a=opt_c "
                      "OR opt_a=opt_d OR opt_b=opt_c OR opt_b=opt_d OR opt_c=opt_d").fetchone()[0]
    bad2 = con.execute("SELECT COUNT(*) FROM questions WHERE answer_index NOT BETWEEN 0 AND 3 "
                       "OR LENGTH(text)<3 OR opt_a='' OR opt_b='' OR opt_c='' OR opt_d=''").fetchone()[0]
    print(f"  重复选项: {bad}   非法行: {bad2}")
    con.close()


if __name__ == "__main__":
    load_builtin()
    load_cnmt()
    gen_math()
    gen_science()
    import olympiad_gen
    print(f"[4] 奥数思维题: {olympiad_gen.build(add, rng)}")
    print("过滤统计:", dict(stats))
    write_db()
    report()
