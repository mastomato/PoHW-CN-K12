package com.jager.tvquiz;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 可选在线题库仓库：默认完全离线，只有家长自行配置地址后才会联网。
 *
 * 数据契约（App 只认这一层，任何商业题库平台都在服务端适配成这个格式）：
 *   index.json  { schema:1, version, updatedAt, packs:[{id,grade,edition,subject,url,sha256,count}] }
 *   pack json   { schema:1, id, grade, edition, subject, questions:[{topic,text,options[4],answerIndex,explain}] }
 *
 * 设计约束：APK 内不放任何第三方题库的 access-key / secret-key，签名只在服务端代理完成。
 * 网络不可用或题库为空时，出题引擎自动回退到本地参数化生成器，保证离线可用。
 */
public final class BankRepository {

    public interface Callback { void onDone(SyncResult result); }

    public static final class SyncResult {
        public boolean ok;
        public String message, version;
        public int packs, questions, failed;
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BankRepository() {}

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "banks");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date());
    }

    // ---------- 内置离线题库（默认题源） ----------

    /**
     * 标记内置离线题库为当前题源。
     * APK 内不预置任何第三方题库包：内置题库由 tools/quizbank 从开源
     * learning-content 纲目生成，随 assets/quiz.db 发布，完全离线。
     * 在线题库源是可选能力，只有家长在设置里自行填写地址后才会联网；
     * 已配置在线源时不覆盖其状态，旧版「内置示例题库」状态在此迁移。
     */
    public static void installBuiltin(Context c, SettingsStore st) {
        String src = st.bankSource();
        boolean legacy = "未初始化".equals(src) || src.contains("示例")
                || src.contains("火花") || src.contains("本地缓存");
        if (!src.startsWith("内置离线") && !legacy) return;

        QuizDb db = QuizDb.open(c);
        if (db == null) {
            st.setBankStatus("内置离线 SQLite", "-", "-", System.currentTimeMillis(),
                    0, 0, "内置题库加载失败，已回退到参数化生成器");
            return;
        }
        st.setBankStatus("内置离线 SQLite", db.version(), "-", System.currentTimeMillis(),
                1, db.count(),
                "完全离线：" + db.knowledgeCount() + " 条纲目 / " + db.count()
                        + " 题 / " + db.subjectCount() + " 学科；在线源需自行配置");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    // ---------- 读取缓存 ----------

    public static int countQuestions(Context c) {
        int total = 0;
        File[] fs = dir(c).listFiles();
        if (fs == null) return 0;
        for (File f : fs) {
            if (!isPack(f)) continue;
            try {
                JSONObject o = new JSONObject(new String(readAll(new java.io.FileInputStream(f)), UTF8));
                JSONArray qs = o.optJSONArray("questions");
                if (qs != null) total += qs.length();
            } catch (Exception ignored) {
            }
        }
        return total;
    }

    /** v5 兼容重载：四个学科共用一个教材版本。 */
    public static List<QuizEngine.Question> load(Context c, int grade, String edition) {
        return load(c, grade, new String[]{edition, edition, edition, edition});
    }

    /**
     * 按 年级 + 分学科教材版本 筛选在线缓存题包。
     * editions 下标与 Subjects.MATH/CHINESE/ENGLISH/OTHER 对应；
     * 题包的 edition 为 "*" 或 "通用(课标)" 时视为通用包，不受版本限制。
     */
    public static List<QuizEngine.Question> load(Context c, int grade, String[] editions) {
        List<QuizEngine.Question> out = new ArrayList<QuizEngine.Question>();
        File[] fs = dir(c).listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (!isPack(f)) continue;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                JSONObject o = new JSONObject(new String(readAll(fis), UTF8));
                if (o.optInt("grade", 0) != grade) continue;
                String ed = o.optString("edition", "*");
                int subject = subjectOf(o.optString("subject", "数学"));
                String want = editionFor(editions, subject);
                if (!"*".equals(ed) && !"通用(课标)".equals(ed)
                        && !ed.equals(want)) continue;
                JSONArray qs = o.optJSONArray("questions");
                if (qs == null) continue;
                for (int i = 0; i < qs.length(); i++) {
                    JSONObject q = qs.optJSONObject(i);
                    if (q == null) continue;
                    JSONArray oa = q.optJSONArray("options");
                    if (oa == null || oa.length() != 4) continue;
                    String[] opts = new String[4];
                    for (int k = 0; k < 4; k++) opts[k] = oa.optString(k, "");
                    QuizEngine.Question qq = QuizEngine.Question.fromBank(subject,
                            q.optString("topic", "同步题库"), q.optString("text", ""),
                            opts, q.optInt("answerIndex", -1), q.optString("explain", ""));
                    if (qq != null) out.add(qq);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static boolean isPack(File f) {
        return f.isFile() && f.getName().startsWith("pack_") && f.getName().endsWith(".json");
    }

    private static int subjectOf(String s) {
        if ("语文".equals(s)) return QuizEngine.CHINESE;
        if ("英语".equals(s)) return QuizEngine.ENGLISH;
        if ("数学".equals(s)) return QuizEngine.MATH;
        return Subjects.OTHER;
    }

    private static String editionFor(String[] eds, int subject) {
        if (eds == null || eds.length == 0) return "通用(课标)";
        int s = Subjects.norm(subject);
        if (s >= eds.length) s = Subjects.MATH;
        String v = eds[s];
        return v == null ? "通用(课标)" : v;
    }

    // ---------- 在线同步 ----------

    public static void sync(final Context app, final SettingsStore st,
                            final String baseUrl, final boolean force, final Callback cb) {
        POOL.execute(new Runnable() {
            @Override public void run() {
                final SyncResult res = doSync(app, st, baseUrl, force);
                MAIN.post(new Runnable() {
                    @Override public void run() { if (cb != null) cb.onDone(res); }
                });
            }
        });
    }

    private static SyncResult doSync(Context app, SettingsStore st, String baseUrl, boolean force) {
        SyncResult res = new SyncResult();
        long checkedAt = System.currentTimeMillis();
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (url.isEmpty()) {
            res.ok = true;
            res.version = st.bankVersion();
            res.packs = st.bankPacks();
            res.questions = countQuestions(app);
            res.message = "未配置在线题库源，当前完全离线使用内置 SQLite 题库";
            st.setBankStatus("本地缓存", st.bankVersion(), st.bankUpdated(), checkedAt,
                    res.packs, res.questions, res.message);
            return res;
        }

        try {
            byte[] idxBytes = httpGet(join(url, "index.json"));
            JSONObject idx = new JSONObject(new String(idxBytes, UTF8));
            if (idx.optInt("schema", 0) != 1) throw new IOException("index.json schema 不是 1");
            String version = idx.getString("version");
            String updatedAt = idx.optString("updatedAt", "-");
            JSONArray packs = idx.optJSONArray("packs");
            if (packs == null) throw new IOException("index.json 缺少 packs");

            String localVersion = readLocalVersion(app);
            if (!force && version.equals(localVersion)) {
                res.ok = true;
                res.version = version;
                res.packs = packs.length();
                res.questions = countQuestions(app);
                res.message = "已是最新版本 " + version; 
                st.setBankStatus("在线题库 · " + shortHost(url), version, updatedAt, checkedAt,
                        res.packs, res.questions, res.message);
                return res;
            }

            File d = dir(app);
            int okCount = 0, failCount = 0, questions = 0;
            for (int i = 0; i < packs.length(); i++) {
                JSONObject p = packs.optJSONObject(i);
                if (p == null) { failCount++; continue; }
                String id = p.optString("id", "");
                String rel = p.optString("url", "");
                if (id.isEmpty() || rel.isEmpty()) { failCount++; continue; }
                try {
                    byte[] data = httpGet(join(url, rel));
                    String expect = p.optString("sha256", "");
                    if (expect.length() != 64) throw new IOException("题目包缺少 sha256");
                    if (!expect.equalsIgnoreCase(Hashes.sha256Hex(data))) {
                        throw new IOException("SHA-256 校验不通过");
                    }
                    JSONObject pack = new JSONObject(new String(data, UTF8));
                    JSONArray qs = pack.optJSONArray("questions");
                    if (qs == null) throw new IOException("题目包缺少 questions");
                    questions += qs.length();
                    String safe = id.replaceAll("[^A-Za-z0-9_-]", "_");
                    writeAtomic(new File(d, "pack_" + safe + ".json"), data);
                    okCount++;
                } catch (Exception e) {
                    failCount++;
                }
            }

            if (okCount == 0) throw new IOException("所有题目包下载或校验失败（" + failCount + " 个）");

            // 只有至少一个包成功，才把新 index 落盘；失败的包继续沿用旧缓存
            writeAtomic(new File(d, "index.json"), idxBytes);
            res.ok = true;
            res.version = version;
            res.packs = okCount;
            res.questions = countQuestions(app);
            res.failed = failCount;
            res.message = "更新到 " + version + "，成功 " + okCount + " 个包"
                    + (failCount > 0 ? "，失败 " + failCount + " 个（沿用旧缓存）" : "");
            st.setBankStatus("在线题库 · " + shortHost(url), version, updatedAt, checkedAt,
                    res.packs, res.questions, res.message);
        } catch (Exception e) {
            res.ok = false;
            res.version = st.bankVersion();
            res.packs = st.bankPacks();
            res.questions = countQuestions(app);
            res.message = "同步失败（已保留旧缓存）：" + e.getClass().getSimpleName()
                    + ": " + e.getMessage();
            st.setBankStatus(st.bankSource(), st.bankVersion(), st.bankUpdated(), checkedAt,
                    res.packs, res.questions, res.message);
        }
        return res;
    }

    private static String readLocalVersion(Context c) {
        File f = new File(dir(c), "index.json");
        if (!f.exists()) return "";
        try {
            JSONObject o = new JSONObject(new String(readAll(new java.io.FileInputStream(f)), UTF8));
            return o.optString("version", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String shortHost(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "自定义";
        }
    }

    private static String join(String base, String rel) throws IOException {
        if (rel.startsWith("http://") || rel.startsWith("https://")) {
            String bh = hostOf(base), rh = hostOf(rel);
            if (bh.isEmpty() || !bh.equals(rh)) throw new IOException("题目包跨主机地址被拒绝");
            return rel;
        }
        String b = base.endsWith("/") ? base : base + "/";
        return b + (rel.startsWith("/") ? rel.substring(1) : rel);
    }

    private static String hostOf(String u) {
        try { return new URL(u).getHost(); } catch (Exception e) { return ""; }
    }

    private static byte[] httpGet(String url) throws IOException {
        if (!url.startsWith("http://") && !url.startsWith("https://"))
            throw new IOException("仅支持 http/https 题库源");
        final String originalHost = hostOf(url);
        // 不交给 HttpURLConnection 自动跳转：它可能跨到任意主机，绕过 join 的跨主机校验。
        // 改为手动跟随，最多 3 跳，且每一跳都必须留在原始主机。
        for (int redirects = 0; redirects <= 3; redirects++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            InputStream in = null;
            try {
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setRequestMethod("GET");
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    if (redirects == 3) throw new IOException("重定向次数超过 3 次");
                    String loc = c.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) throw new IOException("重定向缺少 Location");
                    // join() 对绝对地址复用跨主机校验；相对地址保持在当前（=原始）主机。
                    String next = join(url, loc);
                    if (originalHost.isEmpty() || !originalHost.equals(hostOf(next)))
                        throw new IOException("重定向跨主机地址被拒绝");
                    url = next;
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                in = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("文件超过 4MB");
                    bo.write(buf, 0, n);
                }
                return bo.toByteArray();
            } finally {
                // 成功、>4MB 溢出、以及所有错误路径都要释放流与连接。
                if (in != null) { try { in.close(); } catch (Exception ignored) { } }
                c.disconnect();
            }
        }
        throw new IOException("重定向次数超过 3 次");
    }

    private static void writeAtomic(File dest, byte[] data) throws IOException {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        boolean ok = false;
        try {
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                fo.write(data);
                fo.flush();
                fo.getFD().sync();
            }
            if (dest.exists() && !dest.delete()) throw new IOException("无法替换旧缓存");
            if (!tmp.renameTo(dest)) {
                try (FileOutputStream out = new FileOutputStream(dest)) {
                    out.write(data);
                    out.flush();
                }
                tmp.delete();
            }
            ok = true;
        } finally {
            if (!ok) tmp.delete();
        }
    }

}
