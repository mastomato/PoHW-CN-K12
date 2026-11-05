# 本地构建说明（无 Gradle）

上游 `app/build.sh` 依赖 JDK 17 + Android SDK（build-tools 34 / platform-34）。本仓库用同一套工具链，但把流程拆成可直接复现的 `tools/local/build_apk.sh`。

## 依赖

| 组件 | 版本 | 用途 |
|---|---|---|
| JDK | 17 | javac / d8 / apksigner |
| Android build-tools | 34.0.0 | aapt2、d8、zipalign、apksigner |
| Android platform | android-33 | 编译用的 `android.jar` |

国内下载 `build-tools` / `platform` 建议走镜像（`dl.google.com` 直链经常 404）：

```bash
BASE=https://mirrors.cloud.tencent.com/AndroidSDK
curl -LO $BASE/build-tools_r34-macosx.zip
curl -LO $BASE/platform-33_r02.zip      # 解压后目录名是 android-13（Android 13 = API 33）
```

## 构建

```bash
export JAVA_HOME=/path/to/jdk-17
bash tools/local/build_apk.sh
```

脚本做的事：`aapt2 compile` → `aapt2 link`（生成 `R.java`，源码确实引用 R）→ `javac` → `d8` → 合入 `classes.dex` → `zipalign`。

签名需要自备 keystore：

```bash
keytool -genkeypair -keystore my.keystore -alias pohw -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=PoHW, O=Local"
apksigner sign --ks my.keystore --out dist/PoHW.apk build/out/aligned.apk
```

## 踩过的坑

1. **构建目录不能带空格**。`javac` / `d8` 的 `@argfile` 按空格分词，路径里有空格会把参数拆坏。脚本会先 `cp` 到 `/tmp` 再编译。
2. **必须重新生成 `R.java`**。源码里有 `R.string.guard_channel`、`R.drawable.ic_stat_quiz` 等引用，只重编 Java 不 link 资源会编译失败。
3. **`tests/bank_distribution_check.py` 会用正则从 `QuizDb.java` 里提取 SQL 常量**（`NAME\s*=\s*"([^"]+)";`）。改抽题逻辑时，`POOL_CORE_GRADE` 等四个常量必须保持单字符串字面量，拼成多段会测试失败。
4. **题库版本戳必须同步**。`app/assets/quiz.db.version` 的内容要与库内 `meta.version` 完全一致，否则 App 不会重新拷贝题库，改了等于没改。
5. **改 APK 内容必须重新签名**，且签名与原版不同，覆盖安装会因 `signature mismatch` 失败，只能先卸载。用同一把 keystore 连续升级则无此问题。

## 题库重建

`tools/local/build_bank_merge.py` 需要两份输入（体积原因未入库）：

- 上游原始题库 `app/assets/quiz.db`（可从上游 release 的 APK 里解出 `assets/quiz.db`）
- cn-math-taxonomy 的 `data/questions.json` 与 `data/topics.json`

```bash
python3 tools/local/build_bank_merge.py     # 产出 quiz_new.db
cp quiz_new.db app/assets/quiz.db
sqlite3 quiz_new.db "SELECT v FROM meta WHERE k='version';" > app/assets/quiz.db.version
```

`tools/local/olympiad_gen.py` 由 `build_bank_merge.py` 在 `__main__` 里调用，单独运行没有意义。
