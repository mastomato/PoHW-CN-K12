#!/usr/bin/env bash
# 从源码构建 PoHW APK：aapt2 -> javac -> d8 -> zipalign（不含签名）
#
# 依赖：JDK 17、Android build-tools、Android platform 的 android.jar
#   export JAVA_HOME=/path/to/jdk-17
#   export ANDROID_HOME=$HOME/Library/Android/sdk     # 需含 build-tools/34.0.0 与 platforms/android-33
#
# 可选覆盖（SDK 目录结构不标准时）：
#   AAPT2=/path/to/aapt2  D8_JAR=/path/to/d8.jar  ANDROID_JAR=/path/to/android.jar
#
# 输出：build/out/aligned.apk（未签名，签名见 docs/BUILD-LOCAL.md）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA_HOME="${JAVA_HOME:?请先设置 JAVA_HOME 指向 JDK 17}"
ANDROID_HOME="${ANDROID_HOME:?请先设置 ANDROID_HOME}"
BT_VERSION="${BT_VERSION:-34.0.0}"
PLATFORM="${PLATFORM:-android-33}"

AAPT2="${AAPT2:-$ANDROID_HOME/build-tools/$BT_VERSION/aapt2}"
D8_JAR="${D8_JAR:-$ANDROID_HOME/build-tools/$BT_VERSION/lib/d8.jar}"
ZIPALIGN="${ZIPALIGN:-$ANDROID_HOME/build-tools/$BT_VERSION/zipalign}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_HOME/platforms/$PLATFORM/android.jar}"
VER_CODE="${VER_CODE:-20}"
VER_NAME="${VER_NAME:-1.0.3}"

for f in "$JAVA_HOME/bin/javac" "$AAPT2" "$D8_JAR" "$ZIPALIGN" "$ANDROID_JAR"; do
  [ -e "$f" ] || { echo "缺少依赖: $f"; exit 1; }
done
[ -f "$ROOT/app/assets/quiz.db" ] || { echo "缺少 app/assets/quiz.db"; exit 1; }

# javac / d8 的 @argfile 按空格分词，构建目录必须无空格，故先复制到临时目录
WORK="$(mktemp -d /tmp/pohw-build.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
SRC="$WORK/src"
OUT="$WORK/out"
mkdir -p "$SRC" "$OUT/gen" "$OUT/classes" "$OUT/dex"
cp -R "$ROOT/app" "$SRC/app"

echo "[1/5] aapt2 compile"
"$AAPT2" compile --dir "$SRC/app/res" -o "$OUT/res.zip"

echo "[2/5] aapt2 link（生成 R.java，打包资源与 assets）"
"$AAPT2" link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$SRC/app/AndroidManifest.xml" \
  -A "$SRC/app/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 21 --target-sdk-version 33 \
  --version-code "$VER_CODE" --version-name "$VER_NAME" \
  "$OUT/res.zip"

echo "[3/5] javac"
find "$SRC/app/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVA_HOME/bin/javac" -source 8 -target 8 -encoding UTF-8 -nowarn \
  -classpath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/5] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$JAVA_HOME/bin/java" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --lib "$ANDROID_JAR" --min-api 21 --output "$OUT/dex" @"$OUT/classes.txt"

echo "[5/5] 合入 classes.dex + zipalign"
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"
(cd "$OUT" && zip -q -j base.apk classes.dex)
"$ZIPALIGN" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

mkdir -p "$ROOT/build/out"
cp "$OUT/aligned.apk" "$ROOT/build/out/aligned.apk"
echo "完成: $ROOT/build/out/aligned.apk"
