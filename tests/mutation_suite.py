#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PoHW 负向变异回归（可从当前 HEAD 复核）。

每组故意回退一处实现，确认布局/发布契约、运行时题库分布校验或构建门禁会拦下它；
最后校验源码哈希完全恢复，并用发布密钥重建 dist。
手动运行：python3 tests/mutation_suite.py
不接入 build.sh：它会跑多次完整构建，只用于发布前复核。
"""
import hashlib
import io
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app/src/com/jager/tvquiz")
M = os.path.join(SRC, "MainActivity.java")
S = os.path.join(SRC, "SettingsActivity.java")
E = os.path.join(SRC, "QuizEngine.java")
D = os.path.join(SRC, "QuizDb.java")
O = os.path.join(SRC, "QuizOverlayService.java")
ST = os.path.join(SRC, "SettingsStore.java")
W = os.path.join(SRC, "SubjectWeights.java")
B = os.path.join(ROOT, "app/build.sh")
MF = os.path.join(ROOT, "app/AndroidManifest.xml")
C = os.path.join(ROOT, "tests/layout_contract.py")
DO = os.path.join(SRC, "DonateActivity.java")
LP = os.path.join(SRC, "LaunchPolicy.java")
CHK = os.path.join(ROOT, "tests/bank_distribution_check.py")
BK_DIR = tempfile.mkdtemp(prefix="pohw_mut_bk_")
BK = {}
for p in (M, S, E, D, O, ST, W, DO, LP, B, MF, C, CHK):
    BK[p] = os.path.join(BK_DIR, os.path.basename(p) + ".bk")
    shutil.copyfile(p, BK[p])
ORIG = {p: hashlib.sha256(open(p, "rb").read()).hexdigest() for p in BK}
FAIL = []

OLD_OTHER_GRADE = """            "WHERE grade=? AND subject NOT IN ('数学','语文','英语')";"""
NEW_OTHER_GRADE = """            "WHERE grade=?";"""
DECOY = """
// POOL_OTHER_GRADE = "WHERE grade=? AND subject NOT IN ('数学','语文','英语')";
"""


def restore():
    for p, b in BK.items():
        shutil.copyfile(b, p)


def read(p):
    return io.open(p, encoding="utf-8").read()


def write(p, t):
    io.open(p, "w", encoding="utf-8").write(t)


def replace(p, old, new):
    s = read(p)
    if s.count(old) != 1:
        raise RuntimeError("anchor mismatch %s count=%d for %r" % (
            os.path.basename(p), s.count(old), old[:70]))
    write(p, s.replace(old, new, 1))

def env_for(with_key):
    env = os.environ.copy()
    env["JAVA_HOME"] = "/opt/jdk17"
    env["ANDROID_HOME"] = "/tmp/apkbuild/sdk"
    for k in ("POHW_KEYSTORE", "POHW_KEYSTORE_PASS", "POHW_ALLOW_DEV_SIGN"):
        env.pop(k, None)
    if with_key:
        env["POHW_KEYSTORE"] = "/tmp/apkbuild/debug.keystore"
        env["POHW_KEYSTORE_PASS"] = "android"
    return env


def contract():
    return subprocess.run(["python3", "tests/layout_contract.py"], cwd=ROOT,
                          capture_output=True, text=True)


def distro():
    return subprocess.run(["python3", "tests/bank_distribution_check.py"], cwd=ROOT,
                          capture_output=True, text=True)


def build(with_key):
    return subprocess.run(["bash", "app/build.sh"], cwd=ROOT, env=env_for(with_key),
                          capture_output=True, text=True)


def first_reason(out, keys):
    lines = out.splitlines()
    for line in lines:
        if any(k in line for k in keys):
            return line
    return lines[-1] if lines else "?"


def break_other_grade():
    replace(D, OLD_OTHER_GRADE, NEW_OTHER_GRADE)


def break_other_grade_with_decoy():
    break_other_grade()
    write(D, read(D) + DECOY)


# (名称, 说明, 变异函数, 是否验证构建门禁, 是否必须由契约拦下, 是否必须由运行时分布校验拦下)
MUTATIONS = [
    ("A", "home back to 2 columns", lambda: replace(M, "GRID_COLS = 3", "GRID_COLS = 2"), False, True, False),
    ("B", "legacy column helper", lambda: write(S, read(S) + "\n// wireColumns\n"), False, True, False),
    ("C", "line-commented vertical focus", lambda: replace(S, "        wireVerticalFocus();", "        // wireVerticalFocus();"), False, True, False),
    ("E", "block-commented subject focus", lambda: replace(S, "        wireSubjectRows();", "        /*\n        wireSubjectRows();\n        */"), False, True, False),
    ("F", "fillViewport removed", lambda: replace(S, "        sv.setFillViewport(true);", "        // sv.setFillViewport(true);"), False, True, False),
    ("G", "fixed-height scroll area", lambda: replace(S, "        root.addView(sv, Ui.fill(1));", "        root.addView(sv, Ui.fixed(this, 300));"), False, True, False),
    ("H", "cert fingerprint changed", lambda: replace(B, "76eb6063b574d6df1cd52ac490d82185793bf48abe0185687f4d977e49626172", "deadbeef"), False, True, False),
    ("I", "back to three weight slots", lambda: replace(W, "new int[Subjects.COUNT]", "new int[3]"), True, True, False),
    ("J", "hardcoded 15 percent restored",
     lambda: replace(E, "        return weights.pick(r);",
                     "        if (allSubjects && !bankOther.isEmpty() && r.nextInt(100) < 15) return Subjects.OTHER;\n        return weights.pick(r);"), True, True, False),
    ("K", "other-pool SQL constant broken", break_other_grade, True, False, True),
    ("L", "label derived from chosen pool again",
     lambda: replace(D, "subjectFromBank(bankSubject)", "subjectFromBank(null)"), False, True, False),
    ("M", "fourth slot pinned to zero",
     lambda: replace(W, "w[Subjects.OTHER] = clampWeight(other);", "w[Subjects.OTHER] = 0;"), False, True, False),
    ("N", "fourth weight row disabled again",
     lambda: replace(S, "        track(ed, wt);", "        track(ed);\n        wt.setEnabled(false);"), False, True, False),
    ("O", "service stops passing the fourth weight",
     lambda: replace(O, "st.weight(Subjects.OTHER)", "0"), False, True, False),
    ("P", "versionCode rolled back",
     lambda: replace(B, 'VERSION_CODE="17"', 'VERSION_CODE="16"'), False, True, False),
    ("Q", "OTHER routing disabled",
     lambda: replace(D, "boolean other = s == Subjects.OTHER && allSubjects;", "boolean other = false;"), True, True, False),
    ("R", "single WHERE binding",
     lambda: replace(D, "new String[args.length * 2]", "new String[args.length]"), True, True, False),
    ("S", "broken SQL plus comment decoy", break_other_grade_with_decoy, True, False, True),
    ("T", "pool-preserving fallback removed",
     lambda: replace(D, "        if (q == null) q = pick(poolWhere, poolArgs);", "        if (q == null) q = pick(\"\", null);"), True, True, False),
    ("U", "empty non-core pool ignored",
     lambda: replace(E, "db.hasOtherQuestions()", "true"), False, True, False),
    ("V", "row gap removed",
     lambda: replace(S, "ROW_GAP_DP = 12", "ROW_GAP_DP = 0"), False, True, False),
    ("W", "boot autostart default flipped back on",
     lambda: replace(ST, 'getBoolean("bootAutostart", false)',
                     'getBoolean("bootAutostart", true)'), False, True, False),
    ("X", "launch-to-guard default flipped back off",
     lambda: replace(ST, 'getBoolean("autoGuardOnBoot", true)',
                     'getBoolean("autoGuardOnBoot", false)'), False, True, False),
    ("Y", "stale boot-only label restored",
     lambda: replace(S, "开启（启动即守护）", "开启（开机即守护）"), False, True, False),
    ("Z", "home no longer delegates to LaunchPolicy",
     lambda: replace(M, "LaunchPolicy.shouldAutoGuard",
                     "LaunchPolicy.alwaysGuard"), True, True, False),
    ("AA", "donate activity removed from manifest",
     lambda: replace(MF, 'android:name=".DonateActivity"',
                     'android:name=".NoDonate"'), False, True, False),
    ("AB", "DPAD channel switching removed",
     lambda: replace(DO, "KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_DOWN"), False, True, False),
    ("AC", "alipay QR resource dropped",
     lambda: replace(DO, "R.drawable.donate_alipay",
                     "R.drawable.donate_wechat"), True, True, False),
]

print("--- baseline ---")
base = contract()
print(base.stdout.strip().splitlines()[-1])
if base.returncode != 0:
    FAIL.append("baseline contract")

for name, label, apply_mut, with_build, need_contract, need_distro in MUTATIONS:
    restore()
    apply_mut()
    c = contract()
    if need_contract:
        if c.returncode == 0:
            print("MUT_%s_CONTRACT_NOT_CAUGHT (%s)" % (name, label))
            FAIL.append(name + " contract")
        else:
            print("MUT_%s_CONTRACT_REJECTED: %s" % (
                name, first_reason(c.stdout, ["LAYOUT_CONTRACT_FAIL"])))
    else:
        print("MUT_%s_CONTRACT_INFO: %s" % (
            name, "passed (runtime check must catch it)" if c.returncode == 0
            else "also rejected"))
    if need_distro:
        d = distro()
        if d.returncode == 0:
            print("MUT_%s_DISTRIBUTION_NOT_CAUGHT" % name)
            FAIL.append(name + " distribution")
        else:
            print("MUT_%s_DISTRIBUTION_REJECTED: %s" % (
                name, first_reason(d.stdout + d.stderr, ["BANK_DISTRIBUTION_FAIL"])))
    if with_build:
        b = build(False)
        if b.returncode == 0:
            print("MUT_%s_BUILD_NOT_CAUGHT" % name)
            FAIL.append(name + " build")
        else:
            print("MUT_%s_BUILD_REJECTED: %s" % (
                name, first_reason(b.stdout + b.stderr,
                                   ["LAYOUT_CONTRACT_FAIL", "FORBIDDEN",
                                    "BANK_DISTRIBUTION_FAIL"])))

restore()
print("--- D: dev-signed build must be refused ---")
d = build(False)
reason = [l for l in (d.stdout + d.stderr).splitlines() if "FORBIDDEN: APK certificate" in l]
if d.returncode == 0 or not reason:
    print("MUT_D_BUILD_NOT_CAUGHT")
    FAIL.append("D build")
    print("\n".join((d.stdout + d.stderr).splitlines()[-6:]))
else:
    print("MUT_D_BUILD_REJECTED: " + reason[0])

restore()
print("--- restore integrity ---")
for p, digest in ORIG.items():
    if hashlib.sha256(open(p, "rb").read()).hexdigest() != digest:
        print("RESTORE_BAD " + os.path.basename(p))
        FAIL.append("restore " + p)
print(contract().stdout.strip().splitlines()[-1])

print("--- restore release-signed dist ---")
f = build(True)
for line in f.stdout.splitlines():
    if any(k in line for k in ("LAYOUT_CONTRACT_OK", "BANK_DISTRIBUTION_OK",
                               "FALLBACK_POOL_OK", "JVM_TESTS_OK", "BUILD_OK")):
        print(line)
if f.returncode != 0:
    print("RESTORE_BUILD_FAILED")
    print("\n".join((f.stdout + f.stderr).splitlines()[-12:]))
    FAIL.append("restore build")

count = len(MUTATIONS) + 1
if FAIL:
    print("MUTATION_SUITE_FAIL " + ",".join(FAIL))
    sys.exit(1)
print("MUTATION_SUITE_OK %d/%d" % (count, count))
