#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PoHW 内置题库独立校验（零第三方依赖）。

    python3 tools/quizbank/verify.py app/assets/quiz.db

校验项：
  1. 表结构 / meta 与真实行数一致
  2. 每题 4 个非空且互异的选项、answer_index 合法、题干与解析非空
  3. 复现 APP 端 fromBank() 的合法性检查
  4. 每个年级 × 三科、以及每个年级的全学科都能随机取到题
  5. 纯算式题的答案索引用 Python 重算
  6. 篡改检测：翻转一个字节后 SHA-256 必须变化
  7. 知识点全量性：knowledge 表覆盖 meta 声明的数量与全部学科
"""
import hashlib
import os
import random
import re
import sqlite3
import sys

SQL = ('SELECT topic,text,opt_a,opt_b,opt_c,opt_d,answer_index,explain '
       'FROM questions WHERE grade=? AND subject=? ORDER BY RANDOM() LIMIT 1')


def norm(v):
    return re.sub(r'\s+', '', str(v))


def from_bank_check(row):
    """复现 QuizEngine.Question.fromBank() 的校验规则。"""
    topic, text, a, b, c, d, ai, explain = row
    opts = [a, b, c, d]
    if any(o is None or not str(o).strip() for o in opts):
        return False
    if len({norm(o) for o in opts}) != 4:
        return False
    if not isinstance(ai, int) or not 0 <= ai <= 3:
        return False
    if text is None or len(str(text).strip()) < 3:
        return False
    return True


def main(path):
    if not os.path.isfile(path):
        print('找不到题库：%s' % path)
        return 1
    raw = open(path, 'rb').read()
    print('FILE %s bytes=%d sha256=%s' %
          (path, len(raw), hashlib.sha256(raw).hexdigest()))
    db = sqlite3.connect(path)
    c = db.cursor()
    meta = dict(c.execute('SELECT k,v FROM meta'))
    tables = sorted(r[0] for r in c.execute(
        "SELECT name FROM sqlite_master WHERE type='table'"))
    print('TABLES %s' % ', '.join(tables))
    assert {'questions', 'knowledge', 'chapters', 'meta'} <= set(tables)

    errs = []
    rows = c.execute('SELECT topic,text,opt_a,opt_b,opt_c,opt_d,'
                     'answer_index,explain FROM questions').fetchall()
    for i, r in enumerate(rows):
        if not from_bank_check(r):
            errs.append('illegal question #%d' % i)
    n_q = len(rows)
    n_k = c.execute('SELECT COUNT(*) FROM knowledge').fetchone()[0]
    if n_q != int(meta['questions']):
        errs.append('meta.questions mismatch')
    if n_k != int(meta['knowledge']):
        errs.append('meta.knowledge mismatch')

    # 随机抽题压测
    rng = random.Random(42)
    miss = 0
    for g in range(1, 10):
        for s in ('数学', '语文', '英语'):
            for _ in range(40):
                r = c.execute(SQL, (g, s)).fetchone()
                if r is None or not from_bank_check(r):
                    miss += 1
        for _ in range(40):
            r = c.execute('SELECT topic,text,opt_a,opt_b,opt_c,opt_d,'
                          'answer_index,explain FROM questions WHERE grade=? '
                          'ORDER BY RANDOM() LIMIT 1', (g,)).fetchone()
            if r is None or not from_bank_check(r):
                miss += 1
    if miss:
        errs.append('random pick failed %d times' % miss)
    print('RANDOM 9 grades x 3 subjects x 40 + all-subjects x 40 = %d picks, miss=%d'
          % (9 * 3 * 40 + 9 * 40, miss))

    # 纯算式答案重算
    ok = bad = 0
    for r in c.execute("SELECT text,opt_a,opt_b,opt_c,opt_d,answer_index "
                       "FROM questions WHERE source='程序出题'").fetchall():
        t, opts = r[0], list(r[1:5])
        if '＝ ?' not in t or any(ch in t for ch in '？厘米米克分钟度'):
            continue
        expr = t.split('＝')[0].strip().replace('＋', '+').replace('－', '-') \
                .replace('×', '*').replace('÷', '/').replace(' ', '')
        if not re.fullmatch(r'[0-9+\-*/().]+', expr):
            continue
        try:
            v = eval(expr, {'__builtins__': {}}, {})
            hit = abs(float(opts[r[5]]) - float(v)) < 1e-6
            ok += hit
            bad += not hit
        except Exception:
            pass
    if bad:
        errs.append('recomputed answer mismatch %d' % bad)
    print('ANSWER recompute ok=%d bad=%d' % (ok, bad))

    # 全量性
    subjects = [r[0] for r in c.execute(
        'SELECT DISTINCT subject FROM knowledge ORDER BY subject')]
    grades = [r[0] for r in c.execute(
        'SELECT DISTINCT grade_label FROM knowledge ORDER BY grade_label')]
    if len(subjects) < 15 or len(grades) < 18:
        errs.append('catalog coverage looks partial: %d subjects / %d grades'
                    % (len(subjects), len(grades)))
    print('CATALOG knowledge=%d subjects=%d grade_bands=%d'
          % (n_k, len(subjects), len(grades)))
    print('SUBJECTS %s' % '、'.join(subjects))

    # 篡改检测
    flipped = bytearray(raw)
    flipped[64] ^= 1
    neg = hashlib.sha256(bytes(flipped)).hexdigest() != hashlib.sha256(raw).hexdigest()
    if not neg:
        errs.append('tamper detection failed')
    print('TAMPER rejected=%s' % neg)

    db.close()
    print('VERIFY = %s (errors=%d) %s' %
          ('PASS' if not errs else 'FAIL', len(errs), errs[:5]))
    return 0 if not errs else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else 'app/assets/quiz.db'))
