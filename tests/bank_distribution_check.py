#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""方案 B 运行时回归：用 QuizDb.java 里的真实 SQL 常量实跑内置题库。

静态契约只能守住符号；这里从 QuizDb.java 的命名常量提取 WHERE 谓词
（先去掉 // 与 /* */ 注释，避免注释诱饵），并复刻 QuizDb.pick() 的
OFFSET 采样、双份参数绑定与三层兜底，验证行为：
1. 三科池只返回对应学科、其他池只返回非三科题目，
   因此 QuizDb.subjectFromBank() 反推出的标签与题目真实学科 100% 一致；
2. 四槽配比 5:3:2:2 下，题目真实学科分布接近 41.7/25/16.7/16.7；
3. 全学科关闭（第 4 槽为 0）时绝不出现其他学科题；
4. 某年级某池为空时，第一层兜底仍保持学科约束（只放宽年级）。
零第三方依赖，只用 Python 标准库；由 build.sh 的 [0/9] 调用。
"""
import io
import os
import random
import re
import sqlite3
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB = os.path.join(ROOT, "app/assets/quiz.db")
QUIZDB = os.path.join(ROOT, "app/src/com/jager/tvquiz/QuizDb.java")
QUIZENGINE = os.path.join(ROOT, "app/src/com/jager/tvquiz/QuizEngine.java")
CORE = ("数学", "语文", "英语")
SELECT = "SELECT subject FROM questions "


def fail(msg):
    print("BANK_DISTRIBUTION_FAIL: " + msg)
    sys.exit(1)


def strip_comments(text):
    """丢弃 // 行注释与 /* */ 块注释（含跨行块），防止注释里的诱饵 SQL 被提取。"""
    out = []
    in_block = False
    for line in text.splitlines():
        cur = line
        if in_block:
            end = cur.find("*/")
            if end < 0:
                continue
            cur = cur[end + 2:]
            in_block = False
        while True:
            start = cur.find("/*")
            if start < 0:
                break
            end = cur.find("*/", start + 2)
            if end < 0:
                cur = cur[:start]
                in_block = True
                break
            cur = cur[:start] + cur[end + 2:]
        if cur.lstrip().startswith("//"):
            continue
        out.append(cur)
    return "\n".join(out)


def extract_sql():
    """SQL 常量与路由/绑定必须与 Java 源码同源，否则改坏 QuizDb 也测不出来。"""
    src = strip_comments(io.open(QUIZDB, encoding="utf-8").read())
    eng = strip_comments(io.open(QUIZENGINE, encoding="utf-8").read())
    if "boolean other = s == Subjects.OTHER && allSubjects;" not in src:
        fail("QuizDb.java lost the OTHER pool routing condition")
    if "new String[args.length * 2]" not in src or src.count("System.arraycopy") < 2:
        fail("QuizDb.java lost the double WHERE parameter binding")
    if "pick(poolWhere, poolArgs)" not in src:
        fail("QuizDb.java lost the pool-preserving fallback")
    if "db.hasOtherQuestions()" not in eng:
        fail("QuizEngine no longer checks whether the bank has non-core questions")
    if "gradeWide" in src or "fromBank(subject," in src:
        fail("QuizDb.java regressed to gradeWide / chosen-pool labels")
    if "OFFSET (ABS(RANDOM()) % MAX(1, (SELECT COUNT(*) FROM questions " not in src:
        fail("QuizDb.java lost the empty-pool-safe OFFSET template")

    def const(name):
        m = re.search(name + r'\s*=\s*"([^"]+)";', src)
        if not m:
            fail("QuizDb.java lost SQL constant " + name)
        return m.group(1)

    core_grade = const("POOL_CORE_GRADE")
    other_grade = const("POOL_OTHER_GRADE")
    core_all = const("POOL_CORE_ALL")
    other_all = const("POOL_OTHER_ALL")
    if "grade=?" not in core_grade or "subject=?" not in core_grade:
        fail("POOL_CORE_GRADE lost its grade/subject predicate")
    if "NOT IN ('数学','语文','英语')" not in other_grade:
        fail("POOL_OTHER_GRADE lost its NOT IN predicate")
    if "grade=?" in core_all or "subject=?" not in core_all:
        fail("POOL_CORE_ALL must drop only the grade")
    if "grade=?" in other_all or "NOT IN ('数学','语文','英语')" not in other_all:
        fail("POOL_OTHER_ALL must drop only the grade")
    return core_grade, other_grade, core_all, other_all


CORE_WHERE, OTHER_WHERE, CORE_ALL, OTHER_ALL = extract_sql()


def pool_sql(pool, grade):
    """返回 (主查询谓词, 主查询参数, 兜底谓词, 兜底参数)，与 QuizDb.random 一致。"""
    if pool == 3:
        return OTHER_WHERE, (str(grade),), OTHER_ALL, None
    return (CORE_WHERE, (str(grade), CORE[pool]), CORE_ALL, (CORE[pool],))


def run(conn, where, args):
    """复刻 QuizDb.pick()：主查询与 COUNT 子查询各绑一次参数。"""
    sql = SELECT + where + \
        " LIMIT 1 OFFSET (ABS(RANDOM()) % MAX(1, (SELECT COUNT(*) FROM questions " + where + ")))"
    try:
        # sqlite3 不接受 None 作为 parameters（Java rawQuery 可以），无参数时直接不传。
        if args is None:
            return conn.execute(sql).fetchone()
        return conn.execute(sql, args * 2).fetchone()
    except sqlite3.Error as exc:
        fail("SQL execution failed for %r: %s" % (where, exc))


def draw(conn, where, args, pool_where, pool_args):
    """三层兜底：同年级同池 -> 同池跨年级 -> 全库（仅题库本身缺该科时）。"""
    row = run(conn, where, args)
    if row is None:
        row = run(conn, pool_where, pool_args)
    if row is None:
        row = run(conn, "", None)
    return None if row is None else row[0]


def label_of(bank_subject):
    """与 QuizDb.subjectFromBank() 的映射一致。"""
    for i, name in enumerate(CORE):
        if bank_subject == name:
            return i
    return 3


def roulette(weights, r):
    n = r.randrange(sum(weights))
    for i, w in enumerate(weights):
        n -= w
        if n < 0:
            return i
    return 2


def clone(conn):
    mem = sqlite3.connect(":memory:")
    conn.backup(mem)
    return mem


def verify_fallback(conn):
    """某年级某池为空时，第一层兜底只放宽年级、不放宽学科。"""
    mem = clone(conn)
    mem.execute("DELETE FROM questions WHERE grade=3 AND subject='英语'")
    where, args, pw, pa = pool_sql(2, 3)
    for _ in range(100):
        subject = draw(mem, where, args, pw, pa)
        if subject is None or label_of(subject) != 2:
            fail("empty English pool fell back to a non-English question")

    mem2 = clone(conn)
    mem2.execute("DELETE FROM questions WHERE grade=3 "
                 "AND subject NOT IN ('数学','语文','英语')")
    where, args, pw, pa = pool_sql(3, 3)
    for _ in range(100):
        subject = draw(mem2, where, args, pw, pa)
        if subject is None or label_of(subject) != 3:
            fail("empty other pool fell back to a core-subject question")
    print("FALLBACK_POOL_OK core=english other=noncore draws=200")


def main():
    if not os.path.isfile(DB):
        fail("missing " + DB)
    conn = sqlite3.connect(DB)
    grades = [r[0] for r in conn.execute(
        "SELECT DISTINCT grade FROM questions WHERE grade BETWEEN 1 AND 9 ORDER BY grade")]
    if len(grades) != 9:
        fail("expected grades 1..9, got %r" % (grades,))

    # 1. 每个年级 x 每个池各抽 60 次，验证 SQL 隔离与标签一致性
    total = 0
    for g in grades:
        for pool in range(4):
            where, args, pw, pa = pool_sql(pool, g)
            for _ in range(60):
                bank_subject = draw(conn, where, args, pw, pa)
                if bank_subject is None:
                    fail("no question for grade=%d pool=%d" % (g, pool))
                lab = label_of(bank_subject)
                if lab != pool:
                    fail("pool=%d returned %s (label=%d) at grade=%d; "
                         "subject label would be wrong" % (pool, bank_subject, lab, g))
                total += 1
    print("POOL_ISOLATION_OK grades=9 pools=4 draws=%d" % total)

    # 2. 四槽配比下的题目真实学科分布（不是标签分布）
    r = random.Random(20261105)
    n = 5000
    for g in (3, 9):
        counts = [0, 0, 0, 0]
        for _ in range(n):
            pool = roulette((5, 3, 2, 2), r)
            where, args, pw, pa = pool_sql(pool, g)
            counts[label_of(draw(conn, where, args, pw, pa))] += 1
        pct = [100.0 * c / n for c in counts]
        expect = (500.0 / 12, 300.0 / 12, 200.0 / 12, 200.0 / 12)
        for i in range(4):
            if abs(pct[i] - expect[i]) > 2.5:
                fail("grade=%d subject=%d got %.2f%% expect %.2f%%" % (
                    g, i, pct[i], expect[i]))
        print("DISTRIBUTION_OK grade=%d math/chinese/english/other = "
              "%.2f/%.2f/%.2f/%.2f%% (expect 41.67/25.00/16.67/16.67)"
              % (g, pct[0], pct[1], pct[2], pct[3]))

    # 3. 全学科关闭：第 4 槽为 0，其他学科题必须完全消失
    counts = [0, 0, 0, 0]
    for _ in range(n):
        pool = roulette((5, 3, 2), r)
        where, args, pw, pa = pool_sql(pool, 9)
        counts[label_of(draw(conn, where, args, pw, pa))] += 1
    if counts[3] != 0:
        fail("allSubjects=false still produced %d other-subject questions" % counts[3])
    print("CORE_ONLY_OK other=0 draws=%d" % n)

    verify_fallback(conn)
    print("BANK_DISTRIBUTION_OK")


if __name__ == "__main__":
    main()
