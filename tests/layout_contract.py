#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PoHW UI / 发布契约：源码级静态断言，零 Android 依赖，可由构建门禁直接执行。

首页 3 列 × 2 排、家长设置单列卡片流与发布证书连续性是 TV 遥控器可用性
与覆盖安装的硬约束；纯 JVM 单测无法实例化 android.view.*，因此用源码符号
契约守住结构，再配合 build.sh 的 javac/apksigner 保证符号真的被编译进 dex。

注意：调用次数类断言必须基于「去注释代码行」（含 /* */ 块注释状态机），
否则把调用注释掉也会被计数放行；三栏残留这类负向断言仍基于原始文件。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app/src/com/jager/tvquiz/MainActivity.java")
SET = os.path.join(ROOT, "app/src/com/jager/tvquiz/SettingsActivity.java")
BUILD = os.path.join(ROOT, "app/build.sh")

TOTAL = 0


def read(path):
    with io.open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_comments(text):
    """丢弃 // 整行注释与 /* ... */ 块注释（含跨行块），保留行内代码。"""
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


def check(ok, msg):
    global TOTAL
    if not ok:
        print("LAYOUT_CONTRACT_FAIL: " + msg)
        sys.exit(1)
    TOTAL += 1
    print("[PASS] " + msg)


raw_m = read(MAIN)
raw_s = read(SET)
b = read(BUILD)
m = strip_comments(raw_m)
s = strip_comments(raw_s)

# ---- 首页：3 列 × 2 排 ----
check("private static final int GRID_COLS = 3;" in m, "home declares 3 columns")
check(m.count("new Button[GRID_COLS]") == 2, "home has exactly 2 grid rows")
check(len(re.findall(r"actionButton\(row[12],", m)) == 6, "home has exactly 6 action buttons")
check("row3" not in m, "legacy third button row removed")
check(m.count("l.addView(row") == 2, "exactly two grid rows added to the page")
check("new LinearLayout.LayoutParams(0, Ui.dp(this, 54), 1f)" in m,
      "home buttons are equal-width instead of hardcoded 190dp")
check(m.count("wireGrid()") == 2, "home declares and calls the grid focus chain")
for direction in ("setNextFocusLeftId", "setNextFocusRightId",
                  "setNextFocusUpId", "setNextFocusDownId"):
    check(direction in m, "home focus chain includes " + direction)
check("Ui.fixed(this, 46)" in m, "home title has enough height to avoid clipping")
check("getPackageInfo" in m, "home version still read from PackageManager")

# ---- 家长设置：单列卡片流 ----
check(s.count("new ScrollView(this)") == 1, "settings has exactly one scroll container")
check(s.count("addCard(") == 6, "settings declares 5 grouped cards + helper")
check(len(re.findall(r'addCard\(col, "0\d', s)) == 5, "settings has 5 numbered cards")
check("addColumn" not in raw_s and "wireColumns" not in raw_s,
      "legacy three-column helpers are gone")
check(s.count("wireVerticalFocus()") == 2, "settings declares and calls the vertical focus chain")
check(s.count("wireSubjectRows()") == 2, "subject rows keep their left/right chain")
check("setNextFocusUpId" in s and "setNextFocusDownId" in s,
      "settings vertical chain is explicit across nested cards")
check("root.addView(footer, Ui.fixed(this, 54))" in s,
      "save / change-pin footer stays fixed while the form scrolls")
check(s.count('"保存并返回"') == 1, "exactly one save action")
check(s.count('"检查题库更新"') == 1, "bank update entry lives in settings only")
check("track(urlEdit)" in s, "URL field participates in the focus chain")
check("setMinHeight(Ui.dp(this, 72))" in s,
      "bank status uses min-height instead of a clipping fixed height")
check("Ui.dp(this, GUTTER_DP)" in s, "cards and footer share one centred gutter")
check("ViewGroup.LayoutParams.WRAP_CONTENT, 1f" in s,
      "row values fill the narrow column by weight and wrap by content")

# 复审加固：滚动容器参数与左右焦点边界
check("sv.setFillViewport(true)" in s, "scroll view uses fillViewport for short content")
check("root.addView(sv, Ui.fill(1))" in s, "scroll area takes remaining height by weight")
check("shell.addView(col" in s and "ViewGroup.LayoutParams.WRAP_CONTENT" in s,
      "scroll child is wrap_content so long forms can scroll")
check(s.count("Ui.fill(1)") == 1, "exactly one weighted scroll area, footer stays fixed")
check("root.addView(sv, Ui.fixed(" not in s and "setFillViewport(false)" not in s,
      "no fixed-height / non-fill scroll regression")
check(s.count("setMinHeight(Ui.dp(this, 44))") >= 4,
      "row buttons keep a 44dp minimum but grow with font scale")
check("getNextFocusLeftId() == View.NO_ID" in s
      and "getNextFocusRightId() == View.NO_ID" in s,
      "undefined left/right targets are pinned to self, not geometric search")
check("!(v instanceof EditText)" in s,
      "URL EditText keeps left/right keys for cursor movement")

# ---- 发布证书门禁：防止 dev 随机密钥产出可发布 APK ----
check("RELEASE_CERT_SHA256" in b and "ACTUAL_CERT_SHA256" in b,
      "build compares actual APK certificate to the release certificate")
check("apksigner verify --print-certs" in b,
      "certificate is read from the signed APK, not from the keystore alone")
check("e5816bddefeb145849d98fa9c75149dc26c9cc3ea0d58a717de442e48212286c" in b,
      "pinned release certificate SHA-256 is present")
check("POHW_ALLOW_DEV_SIGN" in b,
      "dev-sign override is explicit and opt-in")
check("versionCode='$VERSION_CODE'" in b,
      "artifact check verifies versionCode as well as versionName")


# ---- 方案 B：四槽学科权重、双路径抽题与题目标签反推 ----
_w = strip_comments(read(os.path.join(ROOT, "app/src/com/jager/tvquiz/SubjectWeights.java")))
_rw_e = read(os.path.join(ROOT, "app/src/com/jager/tvquiz/QuizEngine.java"))
_e = strip_comments(_rw_e)
_rw_d = read(os.path.join(ROOT, "app/src/com/jager/tvquiz/QuizDb.java"))
_d = strip_comments(_rw_d)
_st = strip_comments(read(os.path.join(ROOT, "app/src/com/jager/tvquiz/SettingsStore.java")))
_o = strip_comments(read(os.path.join(ROOT, "app/src/com/jager/tvquiz/QuizOverlayService.java")))
_mf = read(os.path.join(ROOT, "app/AndroidManifest.xml"))
_run = read(os.path.join(ROOT, "tests/run.sh"))

check("new int[Subjects.COUNT]" in _w, "weights declare four subject slots")
check("w[Subjects.OTHER] = clampWeight(other);" in _w, "other slot is wired to the setting")
check("!(allSubjects && otherAvailable)" in _w, "other slot is gated by the all-subjects switch")
check("setOtherAvailable" in _w, "other slot can be disabled when no question bank is ready")
check("public int pick(Random r)" in _w and "MAX_WEIGHT" in _w,
      "weighted roulette is pure Java and clamped")
check("private final SubjectWeights weights;" in _e, "engine delegates to SubjectWeights")
check("int[3]" not in _e, "legacy three-slot weight array is gone")
check("return weights.pick(r);" in _e, "engine draws from the four-slot roulette")
check("syncOtherAvailable()" in _e, "engine refreshes OTHER availability on db/bank changes")
check("r.nextInt(100) < 15" not in _e, "hardcoded 15 percent branch is gone")
check("s == Subjects.OTHER) s = MATH" not in _e,
      "OTHER is no longer downgraded to a math question")
check("gradeWide" not in _d, "grade-wide cross-subject SQL is gone")
check("NOT IN ('数学','语文','英语')" in _d, "other-subject pool uses its own SQL predicate")
check("WHERE grade=? AND subject=?" in _d, "core subjects keep their own SQL predicate")
check("POOL_OTHER_ALL" in _d and "POOL_CORE_ALL" in _d,
      "pool-preserving fallback predicates are declared as constants")
check("pick(poolWhere, poolArgs)" in _d,
      "first fallback keeps the subject constraint and only drops the grade")
check("subjectFromBank(bankSubject)" in _d, "question label is derived from the real bank subject")
check("fromBank(subject," not in _d, "legacy label from the chosen pool is gone")
check("st.weight(Subjects.OTHER)" in _o, "service passes the fourth weight to the engine")
check("st.weight(3)" in m, "home status line shows the four-subject ratio")
check("wt.setEnabled(false)" not in raw_s, "other-subject weight button is enabled")
check("if (subj < 3)" not in raw_s and "if (s < 3)" not in raw_s,
      "no three-subject-only UI branches remain")
check("未启用" in raw_s, "disabled fourth slot is labelled in the UI")
check("SubjectWeights.MAX_WEIGHT + 1" in raw_s,
      "UI weight cycle is driven by the same max weight")
check("Subjects.norm(subject)" in _st and '"w" + s' in _st,
      "settings store persists all four normalized slots")
check('android:versionCode="21"' in _mf, "manifest versionCode advances to 21")
check('VERSION_CODE="21"' in b, "build script versionCode advances to 21")
check("SubjectWeights.java" in _run and "SubjectWeightsTest" in _run,
      "new weights class and JVM test are wired into the test runner")
check("bank_distribution_check.py" in b,
      "runtime SQL distribution regression is wired into the build")
check("grep -qF 'new int[Subjects.COUNT]'" in b,
      "build gate matches the four-slot declaration literally, not as a regex")


# ---- 独立复审加固：路由 / 绑定 / 兜底 / 状态的源码级护栏 ----
_rw_w = read(os.path.join(ROOT, "app/src/com/jager/tvquiz/SubjectWeights.java"))
_chk = read(os.path.join(ROOT, "tests/bank_distribution_check.py"))
_mut = os.path.join(ROOT, "tests/mutation_suite.py")

check("private boolean otherAvailable = false;" in _rw_w,
      "OTHER defaults to unavailable without an explicit bank readiness signal")
check("boolean other = s == Subjects.OTHER && allSubjects;" in _d,
      "OTHER pool routing condition is pinned")
check("boolean other = s == Subjects.OTHER && allSubjects;" in _chk,
      "runtime distribution check also pins the routing condition")
check("new String[args.length * 2]" in _d and _d.count("System.arraycopy") >= 2,
      "WHERE params are bound twice for main query and COUNT subquery")
check("new String[args.length * 2]" in _chk,
      "runtime distribution check also pins the double binding")
check("POOL_OTHER_GRADE" in _d and "POOL_CORE_GRADE" in _d,
      "both main-query predicates are named constants")
check("db.hasOtherQuestions()" in _e,
      "engine disables OTHER when the bank has no non-core questions")
check("hasOtherQuestions()" in _d, "QuizDb exposes the non-core pool count check")
check("if (qs != null)" in _e, "setBank(null) still refreshes bank availability")
check("random(int grade, int subject)" not in _d,
      "deprecated two-arg random overload that silently drew math is gone")
check("Log.e(TAG, " in _d, "SQL failures are logged instead of silently swallowed")
check("strip_comments" in _chk and "FALLBACK_POOL_OK" in _chk,
      "distribution check strips comments and verifies pool-preserving fallbacks")
check("MAX(1, (SELECT COUNT(*) FROM questions " in _d
      and "MAX(1, (SELECT COUNT(*) FROM questions " in _chk,
      "empty-pool-safe OFFSET template is shared by Java and the runtime check")
check("POOL_OTHER_ALL" in _chk and "POOL_CORE_ALL" in _chk,
      "distribution check reads the same named SQL constants")
check(os.path.isfile(_mut), "mutation harness is committed and reproducible")
check("JVM unit tests" in b, "build invokes JVM behavior tests")
check("显示「—」并禁用焦点" not in raw_s, "stale UI comment about the fourth row is gone")
check("题库不可用" in raw_s, "settings note explains the bank-unavailable case")



# ---- 设置页行间距 / 四个默认开关 / APP 启动即守护 ----
_launch = strip_comments(read(os.path.join(
    ROOT, "app/src/com/jager/tvquiz/LaunchPolicy.java")))
_res = os.path.join(ROOT, "app/res")

check("ROW_GAP_DP = 12" in s, "settings declares a shared 12dp row gap")
check(s.count("rowLp()") == 5, "row gap is applied to every row, button and note")
check("p.setMargins(0, half, 0, half)" in s, "row gap is symmetric top and bottom")
check('getBoolean("bootAutostart", false)' in _st,
      "boot autostart defaults off (parent opt-in)")
check('getBoolean("autoGuardOnBoot", true)' in _st,
      "launch-to-guard defaults on")
check('getBoolean("pauseOnQuiz", true)' in _st
      and 'getBoolean("resumePlayback", true)' in _st,
      "pause and resume both default on")
check("开启（启动即守护）" in raw_s and "开机即守护" not in raw_s,
      "auto-guard label says launch, not boot")
check("LaunchPolicy.shouldAutoGuard" in m,
      "home delegates launch auto-guard to a pure policy")
check("b == null" in m, "auto-guard fires only on a genuine cold start")
check("启动即守护" in m, "home status line uses the new wording")
check("public static boolean shouldAutoGuard" in _launch,
      "launch policy is pure Java and unit-testable")
check("LaunchPolicy.java" in _run, "launch policy is compiled into JVM tests")

# ---- v1.0.2：打赏功能已整体移除，防止被误加回 ----
check("DonateActivity" not in raw_s and "打赏" not in raw_s,
      "no donation entry or copy remains in parent settings")
check('android:name=".DonateActivity"' not in _mf,
      "donate activity is not declared in the manifest")
check(not os.path.isfile(os.path.join(
    ROOT, "app/src/com/jager/tvquiz/DonateActivity.java")),
      "donate activity source is deleted")
check(not any(os.path.isfile(os.path.join(_res, "drawable-nodpi", f)) for f in
              ("donate_wechat.png", "donate_alipay.png")),
      "no donation QR PNGs are packaged as APK resources")

# ---- 品牌资源：可复现生成器 + 多密度图标 + TV banner ----
check(os.path.isfile(os.path.join(ROOT, "tools/branding/make_icons.py")),
      "icon generator is committed and reproducible")
check(all(os.path.isfile(os.path.join(_res, d, "ic_launcher.png")) for d in
          ("drawable-mdpi", "drawable-hdpi", "drawable-xhdpi",
           "drawable-xxhdpi", "drawable-xxxhdpi")),
      "launcher icon ships at five densities")
check(os.path.isfile(os.path.join(_res, "drawable-xhdpi/tv_banner.png")),
      "TV banner is shipped")
check("make_icons.py" in b, "build gate verifies the branding generator")


# ---- 题库说明位置：离线说明必须紧跟「检查题库更新」按钮 ----
check(raw_s.find('"检查题库更新"') < raw_s.find('"默认完全离线：'),
      "offline note sits directly under the bank-update button")


# ---- v1.0.4 行为红线：至少答对一题才能返回 + 持续暂停播放 ----
_src = os.path.join(ROOT, "app/src/com/jager/tvquiz")
_v = strip_comments(read(os.path.join(_src, "OverlayQuizView.java")))
_svc = strip_comments(read(os.path.join(_src, "QuizOverlayService.java")))
_kr = strip_comments(read(os.path.join(_src, "KeyRouter.java")))
_mpc = strip_comments(read(os.path.join(_src, "MediaPauseController.java")))
_ep = strip_comments(read(os.path.join(_src, "ExitPolicy.java")))
_rv = read(os.path.join(_src, "OverlayQuizView.java"))

check("public static boolean canExit(int sessionCorrect)" in _ep
      and "REQUIRED_CORRECT = 1" in _ep,
      "exit gate is a single pure-Java rule requiring one correct answer")
check("sessionCorrect" in _svc and "\n        sessionCorrect = 0;" in _svc,
      "service resets the correct-answer counter on every new quiz popup")
check("ExitPolicy.canExit(sessionCorrect)" in _svc,
      "service re-checks the exit gate instead of trusting the view")
check("if (!exitAllowed) { nudgeExit(); return; }" in _v,
      "back key is swallowed until the exit gate opens")
check("setExitAllowed(ExitPolicy.canExit(sessionCorrect))" in _svc,
      "a correct answer unlocks the back key immediately")
check("换题不清 exitAllowed" in _rv,
      "binding a new question cannot re-lock an already unlocked session")
check("KeyRouter.SEM_HOME" in _v and "ACT_PARENT" in _v,
      "HOME is swallowed and the parent PIN escape has its own semantic")
check("SEM_HOME = 7" in _kr and "SEM_PARENT = 8" in _kr,
      "key router declares both new semantics")
check("if (semantic == SEM_HOME) return new Decision(ACT_CONSUME, 0);" in _kr,
      "HOME down AND up are consumed")
check('android:name=".MediaSessionListener"' in _mf,
      "notification-listener service is declared for session-level pause")
check("getActiveSessions" in _mpc and "tc.pause()" in _mpc,
      "all active MediaSessions are paused by name")
check("PULSE_INTERVAL" in _mpc and "handler.postDelayed(pulse, FIRST_DELAY)" in _mpc,
      "MEDIA_PAUSE is re-dispatched while the quiz is showing")
check("只挂一条链" in read(os.path.join(_src, "MediaPauseController.java")),
      "pause pulse stays a single chain (no exponential duplicate dispatch)")
check("hasSessionControl" in raw_s and "ACTION_NOTIFICATION_LISTENER_SETTINGS" in raw_s,
      "parent settings expose the notification-access opt-in")
check("ExitPolicy.java" in _run and "ExitGateTest.java" in _run
      and "ExitGateTest; do" in _run,
      "the exit gate is compiled and executed by the JVM suite")
check("至少答对 1 题" in raw_s,
      "parent settings explain the one-correct-answer rule")

print("LAYOUT_CONTRACT_OK total=%d" % TOTAL)
