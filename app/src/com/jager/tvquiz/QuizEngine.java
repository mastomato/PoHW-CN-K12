package com.jager.tvquiz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * 分年级 / 分学科出题引擎。
 * 数学按课标知识点参数化生成（无限题）；语文英语走内置分年级词表。
 * 若注入了在线题库题目，则按科目优先抽题库题，其余回退到生成器，保证离线永远能出题。
 */
public final class QuizEngine {

    public static final int MATH = 0, CHINESE = 1, ENGLISH = 2;

    private static final int BANK_RATIO = 60;

    public static final class Question {
        public int subject;
        public String topic, text, explain, source;
        public String[] options;
        public int answerIndex;

        Question(int subject, String topic, String text, String correct,
                 List<String> distractors, String explain, String source, Random r) {
            this.subject = subject;
            this.topic = topic;
            this.text = text;
            this.explain = explain;
            this.source = source;
            List<String> all = new ArrayList<String>();
            all.add(correct);
            for (String d : distractors) {
                if (!all.contains(d) && all.size() < 4) all.add(d);
            }
            int k = 1;
            while (all.size() < 4 && k < 300) {
                String extra;
                try {
                    int base = Integer.parseInt(correct);
                    int v = base + ((k % 2 == 0) ? -k : k);
                    if (v < 0) { k++; continue; }
                    extra = Integer.toString(v);
                } catch (NumberFormatException e) {
                    extra = "以上都不对 " + k;
                }
                if (!all.contains(extra)) all.add(extra);
                k++;
            }
            Collections.shuffle(all, r);
            this.options = all.toArray(new String[0]);
            this.answerIndex = all.indexOf(correct);
        }

        /** 解析在线题库中的一道四选一题；任何字段不合法都直接丢弃。 */
        public static Question fromBank(int subject, String topic, String text,
                                        String[] opts, int ai, String explain) {
            if (opts == null || opts.length != 4 || ai < 0 || ai > 3) return null;
            if (text == null || text.trim().length() < 3) return null;
            Set<String> seen = new HashSet<String>();
            for (String o : opts) {
                if (o == null || o.trim().isEmpty() || !seen.add(o)) return null;
            }
            Question q = new Question(subject, topic, text, opts[ai],
                    Arrays.asList(opts[0], opts[1], opts[2], opts[3]),
                    explain == null ? "" : explain, "bank", new Random());
            // 固定为服务端给出的选项顺序，不重排
            q.options = opts;
            q.answerIndex = ai;
            return q;
        }

        /** 稳定去重键：长度前缀编码全部语义字段，抗碰撞且答案感知。 */
        public String key() {
            StringBuilder sb = new StringBuilder();
            appendPart(sb, Integer.toString(subject));
            appendPart(sb, Integer.toString(answerIndex));
            appendPart(sb, text);
            if (options != null) {
                for (String o : options) appendPart(sb, o);
            }
            return Hashes.sha256Hex(sb.toString());
        }

        private static void appendPart(StringBuilder sb, String v) {
            String s = v == null ? "" : v;
            sb.append(s.length()).append(':').append(s).append('|');
        }
    }

    private final Random r = new Random();
    private final int grade;
    private final SubjectWeights weights;
    private final List<Question> bankMath = new ArrayList<Question>();
    private final List<Question> bankChinese = new ArrayList<Question>();
    private final List<Question> bankEnglish = new ArrayList<Question>();
    private final List<Question> bankOther = new ArrayList<Question>();
    private int lastMathType = -1;
    private QuizDb db;
    private boolean allSubjects = false;
    /** 学期过滤值（"上"/"下"）；null = 不限学期。只作用于内置题库，生成器本身不分学期。 */
    private String term;

    /** 兼容旧调用：第 4 槽为 0（不出其他学科）。 */
    public QuizEngine(int grade, String edition, int mathW, int chineseW, int englishW) {
        this(grade, edition, mathW, chineseW, englishW, 0);
    }

    /** 四槽配比：数 / 语 / 英 / 其他学科。 */
    public QuizEngine(int grade, String edition, int mathW, int chineseW,
                      int englishW, int otherW) {
        this.grade = grade < 1 ? 1 : (grade > 9 ? 9 : grade);
        weights = new SubjectWeights(mathW, chineseW, englishW, otherW);
        syncOtherAvailable();
    }

    /** 内置题库确实有非三科题、或存在其他学科在线题包时，OTHER 才参与轮盘赌。 */
    private void syncOtherAvailable() {
        weights.setOtherAvailable((db != null && db.hasOtherQuestions())
                || !bankOther.isEmpty());
    }

    public void setBank(List<Question> qs) {
        bankMath.clear(); bankChinese.clear(); bankEnglish.clear(); bankOther.clear();
        if (qs != null) {
            for (Question q : qs) {
                if (q == null) continue;
                if (q.subject == MATH) bankMath.add(q);
                else if (q.subject == CHINESE) bankChinese.add(q);
                else if (q.subject == ENGLISH) bankEnglish.add(q);
                else if (q.subject == Subjects.OTHER) bankOther.add(q);
            }
        }
        syncOtherAvailable();
    }

    public int bankSize() { return bankMath.size() + bankChinese.size() + bankEnglish.size() + bankOther.size(); }

    /** 注入内置 SQLite 题库；为空时完全走原有生成器链路。 */
    public void setDb(QuizDb db) { this.db = db; syncOtherAvailable(); }

    public boolean hasDb() { return db != null; }

    /** true: allow the other-subject pool; the ratio still comes from SubjectWeights. */
    public void setAllSubjects(boolean v) {
        this.allSubjects = v;
        weights.setAllSubjects(v);
    }
    public boolean isAllSubjects() { return allSubjects; }

    /** 设置学期过滤；null 或空串表示不限学期。 */
    public void setTerm(String v) {
        this.term = (v == null || v.isEmpty()) ? null : v;
    }

    private List<Question> bankFor(int s) {
        if (s == MATH) return bankMath;
        if (s == CHINESE) return bankChinese;
        if (s == ENGLISH) return bankEnglish;
        if (s == Subjects.OTHER) return bankOther;
        return bankMath;
    }

    public Question next() {
        int s = pickSubject();
        List<Question> list = bankFor(s);
        boolean hasDb = db != null;
        boolean hasBank = list != null && !list.isEmpty();
        if (hasBank && (!hasDb || r.nextInt(100) < BANK_RATIO)) {
            return list.get(r.nextInt(list.size()));
        }
        if (hasDb) {
            Question q = db.random(grade, s, allSubjects, term);
            if (q != null) return q;
        }
        if (hasBank) return list.get(r.nextInt(list.size()));
        if (s == CHINESE || s == ENGLISH) return wordQuestion(s);
        return mathByGrade();
    }

    /** 配比真正生效：四槽轮盘赌决定题目来自哪个学科池。 */
    private int pickSubject() {
        return weights.pick(r);
    }

    private Question wordQuestion(int subject) {
        WordBank.Item[] arr = subject == CHINESE ? WordBank.chinese(grade) : WordBank.english(grade);
        WordBank.Item it = arr[r.nextInt(arr.length)];
        return new Question(subject,
                subject == CHINESE ? "语文 · 字词句" : "英语 · 词汇语法",
                it.prompt, it.correct, Arrays.asList(it.wrong), it.explain, "gen", r);
    }

    // ---------- 通用构造辅助 ----------

    private int ri(int lo, int hi) { return lo + r.nextInt(hi - lo + 1); }

    private static String f1(double v) { return String.format(Locale.US, "%.1f", v); }

    private Question intQ(String topic, String text, int correct, int[] cand, String explain) {
        List<String> ds = new ArrayList<String>();
        for (int c : cand) {
            if (c == correct || c < 0) continue;
            String s = Integer.toString(c);
            if (!ds.contains(s) && ds.size() < 3) ds.add(s);
        }
        return new Question(MATH, topic, text, Integer.toString(correct), ds, explain, "gen", r);
    }

    private Question strQ(String topic, String text, String correct, String[] cand, String explain) {
        List<String> ds = new ArrayList<String>();
        for (String c : cand) {
            if (c.equals(correct)) continue;
            if (!ds.contains(c) && ds.size() < 3) ds.add(c);
        }
        return new Question(MATH, topic, text, correct, ds, explain, "gen", r);
    }

    // ---------- 按年级分档 ----------

    private Question mathByGrade() {
        switch (grade) {
            case 1: return mathG1();
            case 2: return mathG2();
            case 3: return mathG3();
            case 4: return mathG4();
            case 5: return mathG5();
            default: return mathG6();
        }
    }

    /** 一年级：20 以内加减、数序、比大小、认识图形 */
    private Question mathG1() {
        switch (r.nextInt(5)) {
            case 0: {
                int a = ri(2, 12), b = ri(1, 18 - a), c = a + b;
                return intQ("20 以内加法", a + " + " + b + " = ?", c,
                        new int[]{c + 1, c - 1, c + 2, a - b},
                        "把 " + b + " 接着数到 " + a + " 后面：" + a + " + " + b + " = " + c);
            }
            case 1: {
                int a = ri(6, 20), b = ri(1, a - 1), c = a - b;
                return intQ("20 以内减法", a + " − " + b + " = ?", c,
                        new int[]{c + 1, c - 1, a + b, c + 2},
                        "从 " + a + " 里去掉 " + b + "，还剩 " + c);
            }
            case 2: {
                int base = ri(3, 15);
                int[] v = {base, base + 2, base + 5, base + 8};
                int max = v[3];
                return strQ("比大小", "下面哪个数最大？", Integer.toString(max),
                        new String[]{String.valueOf(v[0]), String.valueOf(v[1]), String.valueOf(v[2])},
                        "按顺序数一数，" + max + " 排在最后，所以最大");
            }
            case 3: {
                int a = ri(3, 18), c = a + 1;
                return intQ("数的顺序", a + " 的后面一个数是几？", c,
                        new int[]{a - 1, a, c + 1, c + 2},
                        "往后数一个数：" + a + " → " + c);
            }
            default: {
                if (r.nextBoolean()) {
                    return intQ("认识图形", "正方形有几条边？", 4, new int[]{3, 5, 6, 2},
                            "正方形有 4 条一样长的边");
                }
                return intQ("认识图形", "三角形有几条边？", 3, new int[]{4, 5, 2, 6},
                        "三角形有 3 条边、3 个角");
            }
        }
    }

    /** 二年级：100 以内加减、表内乘除、长度单位 */
    private Question mathG2() {
        switch (r.nextInt(6)) {
            case 0: {
                int a = ri(23, 79), b = ri(11, 99 - a > 0 ? 99 - a : 1), c = a + b;
                return intQ("100 以内加法", a + " + " + b + " = ?", c,
                        new int[]{c + 1, c - 1, c + 10, a - b},
                        "相同数位对齐，满十进一：" + a + " + " + b + " = " + c);
            }
            case 1: {
                int a = ri(40, 99), b = ri(11, a - 1), c = a - b;
                return intQ("100 以内减法", a + " − " + b + " = ?", c,
                        new int[]{c + 1, c - 1, c + 10, a + b},
                        "不够减时向十位借一当十：" + a + " − " + b + " = " + c);
            }
            case 2: {
                int a = ri(2, 9), b = ri(2, 9), c = a * b;
                return intQ("表内乘法", a + " × " + b + " = ?", c,
                        new int[]{c + a, c - a, a + b, c + 1},
                        "用乘法口诀：" + a + " × " + b + " = " + c);
            }
            case 3: {
                int b = ri(2, 9), q = ri(2, 9), a = b * q;
                return intQ("表内除法", a + " ÷ " + b + " = ?", q,
                        new int[]{q + 1, q - 1, b, a - b},
                        "想口诀：" + b + " × " + q + " = " + a + "，所以商是 " + q);
            }
            case 4: {
                int m = ri(2, 9), c = m * 100;
                return intQ("长度单位", m + " 米 = (  ) 厘米", c,
                        new int[]{m * 10, m * 1000, c + 10, m + 100},
                        "1 米 = 100 厘米，" + m + " × 100 = " + c);
            }
            default: {
                int a = ri(20, 60), b = ri(5, 20), c = ri(3, 15), ans = a + b - c;
                return intQ("加减混合", a + " + " + b + " − " + c + " = ?", ans,
                        new int[]{a + b, a + b + c, ans + c, ans - c},
                        "从左往右算：" + a + " + " + b + " = " + (a + b) + "，再减 " + c + " = " + ans);
            }
        }
    }

    /** 三年级：v1 已验证的 10 类题型 */
    private Question mathG3() {
        int type = r.nextInt(10);
        if (type == lastMathType) type = (type + 1 + r.nextInt(9)) % 10;
        lastMathType = type;
        switch (type) {
            case 0: return addSub3();
            case 1: return multiplyByOne();
            case 2: return divideByOne();
            case 3: return remainder();
            case 4: return multiple();
            case 5: return fractionAdd();
            case 6: return perimeter();
            case 7: return unitConvert();
            case 8: return twoStep();
            default: return area();
        }
    }

    private Question addSub3() {
        int a = ri(120, 780), b = ri(110, 210);
        if (r.nextBoolean()) {
            int c = a + b;
            return intQ("万以内加减法", a + " + " + b + " = ?", c,
                    new int[]{c + 1, c - 1, c + 10, c - 10, c + 100, a - b},
                    "相同数位对齐，满十进一：" + a + " + " + b + " = " + c);
        }
        if (b > a) { int t = a; a = b; b = t; }
        int c = a - b;
        return intQ("万以内加减法", a + " − " + b + " = ?", c,
                new int[]{c + 1, c - 1, c + 10, c - 10, a + b, c + 100},
                "不够减时向前一位借一当十：" + a + " − " + b + " = " + c);
    }

    private Question multiplyByOne() {
        int a = ri(13, 240), b = ri(2, 9), c = a * b;
        return intQ("多位数乘一位数", a + " × " + b + " = ?", c,
                new int[]{c + a, c - a, c + b, c - b, c + 10, c - 10, a + b},
                "从个位乘起，满几十进几：" + a + " × " + b + " = " + c);
    }

    private Question divideByOne() {
        int b = ri(2, 9), q = ri(12, 99), a = b * q;
        return intQ("一位数除法", a + " ÷ " + b + " = ?", q,
                new int[]{q + 1, q - 1, q + b, q - b, q + 10, b, a - b},
                "想乘法算除法：" + b + " × " + q + " = " + a + "，所以商是 " + q);
    }

    private Question remainder() {
        int b = ri(3, 9), q = ri(5, 40), rem = ri(1, b - 1);
        int a = b * q + rem;
        int[] cand = new int[b + 3];
        for (int i = 0; i < b; i++) cand[i] = i;
        cand[b] = q; cand[b + 1] = b; cand[b + 2] = rem + b;
        return intQ("有余数的除法", a + " ÷ " + b + " = " + q + " …… 余数是几？", rem,
                cand, "余数一定比除数小：" + b + " × " + q + " + " + rem + " = " + a + "，余数是 " + rem);
    }

    private Question multiple() {
        int a = ri(3, 12), b = ri(2, 9), c = a * b;
        return intQ("倍的认识", a + " 的 " + b + " 倍是多少？", c,
                new int[]{a + b, c + a, c - a, c + b, c - b, c + 10},
                "求一个数的几倍用乘法：" + a + " × " + b + " = " + c);
    }

    private Question fractionAdd() {
        int d = ri(6, 12), n1 = ri(1, d - 2), n2 = ri(1, d - n1 - 1);
        String correct = (n1 + n2) + "/" + d;
        String text = n1 + "/" + d + " + " + n2 + "/" + d + " = ?";
        return strQ("分数的初步认识", text, correct,
                new String[]{(n1 + n2 + 1) + "/" + d, (n1 + n2) + "/" + (d * 2),
                        (n1 * n2) + "/" + d, "(" + n1 + "+" + n2 + ")/" + (d * 2)},
                "分母不变、分子相加：" + n1 + " + " + n2 + " = " + (n1 + n2) + "，结果是 " + correct);
    }

    private Question perimeter() {
        if (r.nextBoolean()) {
            int l = ri(4, 18), w = ri(2, 12), c = 2 * (l + w);
            return intQ("长方形周长", "长方形长 " + l + " 厘米、宽 " + w + " 厘米，周长是多少厘米？", c,
                    new int[]{l + w, l * w, 2 * l + w, l + 2 * w, c + 2, c - 2},
                    "周长 =（长 + 宽）× 2 =（" + l + " + " + w + "）× 2 = " + c + " 厘米");
        }
        int a = ri(3, 20), c = 4 * a;
        return intQ("正方形周长", "正方形边长 " + a + " 厘米，周长是多少厘米？", c,
                new int[]{a * a, a * 2, a * 3, c + a, c - a, c + 4},
                "正方形周长 = 边长 × 4 = " + a + " × 4 = " + c + " 厘米");
    }

    private Question unitConvert() {
        switch (r.nextInt(6)) {
            case 0: { int m = ri(2, 9), c = m * 100;
                return intQ("长度单位", m + " 米 = (  ) 厘米", c,
                        new int[]{m * 10, m * 1000, c + 10, c - 10, m + 100},
                        "1 米 = 100 厘米，" + m + " × 100 = " + c); }
            case 1: { int kg = ri(2, 9), c = kg * 1000;
                return intQ("质量单位", kg + " 千克 = (  ) 克", c,
                        new int[]{kg * 100, kg * 10, c + 100, c - 100, kg + 1000},
                        "1 千克 = 1000 克，" + kg + " × 1000 = " + c); }
            case 2: { int h = ri(2, 6), c = h * 60;
                return intQ("时、分、秒", h + " 时 = (  ) 分", c,
                        new int[]{h * 100, h * 24, c + h, c - h, c + 60},
                        "1 时 = 60 分，" + h + " × 60 = " + c); }
            case 3: { int m = ri(2, 9), c = m * 60;
                return intQ("时、分、秒", m + " 分 = (  ) 秒", c,
                        new int[]{m * 100, m * 24, c + m, c - m, c + 60},
                        "1 分 = 60 秒，" + m + " × 60 = " + c); }
            case 4: { int cm = ri(10, 80), c = 100 - cm;
                return intQ("长度单位", "1 米 − " + cm + " 厘米 = (  ) 厘米", c,
                        new int[]{100 + cm, cm, 100, c + 10, c - 10},
                        "先统一单位：1 米 = 100 厘米，100 − " + cm + " = " + c); }
            default: { int y = ri(2, 9), j = ri(1, 9), c = y * 10 + j;
                return intQ("元、角、分", y + " 元 " + j + " 角 = (  ) 角", c,
                        new int[]{y + j, y * j, c + 10, c - 10, y * 10 - j},
                        "1 元 = 10 角，" + y + " 元 = " + (y * 10) + " 角，再加 " + j + " 角 = " + c + " 角"); }
        }
    }

    private Question twoStep() {
        if (r.nextBoolean()) {
            int a = ri(40, 120), b = ri(10, 35), c = ri(5, 30), ans = a - b + c;
            return intQ("两步应用题", "书架上有 " + a + " 本书，借走 " + b + " 本，又买来 " + c + " 本，现在有多少本？", ans,
                    new int[]{a - b, a + c, a - b - c, ans + b, ans - c, a + b + c},
                    "先算借走后：" + a + " − " + b + " = " + (a - b) + "；再加新买的：" + (a - b) + " + " + c + " = " + ans);
        }
        int per = ri(3, 12), boxes = ri(3, 9), total = per * boxes;
        int give = ri(2, Math.min(15, total - 1)), ans = total - give;
        return intQ("两步应用题", "每盒有 " + per + " 支铅笔，买了 " + boxes + " 盒，送给同学 " + give + " 支，还剩多少支？", ans,
                new int[]{total, per + boxes, ans + give, ans - give, total + give, per * (boxes - 1)},
                "先算一共：" + per + " × " + boxes + " = " + total + "；再减送出的：" + total + " − " + give + " = " + ans);
    }

    private Question area() {
        int l = ri(4, 18), w = ri(2, 12), c = l * w;
        return intQ("面积", "长方形长 " + l + " 厘米、宽 " + w + " 厘米，面积是多少平方厘米？", c,
                new int[]{2 * (l + w), l + w, c + l, c - l, c + w, 2 * l * w},
                "面积 = 长 × 宽 = " + l + " × " + w + " = " + c + " 平方厘米（别和周长混淆）");
    }

    /** 四年级：三位数乘两位数、两位数除法、大数、运算律、角、小数 */
    private Question mathG4() {
        switch (r.nextInt(6)) {
            case 0: {
                int a = ri(102, 499), b = ri(11, 29), c = a * b;
                return intQ("三位数乘两位数", a + " × " + b + " = ?", c,
                        new int[]{c + a, c - a, c + b, c - b, a + b},
                        "先用个位乘、再用十位乘，最后相加：" + a + " × " + b + " = " + c);
            }
            case 1: {
                int b = ri(11, 39), q = ri(11, 60), a = b * q;
                return intQ("除数是两位数的除法", a + " ÷ " + b + " = ?", q,
                        new int[]{q + 1, q - 1, q + b, b, a - b},
                        "把 " + b + " 看作整十数试商：" + b + " × " + q + " = " + a);
            }
            case 2: {
                int w = ri(3, 25), c = w * 10000;
                return intQ("大数的认识", w + " 万 = (  )", c,
                        new int[]{w * 1000, w * 100, c + w, w * 100000},
                        "1 万 = 10000，" + w + " × 10000 = " + c);
            }
            case 3: {
                int a = ri(12, 45), c = 100 * a;
                return intQ("运算律", "25 × " + a + " × 4 = ?（想想怎样算更快）", c,
                        new int[]{25 * a, 4 * a, c + 25, c - 25},
                        "用乘法交换律先算 25 × 4 = 100，再乘 " + a + " 得 " + c);
            }
            case 4: {
                switch (r.nextInt(3)) {
                    case 0: return intQ("角的度量", "一个直角是多少度？", 90,
                            new int[]{180, 360, 45, 60}, "直角 = 90°");
                    case 1: return intQ("角的度量", "一个平角是多少度？", 180,
                            new int[]{90, 360, 120, 270}, "平角 = 180° = 2 个直角");
                    default: return intQ("角的度量", "一个周角是多少度？", 360,
                            new int[]{90, 180, 270, 120}, "周角 = 360° = 2 个平角");
                }
            }
            default: {
                int a = ri(11, 89), b = ri(11, 89);
                String correct = f1((a + b) / 10.0);
                return strQ("小数的加减法",
                        f1(a / 10.0) + " + " + f1(b / 10.0) + " = ?", correct,
                        new String[]{f1((a + b + 10) / 10.0), f1((a + b - 10) / 10.0),
                                f1((a + b + 1) / 10.0)},
                        "小数点对齐再相加：" + f1(a / 10.0) + " + " + f1(b / 10.0) + " = " + correct);
            }
        }
    }

    /** 五年级：小数乘法、简易方程、长方体、分数加减、公倍数 */
    private Question mathG5() {
        switch (r.nextInt(6)) {
            case 0: {
                int a = ri(11, 45), b = ri(2, 9);
                String correct = f1(a * b / 10.0);
                return strQ("小数乘法", f1(a / 10.0) + " × " + b + " = ?", correct,
                        new String[]{f1((a * b + 10) / 10.0), f1((a * b - 10) / 10.0),
                                f1(a * b / 100.0)},
                        "先按整数算 " + a + " × " + b + " = " + (a * b) + "，再点上一位小数：" + correct);
            }
            case 1: {
                int x = ri(12, 90), a = ri(5, 40), b = x + a;
                return intQ("简易方程", "x + " + a + " = " + b + "，x = ?", x,
                        new int[]{b, a, b + a, x + 1},
                        "等式两边同时减 " + a + "：x = " + b + " − " + a + " = " + x);
            }
            case 2: {
                int x = ri(3, 25), a = ri(2, 9), b = a * x;
                return intQ("简易方程", a + "x = " + b + "，x = ?", x,
                        new int[]{b, a, b / (a + 1) + 1, x + 1},
                        "等式两边同时除以 " + a + "：x = " + b + " ÷ " + a + " = " + x);
            }
            case 3: {
                int l = ri(3, 12), w = ri(2, 9), h = ri(2, 8), c = l * w * h;
                return intQ("长方体体积", "长方体长 " + l + "、宽 " + w + "、高 " + h + " 厘米，体积是多少立方厘米？", c,
                        new int[]{2 * (l * w + l * h + w * h), l + w + h, c + l, l * w},
                        "体积 = 长 × 宽 × 高 = " + l + " × " + w + " × " + h + " = " + c);
            }
            case 4: {
                switch (r.nextInt(4)) {
                    case 0: return strQ("异分母分数加减", "1/2 + 1/4 = ?", "3/4",
                            new String[]{"1/3", "2/4", "2/6"}, "通分：1/2 = 2/4，2/4 + 1/4 = 3/4");
                    case 1: return strQ("异分母分数加减", "1/3 + 1/6 = ?", "1/2",
                            new String[]{"2/9", "2/6", "1/9"}, "通分：1/3 = 2/6，2/6 + 1/6 = 3/6 = 1/2");
                    case 2: return strQ("异分母分数加减", "1/2 + 1/6 = ?", "2/3",
                            new String[]{"1/8", "2/8", "1/3"}, "通分：1/2 = 3/6，3/6 + 1/6 = 4/6 = 2/3");
                    default: return strQ("异分母分数加减", "3/4 − 1/4 = ?", "1/2",
                            new String[]{"1/8", "3/8", "1/3"}, "分母不变：3/4 − 1/4 = 2/4 = 1/2");
                }
            }
            default: {
                int[][] t = {{4, 6, 12}, {6, 8, 24}, {3, 5, 15}, {8, 12, 24}, {9, 6, 18}};
                int[] p = t[r.nextInt(t.length)];
                return intQ("因数与倍数", p[0] + " 和 " + p[1] + " 的最小公倍数是多少？", p[2],
                        new int[]{p[0] * p[1], p[0] + p[1], Math.max(p[0], p[1]), p[2] + 1},
                        p[0] + " 和 " + p[1] + " 的最小公倍数是 " + p[2]);
            }
        }
    }

    /** 六年级：分数乘法、百分数、比、圆、负数 */
    private Question mathG6() {
        switch (r.nextInt(6)) {
            case 0: {
                int n = ri(2, 9), d = ri(2, 9);
                return intQ("分数乘法", n + "/" + d + " × " + d + " = ?", n,
                        new int[]{n + d, n * d, n + 1, d},
                        "分数乘整数：" + n + "/" + d + " × " + d + " = " + n);
            }
            case 1: {
                int base = ri(2, 20) * 10;
                int[] ps = {10, 20, 25, 50};
                int p = ps[r.nextInt(ps.length)];
                int c = base * p / 100;
                return intQ("百分数", base + " 的 " + p + "% 是多少？", c,
                        new int[]{base - c, base + c, c + p, c * 2},
                        base + " × " + p + "% = " + base + " × " + p + "/100 = " + c);
            }
            case 2: {
                int k = ri(3, 20), total = k * 5, more = k * 3;
                return intQ("比和比例", "把 " + total + " 个球按 2 : 3 分成两份，较多的一份有多少个？", more,
                        new int[]{k * 2, total, k, total - k},
                        "总份数 2 + 3 = 5，每份 " + total + " ÷ 5 = " + k + "，较多的占 3 份 = " + more);
            }
            case 3: {
                int rr = ri(2, 10);
                return strQ("圆的周长", "半径 " + rr + " 厘米的圆，周长是多少厘米？（π 取 3.14）",
                        f1(2 * 3.14 * rr),
                        new String[]{f1(3.14 * rr), f1(9.42 * rr), f1(12.56 * rr)},
                        "C = 2πr = 2 × 3.14 × " + rr + " = " + f1(2 * 3.14 * rr) + " 厘米");
            }
            case 4: {
                int rr = ri(2, 9);
                return strQ("圆的面积", "半径 " + rr + " 厘米的圆，面积是多少平方厘米？（π 取 3.14）",
                        f1(3.14 * rr * rr),
                        new String[]{f1(3.14 * rr * (rr + 1)), f1(3.14 * rr * (rr - 1)), f1(6.28 * rr * rr)},
                        "S = πr² = 3.14 × " + rr + " × " + rr + " = " + f1(3.14 * rr * rr) + " 平方厘米");
            }
            default: {
                int a = ri(-9, 4), d = ri(1, 12), b = a + d;
                return intQ("负数", "温度从 " + a + "℃ 升到 " + b + "℃，升高了多少摄氏度？", d,
                        new int[]{d + 1, d - 1, b, a, b - a + 1},
                        b + " − (" + a + ") = " + d + " ℃");
            }
        }
    }
}
