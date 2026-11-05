#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PoHW 内置题库构建器（零第三方依赖，Python 3.8+）。

把 RedSamerai/learning-content 的**全部**知识点纲目导入随 APK 发布的
SQLite 数据库，并为每个知识点生成答案可校验的四选一题。

    python3 tools/quizbank/build_bank.py \
        --lc /path/to/learning-content \
        --out app/assets/quiz.db \
        --seed 20261104 \
        --stats docs/BANK_STATS.md

数据契约（APP 端 QuizDb.java 只读这三张表）：
    knowledge  全量知识点纲目（原始字段完整保留）
    chapters   兼容旧版 App 的纲目视图
    questions  四选一题（grade/subject/text/opt_a..opt_d/answer_index/explain）
    meta       版本与统计

说明：learning-content 仓库只有知识点纲目，不含题目、选项和答案。
本脚本不引用、不打包任何第三方题库的真题文本，所有题目均由本文件
参数化生成，正确答案由数据本身或程序计算确定。
"""
import argparse
import collections
import datetime
import glob
import hashlib
import json
import os
import random
import re
import sqlite3
import sys

CN_NUM = {'一': 1, '二': 2, '三': 3, '四': 4, '五': 5,
          '六': 6, '七': 7, '八': 8, '九': 9}
GRADES = ['幼儿园', '幼小衔接', '一年级上', '一年级下', '二年级上', '二年级下',
          '三年级上', '三年级下', '四年级上', '四年级下', '五年级上', '五年级下',
          '六年级上', '六年级下', '七年级上', '七年级下', '八年级上', '八年级下',
          '九年级上', '九年级下', '九年级全一册']

GROUP = {
    '数学': 'math', '数学认知': 'math',
    '语文': 'chinese', '语言': 'chinese',
    '英语': 'english',
}

# ---------------------------------------------------------------- 纲目读取

def clean(v):
    return re.sub(r'\s+', ' ', str(v or '')).strip()


def parse_grade(label):
    """返回 (数值年级, 学期)。幼儿园/幼小衔接 -> 0。"""
    label = clean(label)
    if label in ('幼儿园', '幼小衔接'):
        return 0, ''
    m = re.match(r'([一二三四五六七八九])年级(上|下|全一册)?', label)
    if not m:
        return 0, ''
    term = m.group(2) or ''
    return CN_NUM[m.group(1)], ('全' if term == '全一册' else term)


def group_of(subject):
    return GROUP.get(subject, 'other')


def iter_source_files(lc):
    """三个来源全量读取：两份总图谱 + output/ 下逐知识点文件。"""
    files = []
    for name in ('knowledge_graph_complete.json', 'knowledge_graph_backup_4375.json'):
        p = os.path.join(lc, name)
        if os.path.isfile(p):
            files.append(p)
    files.extend(sorted(glob.glob(os.path.join(lc, 'output', '*', '*', '*.json'))))
    return files


def load_nodes(lc):
    """合并去重；同一 key 保留信息最全的一份，并记录所有来源。"""
    merged = {}
    files = iter_source_files(lc)
    if not files:
        raise SystemExit('未找到 learning-content 数据：%s' % lc)
    for path in files:
        origin = os.path.relpath(path, lc)
        try:
            with open(path, encoding='utf-8') as f:
                raw = json.load(f)
        except Exception:
            continue
        items = raw if isinstance(raw, list) else [raw]
        for x in items:
            if not isinstance(x, dict):
                continue
            topic = clean(x.get('topic'))
            grade_label = clean(x.get('grade'))
            subject = clean(x.get('subject'))
            if not topic or not grade_label or not subject:
                continue
            chapter = clean(x.get('chapter')) or '综合'
            key = (grade_label, subject, chapter, topic)
            grade, term = parse_grade(grade_label)
            node = {
                'raw_id': clean(x.get('id')),
                'grade': grade,
                'grade_label': grade_label,
                'term': term,
                'subject': subject,
                'subject_group': group_of(subject),
                'chapter': chapter,
                'topic': topic,
                'description': clean(x.get('description')),
                'example': clean(x.get('example')),
                'explanation': clean(x.get('explanation')),
                'key_points': clean(x.get('key_points')),
                'content': clean(x.get('content')),
                'origins': [origin],
            }
            if key not in merged:
                merged[key] = node
                continue
            old = merged[key]
            if origin not in old['origins']:
                old['origins'].append(origin)
            # 逐字段取更完整（更长）的值，保证「全量导入」不丢信息
            for k in ('raw_id', 'description', 'example', 'explanation',
                      'key_points', 'content'):
                if len(node[k]) > len(old[k]):
                    old[k] = node[k]
    nodes = list(merged.values())
    nodes.sort(key=lambda n: (n['grade'], n['grade_label'],
                              n['subject'], n['chapter'], n['topic']))
    return nodes, files


# ---------------------------------------------------------------- 出题工具

def short(s, n=24):
    s = clean(s)
    return s[:n] + ('…' if len(s) > n else '')


def make_q(text, correct, distractors, explain, topic, rng):
    """组装四选一；保证 4 个选项互异、正确答案唯一、解析非空。"""
    c = clean(correct)
    if not c or len(clean(text)) < 3 or not clean(explain):
        return None
    norm = lambda v: re.sub(r'\s+', '', str(v))
    seen = {norm(c)}
    pool = []
    for d in distractors:
        d = clean(d)
        if d and norm(d) not in seen:
            seen.add(norm(d))
            pool.append(d)
        if len(pool) == 3:
            break
    if len(pool) < 3:
        return None
    items = [c] + pool
    rng.shuffle(items)
    return {'topic': short(topic, 20), 'text': clean(text), 'options': items,
            'answerIndex': items.index(c), 'explain': clean(explain)[:90]}


def qhash(q):
    norm = lambda v: re.sub(r'\s+', '', str(v))
    return hashlib.sha1((norm(q['text']) + '|' +
                         '|'.join(norm(o) for o in q['options'])).encode('utf-8')).hexdigest()


# ---------------------------------------------------------------- 纲目题

def knowledge_questions(nodes, rng):
    """每个知识点至少 1 题；三种题型轮换，答案 100% 由纲目数据本身确定。"""
    by_grade = collections.defaultdict(list)
    subjects = sorted({n['subject'] for n in nodes})
    grade_labels = sorted({n['grade_label'] for n in nodes})
    chapters = sorted({n['chapter'] for n in nodes if len(n['chapter']) > 1})
    for n in nodes:
        by_grade[n['grade_label']].append(n)
    out = []
    for i, n in enumerate(nodes):
        locate = '%s / %s / %s / %s' % (
            n['grade_label'], n['subject'], n['chapter'], n['topic'])
        explain = '纲目定位：%s　·　来源：learning-content' % locate
        variant = i % 3
        q = None
        if variant == 0:
            distract = [x['topic'] for x in by_grade[n['grade_label']]
                        if x['subject'] != n['subject'] and x['topic'] != n['topic']]
            rng.shuffle(distract)
            q = make_q('「%s · %s」中，下列哪个是要掌握的知识点？'
                       % (n['grade_label'], n['subject']),
                       short(n['topic']), [short(x) for x in distract],
                       explain, n['topic'], rng)
        elif variant == 1:
            distract = [s for s in subjects if s != n['subject']]
            rng.shuffle(distract)
            q = make_q('知识点「%s」属于哪个学科？' % short(n['topic']),
                       n['subject'], distract, explain, n['topic'], rng)
        else:
            distract = [g for g in grade_labels if g != n['grade_label']]
            rng.shuffle(distract)
            q = make_q('知识点「%s」是哪一个年级段的学习内容？' % short(n['topic']),
                       n['grade_label'], distract, explain, n['topic'], rng)
        if q is None and chapters:
            distract = [c for c in chapters if c != n['chapter']]
            rng.shuffle(distract)
            q = make_q('知识点「%s」出自哪一章节？' % short(n['topic']),
                       short(n['chapter']), [short(c) for c in distract],
                       explain, n['topic'], rng)
        if q is None:
            continue
        q['grade'] = n['grade']
        q['grade_label'] = n['grade_label']
        q['term'] = n['term']
        q['subject'] = n['subject']
        q['subject_group'] = n['subject_group']
        q['chapter'] = n['chapter']
        q['source'] = '纲目题'
        out.append(q)
    return out


# ---------------------------------------------------------------- 三科程序题

POEMS = [
    ('举头望明月', '低头思故乡'), ('春眠不觉晓', '处处闻啼鸟'),
    ('白日依山尽', '黄河入海流'), ('锄禾日当午', '汗滴禾下土'),
    ('两个黄鹂鸣翠柳', '一行白鹭上青天'), ('离离原上草', '一岁一枯荣'),
    ('千山鸟飞绝', '万径人踪灭'), ('床前明月光', '疑是地上霜'),
    ('日照香炉生紫烟', '遥看瀑布挂前川'), ('两岸猿声啼不住', '轻舟已过万重山'),
    ('独在异乡为异客', '每逢佳节倍思亲'), ('谁知盘中餐', '粒粒皆辛苦'),
    ('欲穷千里目', '更上一层楼'), ('不识庐山真面目', '只缘身在此山中'),
]
ANTONYM = [
    ('热闹', '冷清'), ('高兴', '难过'), ('开始', '结束'), ('容易', '困难'),
    ('诚实', '虚伪'), ('勤劳', '懒惰'), ('骄傲', '谦虚'), ('宽阔', '狭窄'),
    ('明亮', '昏暗'), ('增加', '减少'), ('前进', '后退'), ('安全', '危险'),
]
IDIOM = [
    ('守株待兔', '死守经验、妄想不劳而获'), ('亡羊补牢', '出问题后及时补救'),
    ('画蛇添足', '多此一举反而坏事'), ('自相矛盾', '言行前后抵触'),
    ('掩耳盗铃', '自己欺骗自己'), ('井底之蛙', '见识狭小'),
    ('刻舟求剑', '拘泥成法、不知变通'), ('狐假虎威', '借别人势力吓人'),
]
WORDS_EN = [
    ('apple', '苹果'), ('banana', '香蕉'), ('book', '书'), ('cat', '猫'),
    ('dog', '狗'), ('egg', '鸡蛋'), ('fish', '鱼'), ('green', '绿色'),
    ('happy', '高兴的'), ('milk', '牛奶'), ('orange', '橙子'), ('pen', '钢笔'),
    ('red', '红色'), ('school', '学校'), ('teacher', '老师'), ('water', '水'),
    ('blue', '蓝色'), ('desk', '课桌'), ('friend', '朋友'), ('window', '窗户'),
]
EN_SENT = [
    ('She ___ a student.', 'is', ['am', 'are', 'be']),
    ('I ___ from China.', 'am', ['is', 'are', 'be']),
    ('They ___ playing football.', 'are', ['is', 'am', 'be']),
    ('___ is your name?', 'What', ['Where', 'Who', 'When']),
    ('___ do you go to school? By bus.', 'How', ['What', 'Who', 'Where']),
    ('There ___ some milk in the cup.', 'is', ['are', 'am', 'be']),
    ('___ you like apples? Yes, I do.', 'Do', ['Does', 'Are', 'Is']),
]
PRIMES = [2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47]
COMPOSITES = [4, 6, 8, 9, 10, 12, 14, 15, 16, 18, 20, 21, 22, 24, 25, 26, 27]


def num_pool(correct, rng, n=3):
    c = float(correct)
    delta = max(1, int(abs(c) * 0.1) or 1)
    cand = []
    for d in (delta, delta * 2, -delta, -delta * 2, delta * 3, 1, -1, 10, -10):
        v = c + d
        if v >= 0:
            cand.append(int(round(v)) if abs(v - round(v)) < 1e-9 else round(v, 2))
    rng.shuffle(cand)
    out, seen = [], {str(correct)}
    for v in cand:
        if str(v) not in seen:
            seen.add(str(v))
            out.append(v)
        if len(out) == n:
            break
    while len(out) < n:
        v = int(c) + len(out) + 101
        if str(v) not in seen:
            seen.add(str(v))
            out.append(v)
    return out[:n]


def math_question(g, rng):
    kind = rng.randrange(7)
    hi = 20 if g == 1 else (100 if g == 2 else (999 if g == 3 else 9999))
    if kind == 0:
        a, b = rng.randint(1, hi), rng.randint(1, min(hi, 500))
        v = a + b
        return make_q('%d ＋ %d ＝ ?' % (a, b), v, num_pool(v, rng),
                      '%d＋%d＝%d' % (a, b, v), '加减法', rng)
    if kind == 1:
        a, b = sorted((rng.randint(1, hi), rng.randint(1, min(hi, 500))), reverse=True)
        v = a - b
        return make_q('%d － %d ＝ ?' % (a, b), v, num_pool(v, rng),
                      '%d－%d＝%d' % (a, b, v), '加减法', rng)
    if kind == 2:
        lo = 2 if g <= 2 else 11
        a, b = rng.randint(lo, 99), rng.randint(2, 9 if g <= 3 else 29)
        v = a * b
        return make_q('%d × %d ＝ ?' % (a, b), v, num_pool(v, rng),
                      '%d×%d＝%d' % (a, b, v), '乘法', rng)
    if kind == 3:
        b, q = rng.randint(2, 9), rng.randint(3, 30 if g <= 3 else 99)
        v = b * q
        return make_q('%d ÷ %d ＝ ?' % (v, b), q, num_pool(q, rng),
                      '%d÷%d＝%d' % (v, b, q), '除法', rng)
    if kind == 4:
        a, b = rng.randint(3, 25), rng.randint(2, 20)
        v = 2 * (a + b)
        return make_q('长方形长 %d 厘米、宽 %d 厘米，周长是多少厘米？' % (a, b), v,
                      num_pool(v, rng), '周长＝(长＋宽)×2＝%d' % v, '周长与面积', rng)
    if kind == 5:
        label, correct, pool = ('质数', rng.choice(PRIMES), COMPOSITES) \
            if rng.random() < 0.5 else ('合数', rng.choice(COMPOSITES), PRIMES)
        return make_q('下面哪个数是%s？' % label, correct, rng.sample(pool, 3),
                      '%d 是%s' % (correct, label), '质数与合数', rng)
    base = rng.choice([20, 25, 40, 50, 80, 200])
    p = rng.choice([10, 20, 25, 50, 75])
    v = int(base * p / 100)
    return make_q('%d 的 %d%% 是多少？' % (base, p), v, num_pool(v, rng),
                  '%d×%d%%＝%d' % (base, p, v), '百分数', rng)


def chinese_question(idx, rng):
    kind = idx % 4
    if kind == 0:
        a, b = POEMS[idx % len(POEMS)]
        return make_q('「%s」的下一句是？' % a, b,
                      [x[1] for x in POEMS if x[1] != b][:3],
                      '出自古诗，下一句为「%s」' % b, '古诗名句', rng)
    if kind == 1:
        a, b = ANTONYM[idx % len(ANTONYM)]
        return make_q('「%s」的反义词是？' % a, b,
                      [x[1] for x in ANTONYM if x[1] != b][:3],
                      '「%s」与「%s」意思相反' % (a, b), '反义词', rng)
    if kind == 2:
        w, mean = IDIOM[idx % len(IDIOM)]
        return make_q('成语「%s」的意思是？' % w, mean,
                      [x[1] for x in IDIOM if x[1] != mean][:3],
                      '「%s」指%s' % (w, mean), '成语释义', rng)
    a, b = POEMS[(idx + 5) % len(POEMS)]
    return make_q('「%s」的上一句是？' % b, a,
                  [x[0] for x in POEMS if x[0] != a][:3],
                  '上一句为「%s」' % a, '古诗名句', rng)


def english_question(idx, rng):
    kind = idx % 3
    if kind == 0:
        en, zh = WORDS_EN[idx % len(WORDS_EN)]
        return make_q('单词 "%s" 的中文意思是？' % en, zh,
                      [x[1] for x in WORDS_EN if x[1] != zh][:3],
                      '%s ＝ %s' % (en, zh), '词汇', rng)
    if kind == 1:
        stem, correct, wrong = EN_SENT[idx % len(EN_SENT)]
        return make_q(stem, correct, wrong, '正确填「%s」' % correct, '句型与语法', rng)
    en, zh = WORDS_EN[(idx + 7) % len(WORDS_EN)]
    return make_q('「%s」对应的英文单词是？' % zh, en,
                  [x[0] for x in WORDS_EN if x[0] != en][:3],
                  '%s ＝ %s' % (en, zh), '词汇', rng)


def program_questions(per_cell, rng):
    """1-9 年级 × 数/语/英 的真计算 / 词句题，保证现有三科抽题手感不变。"""
    out = []
    for g in range(1, 10):
        for subject, group, fn in (('数学', 'math', math_question),
                                   ('语文', 'chinese', chinese_question),
                                   ('英语', 'english', english_question)):
            made, seen, tries = 0, set(), 0
            while made < per_cell and tries < per_cell * 12:
                tries += 1
                i = tries
                q = fn(g, rng) if subject == '数学' else fn(i + g * 7, rng)
                if q is None:
                    continue
                h = qhash(q)
                if h in seen:
                    continue
                seen.add(h)
                q.update({'grade': g, 'grade_label': '%d年级' % g, 'term': '',
                          'subject': subject, 'subject_group': group,
                          'chapter': '综合练习', 'source': '程序出题'})
                out.append(q)
                made += 1
    return out


# ---------------------------------------------------------------- SQLite 导出

def write_db(dest, nodes, questions, version, files):
    tmp = dest + '.tmp'
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    c = db.cursor()
    c.execute('PRAGMA journal_mode=DELETE')
    c.execute('CREATE TABLE knowledge ('
              'id INTEGER PRIMARY KEY, raw_id TEXT, grade INTEGER, grade_label TEXT, '
              'term TEXT, subject TEXT, subject_group TEXT, chapter TEXT, topic TEXT, '
              'description TEXT, example TEXT, explanation TEXT, key_points TEXT, '
              'content TEXT, origins TEXT, '
              'UNIQUE(grade_label, subject, chapter, topic))')
    c.execute('CREATE INDEX idx_kn_gs ON knowledge(grade, subject)')
    c.execute('CREATE INDEX idx_kn_group ON knowledge(grade, subject_group)')
    c.execute('CREATE TABLE chapters ('
              'id INTEGER PRIMARY KEY, grade INTEGER, term TEXT, subject TEXT, '
              'chapter TEXT, topic TEXT)')
    c.execute('CREATE INDEX idx_ch_gs ON chapters(grade, subject)')
    c.execute('CREATE TABLE questions ('
              'id INTEGER PRIMARY KEY, grade INTEGER NOT NULL, grade_label TEXT, '
              'term TEXT, subject TEXT NOT NULL, subject_group TEXT, chapter TEXT, '
              'topic TEXT, text TEXT NOT NULL, opt_a TEXT, opt_b TEXT, opt_c TEXT, '
              'opt_d TEXT, answer_index INTEGER NOT NULL, explain TEXT, '
              'source TEXT, qhash TEXT)')
    c.execute('CREATE UNIQUE INDEX idx_qhash_grade ON questions(qhash, grade)')
    c.execute('CREATE INDEX idx_gs ON questions(grade, subject)')
    c.execute('CREATE INDEX idx_grade ON questions(grade)')
    c.execute('CREATE INDEX idx_group ON questions(grade, subject_group)')
    c.execute('CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)')

    c.executemany('INSERT INTO knowledge(raw_id,grade,grade_label,term,subject,'
                  'subject_group,chapter,topic,description,example,explanation,'
                  'key_points,content,origins) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                  [(n['raw_id'], n['grade'], n['grade_label'], n['term'],
                    n['subject'], n['subject_group'], n['chapter'], n['topic'],
                    n['description'], n['example'], n['explanation'],
                    n['key_points'], n['content'], '|'.join(n['origins']))
                   for n in nodes])
    c.executemany('INSERT INTO chapters(grade,term,subject,chapter,topic) '
                  'VALUES(?,?,?,?,?)',
                  [(n['grade'], n['term'], n['subject'], n['chapter'], n['topic'])
                   for n in nodes])

    inserted, skipped = 0, 0
    for q in questions:
        o = q['options']
        try:
            c.execute('INSERT INTO questions(grade,grade_label,term,subject,'
                      'subject_group,chapter,topic,text,opt_a,opt_b,opt_c,opt_d,'
                      'answer_index,explain,source,qhash) '
                      'VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                      (q['grade'], q['grade_label'], q['term'], q['subject'],
                       q['subject_group'], q['chapter'], q['topic'], q['text'],
                       o[0], o[1], o[2], o[3], q['answerIndex'], q['explain'],
                       q['source'], qhash(q)))
            inserted += 1
        except sqlite3.IntegrityError:
            skipped += 1
    subjects = sorted({n['subject'] for n in nodes})
    c.executemany('INSERT INTO meta(k,v) VALUES(?,?)', [
        ('version', version),
        ('updatedAt', datetime.datetime.now().strftime('%Y-%m-%d %H:%M')),
        ('schema', '2'),
        ('questions', str(inserted)),
        ('knowledge', str(len(nodes))),
        ('subjects', str(len(subjects))),
        ('grades', str(len({n['grade_label'] for n in nodes}))),
        ('source', 'RedSamerai/learning-content (full catalog)'),
        ('sourceFiles', str(len(files))),
        ('note', '全量纲目导入；题目由 build_bank.py 参数化生成，答案由数据/程序确定；不含任何第三方真题文本。'),
        ('licenseNote', '题库数据来自 learning-content，该仓库未附 LICENSE；仅作个人非商业学习用途，不随本项目 MIT 许可证一并授权。'),
    ])
    db.commit()
    c.execute('VACUUM')
    db.close()
    os.replace(tmp, dest)
    return inserted, skipped, subjects


def verify_db(path):
    db = sqlite3.connect(path)
    c = db.cursor()
    errs = []
    meta = dict(c.execute('SELECT k,v FROM meta'))
    rows = c.execute('SELECT text,opt_a,opt_b,opt_c,opt_d,answer_index,explain '
                     'FROM questions').fetchall()
    for i, r in enumerate(rows):
        text, a, b, d, e, ai, ex = r
        opts = [a, b, d, e]
        if any(o is None or not str(o).strip() for o in opts):
            errs.append('empty option #%d' % i); continue
        if len({re.sub(r'\s+', '', str(o)) for o in opts}) != 4:
            errs.append('duplicate option #%d' % i)
        if not isinstance(ai, int) or not 0 <= ai <= 3:
            errs.append('answer_index #%d' % i)
        if not str(text).strip() or len(str(text).strip()) < 3:
            errs.append('text #%d' % i)
        if not str(ex).strip():
            errs.append('explain #%d' % i)
    if len(rows) != int(meta['questions']):
        errs.append('meta.questions mismatch')
    if c.execute('SELECT COUNT(*) FROM knowledge').fetchone()[0] != int(meta['knowledge']):
        errs.append('meta.knowledge mismatch')
    # 模拟 APP 端随机挑题：每个年级 × 三科都要能取到题
    for g in range(1, 10):
        for s in ('数学', '语文', '英语'):
            n = c.execute('SELECT COUNT(*) FROM questions WHERE grade=? AND subject=?',
                          (g, s)).fetchone()[0]
            if n == 0:
                errs.append('no question G%d/%s' % (g, s))
        if c.execute('SELECT COUNT(*) FROM questions WHERE grade=?', (g,)).fetchone()[0] == 0:
            errs.append('no question G%d (all subjects)' % g)
    db.close()
    return errs, meta


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--lc', required=True, help='learning-content 仓库目录')
    ap.add_argument('--out', default='app/assets/quiz.db')
    ap.add_argument('--seed', type=int, default=20261104)
    ap.add_argument('--per-cell', type=int, default=30, help='每个年级每科程序题数')
    ap.add_argument('--stats', default='', help='可选：写出统计 Markdown')
    a = ap.parse_args()

    rng = random.Random(a.seed)
    nodes, files = load_nodes(a.lc)
    print('SOURCES files=%d unique_knowledge=%d' % (len(files), len(nodes)))
    qs = knowledge_questions(nodes, rng)
    prog = program_questions(a.per_cell, rng)
    qs.extend(prog)
    print('QUESTIONS knowledge=%d program=%d total=%d' %
          (len(qs) - len(prog), len(prog), len(qs)))

    version = 'pohw-%s-full-s%d' % (datetime.datetime.now().strftime('%Y.%m.%d'), a.seed)
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    inserted, skipped, subjects = write_db(a.out, nodes, qs, version, files)
    with open(a.out + '.version', 'w', encoding='utf-8') as f:
        f.write(version + '\n')
    errs, meta = verify_db(a.out)
    print('SQLITE %s bytes=%d questions=%d dedup_skipped=%d' %
          (a.out, os.path.getsize(a.out), inserted, skipped))
    print('VERIFY errors=%d %s' % (len(errs), errs[:5]))
    print('SUBJECTS %s' % ', '.join(subjects))
    print('BUILD_BANK = %s' % ('PASS' if not errs else 'FAIL'))

    by_group = collections.Counter(n['subject_group'] for n in nodes)
    by_grade = collections.Counter(n['grade_label'] for n in nodes)
    if a.stats:
        lines = ['# 内置题库统计', '',
                 '- 数据源：`RedSamerai/learning-content`（全量纲目）',
                 '- 版本：`%s`' % version,
                 '- 源文件：**%d** 个；去重后知识点：**%d** 条' % (len(files), len(nodes)),
                 '- 学科：**%d** 个（%s）' % (len(subjects), '、'.join(subjects)),
                 '- 年级段：**%d** 个' % len(by_grade),
                 '- 题目：**%d** 道（纲目题 %d + 程序出题 %d，去重丢弃 %d）' %
                 (inserted, len(qs) - len(prog), len(prog), skipped),
                 '- 数据库大小：%d 字节' % os.path.getsize(a.out), '',
                 '## 知识点分组', '',
                 '| 分组 | 知识点 |', '| --- | ---: |']
        lines += ['| %s | %d |' % (k, v) for k, v in sorted(by_group.items())]
        lines += ['', '## 各年级段知识点', '',
                  '| 年级段 | 知识点 |', '| --- | ---: |']
        lines += ['| %s | %d |' % (k, v) for k, v in sorted(by_grade.items())]
        lines += ['', '> 题目全部由 `tools/quizbank/build_bank.py` 参数化生成，',
                  '> learning-content 原库只提供知识点纲目，不含题目/选项/答案。']
        os.makedirs(os.path.dirname(os.path.abspath(a.stats)), exist_ok=True)
        with open(a.stats, 'w', encoding='utf-8') as f:
            f.write('\n'.join(lines) + '\n')
    sys.exit(0 if not errs else 1)


if __name__ == '__main__':
    main()
