#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Windows 本地构建（无 Gradle，也不依赖 Git Bash）。

与 app/build.sh 等价的流程：aapt2 compile/link -> javac -> d8 -> 合入 dex
-> zipalign -> apksigner。全部使用 Windows 风格路径，规避 Git Bash 的
/c/... 路径与 .bat 工具 shim 问题；适用于本地快速出包与真机验证。

用法（PowerShell / cmd）：
    set JAVA_HOME=C:\\path\\to\\jdk17
    set ANDROID_HOME=C:\\path\\to\\android-sdk
    python tools\\local\\build_windows.py

覆盖项：
    POHW_KEYSTORE / POHW_KEYSTORE_PASS  指定发布密钥；不设则用 .build-win 下的本地调试密钥
    POHW_VER_NAME / POHW_VER_CODE       覆盖版本号
"""
import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
APP = os.path.join(ROOT, "app")
BUILD = os.path.join(ROOT, ".build-win")
DIST = os.path.join(ROOT, "dist")

VERSION_NAME = os.environ.get("POHW_VER_NAME", "1.0.7")
VERSION_CODE = os.environ.get("POHW_VER_CODE", "24")
APK_NAME = "PoHW-v%s.apk" % VERSION_NAME

JAVA_HOME = os.environ.get("JAVA_HOME", "")
ANDROID_HOME = os.environ.get("ANDROID_HOME", "")
if not JAVA_HOME or not ANDROID_HOME:
    sys.exit("请先设置 JAVA_HOME 与 ANDROID_HOME")

BT = os.path.join(ANDROID_HOME, "build-tools", "34.0.0")
AAPT2 = os.path.join(BT, "aapt2.exe")
AAPT = os.path.join(BT, "aapt.exe")
ZIPALIGN = os.path.join(BT, "zipalign.exe")
D8_JAR = os.path.join(BT, "lib", "d8.jar")
APKSIGNER_JAR = os.path.join(BT, "lib", "apksigner.jar")
JAVAC = os.path.join(JAVA_HOME, "bin", "javac.exe")
JAVA = os.path.join(JAVA_HOME, "bin", "java.exe")
KEYTOOL = os.path.join(JAVA_HOME, "bin", "keytool.exe")

ANDROID_JAR = os.path.join(ANDROID_HOME, "platforms", "android-33", "android.jar")
if not os.path.isfile(ANDROID_JAR):
    ANDROID_JAR = os.path.join(ANDROID_HOME, "platforms", "android-13", "android.jar")

for p in (AAPT2, AAPT, ZIPALIGN, D8_JAR, APKSIGNER_JAR, JAVAC, JAVA, KEYTOOL, ANDROID_JAR):
    if not os.path.isfile(p):
        sys.exit("缺少依赖: " + p)
if not os.path.isfile(os.path.join(APP, "assets", "quiz.db")):
    sys.exit("缺少 app/assets/quiz.db")


def run(args):
    print("+ " + " ".join(str(a) for a in args))
    r = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                       errors="replace")
    if r.returncode != 0:
        print(r.stdout)
        sys.exit("命令失败(%d): %s" % (r.returncode, args[0]))
    return r.stdout


def fresh(path):
    if os.path.isdir(path):
        shutil.rmtree(path)
    os.makedirs(path, exist_ok=True)


fresh(BUILD)
OUT = os.path.join(BUILD, "out")
os.makedirs(OUT, exist_ok=True)
os.makedirs(os.path.join(OUT, "gen"), exist_ok=True)
os.makedirs(os.path.join(OUT, "classes"), exist_ok=True)
os.makedirs(os.path.join(OUT, "dex"), exist_ok=True)
os.makedirs(DIST, exist_ok=True)

print("[1/7] aapt2 compile")
run([AAPT2, "compile", "--dir", os.path.join(APP, "res"),
     "-o", os.path.join(OUT, "res.zip")])

print("[2/7] aapt2 link")
run([AAPT2, "link", "-o", os.path.join(OUT, "base.apk"),
     "-I", ANDROID_JAR,
     "--manifest", os.path.join(APP, "AndroidManifest.xml"),
     "-A", os.path.join(APP, "assets"),
     "--java", os.path.join(OUT, "gen"),
     "--min-sdk-version", "21", "--target-sdk-version", "33",
     "--version-code", VERSION_CODE, "--version-name", VERSION_NAME,
     os.path.join(OUT, "res.zip")])

print("[3/7] javac")
sources = []
for base in (os.path.join(APP, "src"), os.path.join(OUT, "gen")):
    for dirpath, _dirs, files in os.walk(base):
        for f in files:
            if f.endswith(".java"):
                sources.append(os.path.join(dirpath, f))
src_list = os.path.join(OUT, "sources.txt")
with open(src_list, "w", encoding="utf-8") as fh:
    fh.write("\n".join(sources))
run([JAVAC, "-source", "8", "-target", "8", "-encoding", "UTF-8", "-nowarn",
     "-classpath", ANDROID_JAR, "-d", os.path.join(OUT, "classes"), "@" + src_list])

print("[4/7] d8")
classes = []
for dirpath, _dirs, files in os.walk(os.path.join(OUT, "classes")):
    for f in files:
        if f.endswith(".class"):
            classes.append(os.path.join(dirpath, f))
cls_list = os.path.join(OUT, "classes.txt")
with open(cls_list, "w", encoding="utf-8") as fh:
    fh.write("\n".join(classes))
run([JAVA, "-cp", D8_JAR, "com.android.tools.r8.D8",
     "--lib", ANDROID_JAR, "--min-api", "21",
     "--output", os.path.join(OUT, "dex"), "@" + cls_list])

print("[5/7] 合入 classes.dex")
base_apk = os.path.join(OUT, "base.apk")
with zipfile.ZipFile(base_apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(OUT, "dex", "classes.dex"), "classes.dex")

print("[6/7] zipalign")
run([ZIPALIGN, "-f", "4", base_apk, os.path.join(OUT, "aligned.apk")])

print("[7/7] sign")
KS = os.environ.get("POHW_KEYSTORE") or os.path.join(ROOT, ".dev-keystore.jks")
KS_PASS = os.environ.get("POHW_KEYSTORE_PASS") or "pohwdevpass"
ALIAS = os.environ.get("POHW_KEY_ALIAS") or "pohw"
if not os.path.isfile(KS):
    run([KEYTOOL, "-genkeypair", "-keystore", KS, "-storepass", KS_PASS,
         "-keypass", KS_PASS, "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048",
         "-validity", "10000", "-dname", "CN=PoHW Dev, O=Local", "-noprompt"])
out_apk = os.path.join(DIST, APK_NAME)
run([JAVA, "-jar", APKSIGNER_JAR, "sign", "--ks", KS,
     "--ks-pass", "pass:" + KS_PASS, "--key-pass", "pass:" + KS_PASS,
     "--out", out_apk, os.path.join(OUT, "aligned.apk")])
run([JAVA, "-jar", APKSIGNER_JAR, "verify", "--verbose", out_apk])

print("=== RESULT ===")
print(os.path.getsize(out_apk), "bytes ->", out_apk)
print(run([AAPT, "dump", "badging", out_apk]).splitlines()[0])
