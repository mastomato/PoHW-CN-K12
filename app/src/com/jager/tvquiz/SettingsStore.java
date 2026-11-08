package com.jager.tvquiz;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** 家长设置、答题统计、题库状态与密码哈希的持久化。 */
public final class SettingsStore implements PinGate.Store {

    public static final String PREF = "tvquiz_v2";
    /** 教材版本：数学/语文/英语/其他学科各自独立选择。 */
    public static final String[] EDITIONS = {"通用(课标)", "人教版", "部编版", "北师大版", "苏教版", "外研版", "译林版", "沪教版"};
    public static final int MIN_GRADE = 1;
    public static final int MAX_GRADE = 9;
    /** 学期：0=全学年，1=上册，2=下册。题库 term 列按此过滤，空值与「全」类题目始终参与。 */
    public static final String[] TERMS = {"全学年（上下册）", "上册", "下册"};

    public static final int[] INTERVALS = {15, 30, 60, 300, 600, 1200, 1800};
    public static final String[] INTERVAL_LABELS = {"15 秒(演示)", "30 秒(演示)", "1 分钟", "5 分钟", "10 分钟", "20 分钟", "30 分钟"};
    public static final int[] ROUND_COUNTS = {1, 3, 5, 10, 20};
    public static final int[] SYNC_HOURS = {1, 6, 12, 24};
    public static final String[] SYNC_LABELS = {"每 1 小时", "每 6 小时", "每 12 小时", "每天"};

    private final SharedPreferences sp;

    public SettingsStore(Context c) {
        sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    public int grade() { return clamp(sp.getInt("grade", 3), MIN_GRADE, MAX_GRADE); }
    // key settings are written synchronously: apply() can be lost if the process is killed
    public void grade(int v) {
        sp.edit().putInt("grade", clamp(v, MIN_GRADE, MAX_GRADE)).commit();
    }

    /** unified grade label: 1-6 primary, 7-9 junior high. */
    public String gradeLabel() {
        int g = grade();
        return g <= 6 ? ("小学 " + g + " 年级") : ("初中 " + (g - 6) + " 年级");
    }

    // ---------- 学期（上册 / 下册） ----------

    public int termIndex() { return clamp(sp.getInt("term", 0), 0, TERMS.length - 1); }
    public void termIndex(int v) {
        sp.edit().putInt("term", clamp(v, 0, TERMS.length - 1)).commit();
    }
    public String termLabel() { return TERMS[termIndex()]; }

    /** 传给题库过滤的 term 值；null 表示不限学期。 */
    public String termFilter() {
        int i = termIndex();
        return i == 0 ? null : (i == 1 ? "上" : "下");
    }

    /** false = three core subjects (default); true = all subjects of that grade. */
    public boolean allSubjects() { return sp.getBoolean("allSubjects", false); }
    public void allSubjects(boolean v) {
        sp.edit().putBoolean("allSubjects", v).commit();
    }

    // ---------- 分学科教材版本 ----------

    /**
     * 兼容 v5 的单一 edition 键：把它当作四个学科的默认值，
     * 一旦家长单独改过某学科，就以该学科自己的键为准。
     */
    public int editionIndexFor(int subject) {
        int s = Subjects.norm(subject);
        int legacy = clamp(sp.getInt("edition", 0), 0, EDITIONS.length - 1);
        return clamp(sp.getInt(Subjects.editionKey(s), legacy), 0, EDITIONS.length - 1);
    }

    public void setEditionIndexFor(int subject, int v) {
        sp.edit().putInt(Subjects.editionKey(Subjects.norm(subject)),
                clamp(v, 0, EDITIONS.length - 1)).commit();
    }

    public String editionFor(int subject) {
        return EDITIONS[editionIndexFor(subject)];
    }

    /** v5 兼容：单一版本等价于数学学科的版本。 */
    public int editionIndex() { return editionIndexFor(Subjects.MATH); }
    public void editionIndex(int v) { setEditionIndexFor(Subjects.MATH, v); }
    public String edition() { return editionFor(Subjects.MATH); }

    // ---------- 开机自启 / 启动即守护 ----------

    /** 是否响应开机广播。默认关闭：是否随系统开机拉起，必须由家长显式选择并放行厂商自启动权限。 */
    public boolean bootAutostart() { return sp.getBoolean("bootAutostart", false); }
    public void bootAutostart(boolean v) {
        sp.edit().putBoolean("bootAutostart", v).commit();
    }

    /**
     * 「启动即守护」默认开启：APP 冷启动（含开机广播链路）即进入守护。
     * SharedPreferences 键沿用 autoGuardOnBoot，保证覆盖安装不丢家长旧设置。
     */
    public boolean autoGuardOnBoot() { return sp.getBoolean("autoGuardOnBoot", true); }
    public void autoGuardOnBoot(boolean v) {
        sp.edit().putBoolean("autoGuardOnBoot", v).commit();
    }

    // ---------- 退出门禁：必须答对几题才能返回（默认 5） ----------

    /** 家长可配：本次弹题会话内需答对的题数，默认 5，范围 1..10。 */
    public int requiredCorrect() {
        return ExitPolicy.normalizeRequired(
                sp.getInt("requiredCorrect", ExitPolicy.DEFAULT_REQUIRED_CORRECT));
    }
    public void requiredCorrect(int v) {
        sp.edit().putInt("requiredCorrect", ExitPolicy.normalizeRequired(v)).commit();
    }

    // ---------- 答题时暂停播放（两项默认开启，家长可分别关闭） ----------

    public boolean pauseOnQuiz() { return sp.getBoolean("pauseOnQuiz", true); }
    public void pauseOnQuiz(boolean v) {
        sp.edit().putBoolean("pauseOnQuiz", v).commit();
    }

    public boolean resumePlayback() { return sp.getBoolean("resumePlayback", true); }
    public void resumePlayback(boolean v) {
        sp.edit().putBoolean("resumePlayback", v).commit();
    }

    // ---------- 频率 / 配比 / 在线源 ----------

    public int intervalSec() { return sp.getInt("interval", 30); }
    public void intervalSec(int v) { sp.edit().putInt("interval", v).commit(); }
    public int intervalIndex() {
        for (int i = 0; i < INTERVALS.length; i++) if (INTERVALS[i] == intervalSec()) return i;
        return 1;
    }

    /** 每轮展示的题数；默认 10 题，与知行答题 TV 一致。 */
    public int roundCount() {
        int value = sp.getInt("roundCount", 10);
        for (int count : ROUND_COUNTS) if (count == value) return value;
        return 10;
    }
    public int roundCountIndex() {
        for (int i = 0; i < ROUND_COUNTS.length; i++)
            if (ROUND_COUNTS[i] == roundCount()) return i;
        return 3;
    }
    public void roundCount(int value) {
        boolean allowed = false;
        for (int count : ROUND_COUNTS) if (count == value) allowed = true;
        sp.edit().putInt("roundCount", allowed ? value : 10).commit();
    }

    public int syncIndex() { return clamp(sp.getInt("syncIdx", 1), 0, SYNC_HOURS.length - 1); }
    public void syncIndex(int v) { sp.edit().putInt("syncIdx", clamp(v, 0, SYNC_HOURS.length - 1)).apply(); }
    public long syncIntervalMs() { return SYNC_HOURS[syncIndex()] * 3600L * 1000L; }

    /** 四科配比默认 5:3:2:2；第 4 槽仅在「出题范围 = 全学科」时参与轮盘赌。 */
    public int weight(int subject) {
        int s = Subjects.norm(subject);
        int def = s == Subjects.MATH ? 5 : (s == Subjects.CHINESE ? 3 : 2);
        return clamp(sp.getInt("w" + s, def), 0, SubjectWeights.MAX_WEIGHT);
    }
    public void weight(int subject, int v) {
        int s = Subjects.norm(subject);
        sp.edit().putInt("w" + s, clamp(v, 0, SubjectWeights.MAX_WEIGHT)).apply();
    }

    public String bankUrl() { return sp.getString("bankUrl", ""); }
    public void bankUrl(String v) { sp.edit().putString("bankUrl", v == null ? "" : v.trim()).apply(); }

    public boolean guardEnabled() { return sp.getBoolean("guard", false); }
    public void guardEnabled(boolean v) {
        sp.edit().putBoolean("guard", v).commit();
    }

    public int questioned() { return sp.getInt("questioned", 0); }
    public int correct() { return sp.getInt("correct", 0); }
    public void addResult(boolean ok) {
        sp.edit().putInt("questioned", questioned() + 1)
                .putInt("correct", correct() + (ok ? 1 : 0)).apply();
    }
    /** 出题时计数；答对时单独累加正确数，答错不跳过因此不会重复出题。 */
    public void bumpQuestioned() { sp.edit().putInt("questioned", questioned() + 1).apply(); }
    public void bumpCorrect() { sp.edit().putInt("correct", correct() + 1).apply(); }
    public void resetStats() { sp.edit().putInt("questioned", 0).putInt("correct", 0).apply(); }

    @Override public boolean hasPin() {
        String h = sp.getString("pinHash", "");
        return h != null && !h.isEmpty();
    }

    @Override public boolean checkPin(String pin) {
        String hash = sp.getString("pinHash", "");
        String salt = sp.getString("pinSalt", "");
        if (hash == null || hash.isEmpty() || pin == null) return false;
        int iter = sp.getInt("pinIter", 0);
        if (iter <= 0) {
            // v4.x legacy format: SHA-256(salt:pin). Re-setting the PIN migrates it.
            String calc = sha256Hex(salt + ":" + pin);
            return !calc.isEmpty() && MessageDigest.isEqual(hash.getBytes(), calc.getBytes());
        }
        byte[] derived = derive(pin, unhex(salt), iter);
        byte[] stored = unhex(hash);
        return derived != null && stored != null
                && MessageDigest.isEqual(stored, derived);
    }

    /**
     * 同步落盘（commit）并回读校验。
     * 老版本用 apply() 且不校验，进程被回收时存在「提示设置成功但密码没保存」的窗口。
     */
    @Override public boolean setPin(String pin) {
        if (pin == null || pin.length() < 4) return false;
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] derived = derive(pin, salt, PBKDF2_ITER);
        if (derived == null) return false;
        boolean ok = sp.edit().putString("pinSalt", hex(salt))
                .putString("pinHash", hex(derived))
                .putInt("pinIter", PBKDF2_ITER).commit();
        return ok && hasPin() && checkPin(pin);
    }

    public boolean changePin(String oldPin, String newPin) {
        if (!checkPin(oldPin)) return false;
        return setPin(newPin);
    }

    // ---------- 一次性 STOP 令牌 ----------

    private static final String KEY_STOP_TOKEN = "stop_token";
    private static final String KEY_STOP_TOKEN_AT = "stop_token_at";
    private static final long STOP_TOKEN_TTL_MS = 60000L;

    /**
     * 家长密码验证成功后由本进程签发；用 commit() 同步落盘，
     * 避免 apply() 异步写入在服务消费令牌前被进程回收而丢失。
     */
    public String issueStopToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        String token = hex(b);
        boolean ok = sp.edit().putString(KEY_STOP_TOKEN, token)
                .putLong(KEY_STOP_TOKEN_AT, System.currentTimeMillis()).commit();
        return ok ? token : "";
    }

    /**
     * 校验并立即作废令牌：无论成功与否都删除，杜绝重放；
     * 令牌带 60 秒 TTL，签发后服务未消费也不会长期残留可用凭证。
     * 使用 MessageDigest.isEqual 做常量时间比较。
     */
    public boolean consumeStopToken(String token) {
        String saved = sp.getString(KEY_STOP_TOKEN, "");
        long at = sp.getLong(KEY_STOP_TOKEN_AT, 0L);
        sp.edit().remove(KEY_STOP_TOKEN).remove(KEY_STOP_TOKEN_AT).commit();
        if (token == null || token.isEmpty() || saved == null || saved.isEmpty()) return false;
        long now = System.currentTimeMillis();
        if (at <= 0L || now - at < 0L || now - at > STOP_TOKEN_TTL_MS) return false;
        return MessageDigest.isEqual(
                saved.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public String bankSource() { return sp.getString("bank_source", "未初始化"); }
    public String bankVersion() { return sp.getString("bank_version", "-"); }
    public String bankUpdated() { return sp.getString("bank_updated", "-"); }
    public long bankChecked() { return sp.getLong("bank_checked", 0L); }
    public int bankPacks() { return sp.getInt("bank_packs", 0); }
    public int bankQuestions() { return sp.getInt("bank_questions", 0); }
    public String bankMessage() { return sp.getString("bank_message", "尚未同步"); }

    public void setBankStatus(String source, String version, String updatedAt,
                              long checkedAt, int packs, int questions, String message) {
        sp.edit().putString("bank_source", source)
                .putString("bank_version", version)
                .putString("bank_updated", updatedAt)
                .putLong("bank_checked", checkedAt)
                .putInt("bank_packs", packs)
                .putInt("bank_questions", questions)
                .putString("bank_message", message).apply();
    }

    private static final int PBKDF2_ITER = 60000;
    private static final int KEY_BITS = 256;

    /** PBKDF2WithHmacSHA1 is available on API 21+ and any standard JVM. */
    private static byte[] derive(String pin, byte[] salt, int iter) {
        if (pin == null || salt == null || salt.length == 0 || iter <= 0) return null;
        PBEKeySpec spec = null;
        try {
            spec = new PBEKeySpec(pin.toCharArray(), salt, iter, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(spec).getEncoded();
        } catch (Exception e) {
            return null;
        } finally {
            if (spec != null) spec.clearPassword();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        if (s == null || s.length() % 2 != 0) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** 兼容既有调用与单测；实现统一收敛到 Hashes。 */
    public static String sha256Hex(String s) {
        return Hashes.sha256Hex(s);
    }
}
