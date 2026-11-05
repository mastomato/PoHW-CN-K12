package com.jager.tvquiz;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地错题库：独立可写 SQLite（不改动只读的 assets/quiz.db）。
 * 同一道题按 qkey 去重累加错误次数；写入走单线程后台队列，不挡主线程。
 */
public final class WrongBook extends SQLiteOpenHelper {

    private static final String TAG = "PoHW.WrongBook";
    private static final String DB = "pohw_wrong.db";
    private static final int VERSION = 2;
    /** 列表只展示最近 500 条，标题里的总数必须另用 COUNT(*) 取真实值。 */
    public static final int LIST_LIMIT = 500;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    /** onUpgrade 只补列、不删表：错题库是用户累积数据，DROP 属于不可逆破坏。 */
    private static final String[][] COLUMNS = {
            {"grade", "INTEGER"}, {"grade_label", "TEXT"}, {"subject", "TEXT"},
            {"edition", "TEXT"}, {"topic", "TEXT"}, {"text", "TEXT"},
            {"opt_a", "TEXT"}, {"opt_b", "TEXT"}, {"opt_c", "TEXT"}, {"opt_d", "TEXT"},
            {"answer_index", "INTEGER"}, {"explain", "TEXT"}, {"source", "TEXT"},
            {"wrong_count", "INTEGER NOT NULL DEFAULT 1"}, {"last_pick", "TEXT"},
            {"last_wrong_at", "INTEGER"}, {"created_at", "INTEGER"}
    };

    private static volatile WrongBook instance = null;
    private static volatile int countCache = -1;
    private static volatile String latestSummary = "";

    public static final class Entry {
        public long id;
        public String key = "", gradeLabel = "", subject = "", edition = "", topic = "";
        public String text = "", explain = "", source = "", lastPick = "";
        public String[] options = new String[]{"", "", "", ""};
        public int grade, answerIndex, wrongCount = 1;
        public long lastWrongAt, createdAt;
    }

    private WrongBook(Context c) {
        super(c.getApplicationContext(), DB, null, VERSION);
    }

    /** 只构造 Helper、不碰磁盘：count()/list() 一律走 refreshAsync() 后台线程。 */
    public static synchronized WrongBook open(Context c) {
        if (instance == null) {
            try {
                instance = new WrongBook(c.getApplicationContext());
            } catch (Exception e) {
                Log.e(TAG, "open wrong book failed", e);
            }
        }
        return instance;
    }

    /** 后台刷新缓存计数与最近错题摘要；回调在主线程执行，entries 为最近 LIST_LIMIT 条。 */
    public interface RefreshCallback {
        void onLoaded(List<Entry> entries, int total);
    }

    public static void refreshAsync(final Context c) {
        refreshAsync(c, null);
    }

    public static void refreshAsync(final Context c, final RefreshCallback cb) {
        POOL.execute(new Runnable() {
            @Override public void run() {
                WrongBook w = open(c);
                List<Entry> snapshot;
                int total;
                if (w == null) {
                    snapshot = new ArrayList<Entry>();
                    total = 0;
                } else {
                    snapshot = w.list();   // 顺带刷新 latestSummary
                    total = w.count();     // COUNT(*) 真实总数，不受 LIST_LIMIT 影响
                }
                if (cb != null) {
                    final List<Entry> f = snapshot;
                    final int t = total;
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() { cb.onLoaded(f, t); }
                    });
                }
            }
        });
    }

    private static void mutateAsync(final Runnable work, final Runnable onDone) {
        POOL.execute(new Runnable() {
            @Override public void run() {
                try { work.run(); } catch (Exception e) { Log.e(TAG, "mutate failed", e); }
                if (onDone != null) {
                    new Handler(Looper.getMainLooper()).post(onDone);
                }
            }
        });
    }

    public static void deleteAsync(final Context c, final long id, final Runnable onDone) {
        mutateAsync(new Runnable() {
            @Override public void run() {
                WrongBook w = open(c);
                if (w != null) w.delete(id);
            }
        }, onDone);
    }

    public static void clearAsync(final Context c, final Runnable onDone) {
        mutateAsync(new Runnable() {
            @Override public void run() {
                WrongBook w = open(c);
                if (w != null) w.clear();
            }
        }, onDone);
    }

    /** 控制台每秒刷新用缓存计数，避免轮询 SQLite。 */
    public static int cachedCount() {
        return countCache < 0 ? 0 : countCache;
    }

    /** 控制台状态区直接显示最近一道错题的摘要。 */
    public static String latestSummary() {
        return latestSummary == null ? "" : latestSummary;
    }

    private static String summarize(Entry e) {
        if (e == null || e.text.isEmpty()) return "";
        String t = e.text.replace('\n', ' ').trim();
        if (t.length() > 34) t = t.substring(0, 34) + "…";
        return e.subject + " · " + t;
    }

    public static void recordAsync(final Context c, final Entry e) {
        POOL.execute(new Runnable() {
            @Override public void run() {
                WrongBook w = open(c);
                if (w != null) w.record(e);
            }
        });
    }

    /** 在主线程把题面快照成纯数据对象，后台线程不持有 Question 引用。 */
    public static Entry of(QuizEngine.Question q, int grade, String gradeLabel,
                           String edition, int picked) {
        Entry e = new Entry();
        if (q == null) return e;
        e.key = q.key();
        e.grade = grade;
        e.gradeLabel = gradeLabel == null ? "" : gradeLabel;
        e.subject = Subjects.name(q.subject);
        e.edition = edition == null ? "" : edition;
        e.topic = q.topic == null ? "" : q.topic;
        e.text = q.text == null ? "" : q.text;
        e.explain = q.explain == null ? "" : q.explain;
        e.source = q.source == null ? "" : q.source;
        e.answerIndex = q.answerIndex;
        if (q.options != null) {
            for (int i = 0; i < 4 && i < q.options.length; i++) {
                e.options[i] = q.options[i] == null ? "" : q.options[i];
            }
        }
        if (picked >= 0 && picked < 4) e.lastPick = e.options[picked];
        long now = System.currentTimeMillis();
        e.lastWrongAt = now;
        e.createdAt = now;
        return e;
    }

    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE IF NOT EXISTS wrong ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "qkey TEXT NOT NULL UNIQUE,"
                + "grade INTEGER, grade_label TEXT, subject TEXT, edition TEXT, topic TEXT,"
                + "text TEXT NOT NULL, opt_a TEXT, opt_b TEXT, opt_c TEXT, opt_d TEXT,"
                + "answer_index INTEGER, explain TEXT, source TEXT,"
                + "wrong_count INTEGER NOT NULL DEFAULT 1,"
                + "last_pick TEXT, last_wrong_at INTEGER, created_at INTEGER)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_wrong_time ON wrong(last_wrong_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase d, int oldV, int newV) {
        // 绝不删表：只幂等补表、补索引、补缺失列，用户累积的错题必须完整保留。
        onCreate(d);
        ensureColumns(d);
    }

    private static void ensureColumns(SQLiteDatabase d) {
        java.util.Set<String> names = new java.util.HashSet<String>();
        Cursor c = d.rawQuery("PRAGMA table_info(wrong)", null);
        try {
            int idx = c.getColumnIndex("name");
            while (c.moveToNext()) {
                String n = c.getString(idx);
                if (n != null) names.add(n.toLowerCase(java.util.Locale.US));
            }
        } finally {
            c.close();
        }
        for (String[] col : COLUMNS) {
            if (!names.contains(col[0].toLowerCase(java.util.Locale.US))) {
                d.execSQL("ALTER TABLE wrong ADD COLUMN " + col[0] + " " + col[1]);
            }
        }
    }

    public synchronized int count() {
        try {
            Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM wrong", null);
            int n = 0;
            try { if (c.moveToFirst()) n = c.getInt(0); } finally { c.close(); }
            countCache = n;
            return n;
        } catch (Exception e) {
            Log.e(TAG, "count failed", e);
            return countCache < 0 ? 0 : countCache;
        }
    }

    public synchronized void record(Entry e) {
        if (e == null || e.key == null || e.key.isEmpty()) return;
        boolean ok = false;
        try {
            SQLiteDatabase d = getWritableDatabase();
            long now = System.currentTimeMillis();
            ContentValues v = new ContentValues();
            v.put("grade", e.grade);
            v.put("grade_label", e.gradeLabel);
            v.put("subject", e.subject);
            v.put("edition", e.edition);
            v.put("topic", e.topic);
            v.put("text", e.text);
            v.put("opt_a", e.options[0]);
            v.put("opt_b", e.options[1]);
            v.put("opt_c", e.options[2]);
            v.put("opt_d", e.options[3]);
            v.put("answer_index", e.answerIndex);
            v.put("explain", e.explain);
            v.put("source", e.source);
            v.put("last_pick", e.lastPick);
            v.put("last_wrong_at", now);

            d.beginTransaction();
            try {
                // 读 + 写必须同事务，否则并发 record 会覆盖彼此的 wrong_count。
                Cursor c = d.rawQuery("SELECT wrong_count FROM wrong WHERE qkey=?",
                        new String[]{e.key});
                boolean exists;
                try { exists = c.moveToFirst(); } finally { c.close(); }
                if (!exists) {
                    v.put("qkey", e.key);
                    v.put("wrong_count", 1);
                    v.put("created_at", now);
                    if (d.insert("wrong", null, v) < 0) {
                        throw new IllegalStateException("insert wrong returned -1");
                    }
                } else {
                    if (d.update("wrong", v, "qkey=?", new String[]{e.key}) < 1) {
                        throw new IllegalStateException("update wrong affected 0 rows");
                    }
                    d.execSQL("UPDATE wrong SET wrong_count = wrong_count + 1 WHERE qkey=?",
                            new Object[]{e.key});
                }
                d.setTransactionSuccessful();
                ok = true;
            } finally {
                d.endTransaction();
            }
        } catch (Exception ex) {
            Log.e(TAG, "record failed", ex);
        }
        // 写失败绝不推进摘要/计数，否则控制台会显示一条并不存在的错题。
        if (!ok) return;
        latestSummary = summarize(e);
        countCache = count();
    }

    public synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<Entry>();
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT * FROM wrong ORDER BY last_wrong_at DESC LIMIT " + LIST_LIMIT, null);
            try {
                while (c.moveToNext()) out.add(read(c));
            } finally {
                c.close();
            }
        } catch (Exception ex) {
            Log.e(TAG, "list failed", ex);
        }
        latestSummary = out.isEmpty() ? "" : summarize(out.get(0));
        return out;
    }

    public synchronized void delete(long id) {
        try {
            getWritableDatabase().delete("wrong", "id=?", new String[]{Long.toString(id)});
        } catch (Exception ex) {
            Log.e(TAG, "delete failed", ex);
        }
        list();   // 删除的可能正是最近一道，摘要要跟着刷新
        countCache = count();
    }

    public synchronized void clear() {
        try {
            getWritableDatabase().delete("wrong", null, null);
        } catch (Exception ex) {
            Log.e(TAG, "clear failed", ex);
        }
        latestSummary = "";
        countCache = count();
    }

    private static Entry read(Cursor c) {
        Entry e = new Entry();
        e.id = lng(c, "id");
        e.key = str(c, "qkey");
        e.grade = num(c, "grade");
        e.gradeLabel = str(c, "grade_label");
        e.subject = str(c, "subject");
        e.edition = str(c, "edition");
        e.topic = str(c, "topic");
        e.text = str(c, "text");
        e.options[0] = str(c, "opt_a");
        e.options[1] = str(c, "opt_b");
        e.options[2] = str(c, "opt_c");
        e.options[3] = str(c, "opt_d");
        e.answerIndex = num(c, "answer_index");
        e.explain = str(c, "explain");
        e.source = str(c, "source");
        e.lastPick = str(c, "last_pick");
        e.wrongCount = num(c, "wrong_count");
        e.lastWrongAt = lng(c, "last_wrong_at");
        e.createdAt = lng(c, "created_at");
        return e;
    }

    private static String str(Cursor c, String k) {
        int i = c.getColumnIndex(k);
        return (i < 0 || c.isNull(i)) ? "" : c.getString(i);
    }

    private static int num(Cursor c, String k) {
        int i = c.getColumnIndex(k);
        return i < 0 ? 0 : c.getInt(i);
    }

    private static long lng(Cursor c, String k) {
        int i = c.getColumnIndex(k);
        return i < 0 ? 0L : c.getLong(i);
    }
}
