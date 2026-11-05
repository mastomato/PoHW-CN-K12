#!/usr/bin/env bash
# PoHW / Android TV 答题守护 —— 零 Gradle 构建链: aapt2 -> javac -> d8 -> zipalign -> apksigner
# Requirements: JDK 17 + Android SDK (platform android-34 / build-tools 34.0.0)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
BUILD="$ROOT/.build"
DIST="$ROOT/dist"
VERSION_NAME="1.0.1"
VERSION_CODE="17"
APK_NAME="PoHW-v${VERSION_NAME}.apk"

: "${JAVA_HOME:=/opt/jdk17}"
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
export JAVA_HOME ANDROID_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/build-tools/34.0.0:$PATH"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"

for tool in aapt2 javac d8 zipalign apksigner keytool; do
  command -v "$tool" >/dev/null || { echo "MISSING TOOL: $tool"; exit 1; }
done
[ -f "$ANDROID_JAR" ] || { echo "MISSING android.jar"; exit 1; }
[ -f "$APP/assets/quiz.db" ] || { echo "MISSING assets/quiz.db"; exit 1; }
[ -f "$APP/assets/quiz.db.version" ] || { echo "MISSING assets/quiz.db.version"; exit 1; }
[ -f "$APP/res/drawable-hdpi/ic_launcher.png" ] || { echo "MISSING launcher icon"; exit 1; }
mkdir -p "$BUILD" "$DIST"
cd "$ROOT"

echo "[0/9] static guards"
grep -q "versionCode=\"$VERSION_CODE\"" "$APP/AndroidManifest.xml"
grep -q "versionName=\"$VERSION_NAME\"" "$APP/AndroidManifest.xml"
grep -q 'getPackageInfo' "$APP/src/com/jager/tvquiz/MainActivity.java"
grep -q 'GuardPolicy.allowStop' "$APP/src/com/jager/tvquiz/QuizOverlayService.java"
grep -q 'setAllSubjects' "$APP/src/com/jager/tvquiz/QuizEngine.java"
# v1.0.0 semantic gates: assert behaviour symbols, not exact line spellings.
# (Old gates assumed clearing FLAG_NOT_FOCUSABLE guarantees the WMS focused
#  window; it does not. The focus state machine below is the real safeguard.)
SVC="$APP/src/com/jager/tvquiz/QuizOverlayService.java"
VIEW="$APP/src/com/jager/tvquiz/OverlayQuizView.java"
grep -q 'QuizFlow' "$SVC"                       # 判题状态机由服务推进
grep -q 'flow.submit' "$SVC"
grep -q 'KeyRouter.decide' "$VIEW"              # 遥控器按键自主路由
grep -q 'WrongBook.recordAsync' "$SVC"
grep -q 'MediaPauseController' "$SVC"
grep -q 'BootPolicy.shouldStartGuard' "$APP/src/com/jager/tvquiz/BootReceiver.java"
grep -q 'new OverlayQuizView' "$SVC"             # 每次弹题不复用 View
grep -q 'setFocusable(false)' "$VIEW"            # 子按钮不参与焦点树
grep -q 'WrongBookActivity' "$APP/AndroidManifest.xml"
# v1.0.1 UI consolidation: one toggle, subject rows, bank entry only in settings
MAIN="$APP/src/com/jager/tvquiz/MainActivity.java"
SET="$APP/src/com/jager/tvquiz/SettingsActivity.java"
grep -q 'GuardState.toggleColor' "$MAIN"
grep -q 'GuardState.toggleLabel' "$MAIN"
grep -q 'toggleGuard()' "$MAIN"
grep -q 'addSubjectRow' "$SET"
grep -q 'wireSubjectRows' "$SET"
if grep -q '检查题库更新' "$MAIN"; then
  echo 'FORBIDDEN: bank-update entry must live in SettingsActivity only'; exit 1
fi
# v1.0.1 UI polish: 3x2 home grid + single-column card flow in settings.
# Behaviour symbols (not Chinese copy) keep the gates stable across wording tweaks.
grep -q 'GRID_COLS = 3' "$MAIN"
grep -q 'wireGrid()' "$MAIN"
[ "$(grep -cF 'new Button[GRID_COLS]' "$MAIN")" -eq 2 ] || {
  echo 'FORBIDDEN: home grid must be exactly 2 rows'; exit 1; }
grep -q 'wireVerticalFocus' "$SET"
grep -q 'addCard' "$SET"
grep -q 'new ScrollView(this)' "$SET"
if grep -q -e 'wireColumns' -e 'addColumn' "$SET"; then
  echo 'FORBIDDEN: settings must stay a single-column card flow'; exit 1
fi
# 布局契约（3x2 网格 / 单列卡片流 / DPAD 焦点链）由独立脚本逐条断言，
# 纯 JVM 单测无法实例化 android.view.*，这里守住结构，javac 再守住编译。
# v1.0.1 家长设置收口：行间距、启动策略与打赏入口的行为符号门禁。
grep -q "ROW_GAP_DP" "$SET"
grep -q "DonateActivity" "$APP/AndroidManifest.xml"
grep -q "LaunchPolicy.shouldAutoGuard" "$MAIN"
[ -f "$APP/res/drawable-nodpi/donate_wechat.png" ] || { echo "MISSING wechat QR"; exit 1; }
[ -f "$APP/res/drawable-nodpi/donate_alipay.png" ] || { echo "MISSING alipay QR"; exit 1; }
[ -f "$ROOT/tools/branding/make_icons.py" ] || { echo "MISSING icon generator"; exit 1; }
python3 tests/layout_contract.py
# 运行时回归：用与 App 相同的两条 SQL 实跑内置题库，验证配比与标签。
python3 tests/bank_distribution_check.py
# 方案 B：配比必须真的进入抽题链路，禁止退回 gradeWide / 15% / 旧标签路径。
W="$APP/src/com/jager/tvquiz/SubjectWeights.java"
DB="$APP/src/com/jager/tvquiz/QuizDb.java"
ENG="$APP/src/com/jager/tvquiz/QuizEngine.java"
grep -qF 'new int[Subjects.COUNT]' "$W"
grep -q 'SubjectWeights' "$ENG"
grep -q 'st.weight(Subjects.OTHER)' "$SVC"
grep -q 'NOT IN (' "$DB"
grep -q 'subjectFromBank' "$DB"
# 钉死最易回归的三处：OTHER 路由、参数绑定两次、保持学科约束的兜底。
grep -qF 'boolean other = s == Subjects.OTHER && allSubjects;' "$DB"
grep -qF 'new String[args.length * 2]' "$DB"
grep -qF 'pick(poolWhere, poolArgs)' "$DB"
grep -qF 'db.hasOtherQuestions()' "$ENG"
# 负向禁词只看代码行，注释里提到旧实现不应误拦构建。
if grep -v -e '^[[:space:]]*//' -e '^[[:space:]]*\*' -e '^[[:space:]]*/\*' "$ENG" "$DB" \
    | grep -q -e 'gradeWide' -e 'nextInt(100) < 15' -e 'fromBank(subject,'; then
  echo 'FORBIDDEN: legacy gradeWide / 15 percent / wrong subject label'; exit 1
fi
# P0: FGS 契约覆盖所有分支（startForeground 必须在 onStartCommand 入口）
grep -q 'fgReady = enterForeground()' "$SVC"
grep -q 'startForeground(NOTIF_ID' "$SVC"
# P0: 窗口焦点状态机（失焦重抢 + 有限重试 + 一次窗口重建）
grep -q 'onQuizWindowFocus' "$SVC"
grep -q 'updateViewLayout' "$SVC"
grep -q 'rebuildOverlay' "$SVC"
grep -q 'onWindowFocusChanged' "$VIEW"
grep -q 'requestContinue' "$VIEW"               # 不在按键分发栈内同步摘窗口
# P1: 一次性 STOP 令牌，客户端 boolean 不再是授权依据
grep -q 'issueStopToken' "$SVC"
grep -q 'consumeStopToken' "$SVC"
grep -q 'EXTRA_STOP_TOKEN' "$SVC"
if grep -q 'EXTRA_STOP_VERIFIED' "$SVC" "$VIEW"; then
  echo 'FORBIDDEN: STOP verified boolean must not be trusted'; exit 1
fi
# P1: QUICKBOOT 与 BOOT_COMPLETED 拆成两个 receiver
grep -q 'QuickBootReceiver' "$APP/AndroidManifest.xml"
[ -f "$APP/src/com/jager/tvquiz/QuickBootReceiver.java" ] || {
  echo 'MISSING QuickBootReceiver.java'; exit 1; }
# 行为级回归必须真的被编译、真的被执行
grep -q 'BehaviorV601Test' tests/run.sh
grep -q 'KeyRouter.java' tests/run.sh
grep -q 'QuizFlow.java' tests/run.sh
for f in Hashes Subjects AnswerPolicy BootPolicy BootSettings MediaPauseController          WrongBook WrongBookActivity OverlayQuizView KeyRouter QuizFlow; do
  [ -f "$APP/src/com/jager/tvquiz/$f.java" ] || { echo "MISSING source: $f.java"; exit 1; }
done
if grep -rq -E 'v4[.]1 控制台|DeviceAdmin|startLockTask|DELETE_PACKAGES|pack_hh_|huohua|HH_API_KEY' "$APP/src"; then
  echo 'FORBIDDEN legacy / third-party content in sources'; exit 1
fi
if ls "$APP/assets"/pack_hh_*.json >/dev/null 2>&1; then
  echo 'FORBIDDEN third-party question packs in assets'; exit 1
fi
if [ -f "$APP/assets/index.json" ]; then
  echo 'FORBIDDEN third-party bank index in assets'; exit 1
fi
if grep -q 'allowBackup="true"' "$APP/AndroidManifest.xml"; then
  echo 'allowBackup must stay false'; exit 1
fi

echo "[1/9] verify built-in question bank"
python3 tools/quizbank/verify.py "$APP/assets/quiz.db"

echo "[2/9] JVM unit tests (204 assertions incl. behaviour regressions)"
bash tests/run.sh

STAMP="$(date +%s)"
for d in gen classes dexout out; do
  if [ -d "$BUILD/$d" ]; then mv "$BUILD/$d" "$BUILD/$d.old.$STAMP"; fi
done
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dexout" "$BUILD/out"

echo "[3/9] aapt2 compile"
aapt2 compile --dir "$APP/res" -o "$BUILD/out/res.zip"

echo "[4/9] aapt2 link"
aapt2 link -o "$BUILD/out/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  -A "$APP/assets" \
  --java "$BUILD/gen" \
  --min-sdk-version 21 --target-sdk-version 33 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$BUILD/out/res.zip"

echo "[5/9] javac"
find "$APP/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
grep -q 'QuizDb.java' "$BUILD/sources.txt"
grep -q 'SubjectWeights.java' "$BUILD/sources.txt"
grep -q 'PinVerifyView.java' "$BUILD/sources.txt"
grep -q 'WrongBook.java' "$BUILD/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -nowarn \
  -classpath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "[6/9] d8"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
d8 --lib "$ANDROID_JAR" --min-api 21 --output "$BUILD/dexout" @"$BUILD/classes.txt"
if grep -aq 'v4.1 控制台' "$BUILD/dexout/classes.dex"; then
  echo 'OLD HARDCODED TITLE IN DEX'; exit 1
fi
grep -aq '应用版本' "$BUILD/dexout/classes.dex"
grep -aq '错题库' "$BUILD/dexout/classes.dex"

echo "[7/9] package + zipalign"
cp "$BUILD/dexout/classes.dex" "$BUILD/out/classes.dex"
(cd "$BUILD/out" && zip -q -j base.apk classes.dex)
zipalign -f 4 "$BUILD/out/base.apk" "$BUILD/out/aligned.apk"

echo "[8/9] sign + verify"
# Dev builds generate a random local signing key (gitignored), so a fresh clone
# never ships a hardcoded private key. IMPORTANT for side-load upgrades: point
# POHW_KEYSTORE + POHW_KEYSTORE_PASS at the SAME keystore as the previous
# release, otherwise Android refuses to install over the old APK. Release
# artifacts keep a stable certificate only because that keystore is passed
# explicitly; the default (no env vars) intentionally does NOT guarantee it.
KS="${POHW_KEYSTORE:-$BUILD/release.keystore}"
PASS_FILE="$BUILD/keystore.pass"
if [ -n "${POHW_KEYSTORE_PASS:-}" ]; then
  KS_PASS="$POHW_KEYSTORE_PASS"
elif [ -f "$PASS_FILE" ]; then
  KS_PASS="$(cat "$PASS_FILE")"
else
  KS_PASS="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
  printf '%s' "$KS_PASS" > "$PASS_FILE"
fi
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias pohw -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=PoHW, OU=OpenSource, O=PoHW, L=Shenzhen, ST=Guangdong, C=CN" \
    >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$DIST/$APK_NAME" "$BUILD/out/aligned.apk"
apksigner verify --verbose "$DIST/$APK_NAME"

# 发布证书连续性门禁：apksigner verify 只证明签名有效，不证明签名者相同。
# 指纹不一致时 adb install -r 会因 signature mismatch 拒绝覆盖安装，
# 因此默认强制比对已发布 v1.0.0 / v1.0.1 的证书；仅本地实验构建可显式跳过。
RELEASE_CERT_SHA256="76eb6063b574d6df1cd52ac490d82185793bf48abe0185687f4d977e49626172"
ACTUAL_CERT_SHA256="$(apksigner verify --print-certs "$DIST/$APK_NAME"   | sed -n 's/.*SHA-256 digest: //p' | tr -d '[:space:]')"
if [ "${POHW_ALLOW_DEV_SIGN:-0}" = "1" ]; then
  echo "WARNING: dev-sign override; certificate NOT checked: $ACTUAL_CERT_SHA256"
elif [ "$ACTUAL_CERT_SHA256" != "$RELEASE_CERT_SHA256" ]; then
  echo "FORBIDDEN: APK certificate $ACTUAL_CERT_SHA256 != release cert $RELEASE_CERT_SHA256"
  echo "Point POHW_KEYSTORE at the release keystore, or use POHW_ALLOW_DEV_SIGN=1 for a local dev build."
  exit 1
fi

echo "[9/9] artifact checks"
aapt dump badging "$DIST/$APK_NAME" 2>/dev/null | grep -q "versionName='$VERSION_NAME'"
aapt dump badging "$DIST/$APK_NAME" 2>/dev/null | grep -q "versionCode='$VERSION_CODE'"
unzip -l "$DIST/$APK_NAME" | grep -q 'assets/quiz.db'
unzip -l "$DIST/$APK_NAME" | grep -q 'assets/quiz.db.version'
# zip -r 对已存在的归档只做增量更新，旧条目不会消失；先删旧包再重建。
rm -f "$DIST/PoHW-src.zip"
(cd "$ROOT" && zip -q -r "$DIST/PoHW-src.zip" \
  README.md LICENSE .gitignore CHANGELOG.md \
  app/AndroidManifest.xml app/build.sh app/src app/res app/assets \
  tests docs tools -x '*/__pycache__/*' '*.pyc')

# 源码包只允许源码与文档：Python 字节码和缓存目录属于本机运行产物，不得外发。
if unzip -l "$DIST/PoHW-src.zip" | grep -qE '__pycache__|\.pyc$'; then
  echo "FORBIDDEN: python bytecode leaked into the source archive"
  exit 1
fi

echo "=== RESULT ==="
ls -lh "$DIST/$APK_NAME" "$DIST/PoHW-src.zip"
sha256sum "$DIST/$APK_NAME"
echo "BUILD_OK version=$VERSION_NAME code=$VERSION_CODE"
