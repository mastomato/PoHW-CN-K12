# 构建指南

## 1. 环境

| 依赖 | 版本 / 用途 |
| --- | --- |
| JDK | 17（`javac` / `keytool` / `java`） |
| Android SDK | `platforms/android-34`（`android.jar`）、`build-tools/34.0.0`（`aapt2` / `d8` / `zipalign` / `apksigner` / `aapt`） |
| Python | 3.8+，仅题库脚本，零第三方依赖 |
| 其他 | `bash` / `zip` / `sha256sum` |

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
command -v aapt2 javac d8 zipalign apksigner   # 应全部能找到
```

不需要 Android Studio、Gradle、Kotlin 或任何 Maven 依赖。

## 2. 完整构建

```bash
bash tests/run.sh                                   # 204 个 JVM 单测（8 组）
python3 tools/quizbank/verify.py app/assets/quiz.db  # 题库独立校验
bash app/build.sh                                    # 产出 dist/PoHW-v1.0.1.apk
```

`app/build.sh` 的九个阶段：

| 阶段 | 动作 |
| --- | --- |
| 0 | 静态守卫：版本一致性、禁止写死 `v4.1 控制台`、禁止 DeviceAdmin / 防卸载 API、禁止第三方题包残留、`allowBackup=false` |
| 1 | 题库校验：表结构、每题选项合法性、9 年级 × 3 科 × 40 次 + 全学科 360 次随机抽题、答案重算、篡改检测 |
| 2 | JVM 单测 8 组 204 项：PinGate / QuizLayout / StopGuard / StopState / GradeScope / FeatureV6 / BehaviorV601 / SubjectWeights |
| 3 | `aapt2 compile --dir res` |
| 4 | `aapt2 link`（生成 `R.java`，打包 `assets/`） |
| 5 | `javac -source 8 -target 8 -classpath android.jar` |
| 6 | `d8 --min-api 21` |
| 7 | `zip` 塞入 `classes.dex` + `zipalign -f 4` |
| 8 | `apksigner sign`（debug keystore 自动生成在 `.build/`，已 gitignore）+ `apksigner verify` |
| 9 | 校验 `versionName`、`assets/quiz.db(.version)`，打包 `dist/PoHW-src.zip` |

> **顺序注意**：必须先 zipalign 再签名，APK 内 `assets/*.db` 为已压缩资源，
> 不要再做二次对齐或重签名之外的修改。

## 3. 版本管理

`app/AndroidManifest.xml` 的 `android:versionCode` / `android:versionName`
必须与 `app/build.sh` 顶部的 `VERSION_CODE` / `VERSION_NAME` 一致（脚本会 grep 校验）。
首页版本文字由 `MainActivity.readVersion()` 通过 `PackageManager.getPackageInfo()`
动态读取，**禁止写死版本号**。

## 4. 重新生成题库

```bash
git clone https://github.com/RedSamerai/learning-content /path/to/learning-content
python3 tools/quizbank/build_bank.py \
    --lc /path/to/learning-content \
    --out app/assets/quiz.db \
    --stats docs/BANK_STATS.md \
    --seed 20261104
python3 tools/quizbank/verify.py app/assets/quiz.db
```

上游原始仓库（约 20 MB）不要提交进本仓库，`.gitignore` 已排除 `/data/` 与 `/third_party/`。
`--seed` 固定时生成结果完全可复现。

## 5. 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| `MISSING TOOL: apksigner` | `JAVA_HOME` / `ANDROID_HOME` 没设，或 build-tools 版本不是 34.0.0 |
| `MISSING android.jar` | 缺 `platforms/android-34`，用 sdkmanager 安装 |
| `apksigner` 报找不到 java | `apksigner` 依赖 `JAVA_HOME/bin/java` |
| `aapt2 link` 报资源错误 | `res/values/strings.xml`、`res/xml/network_security_config.xml` 是否齐全 |
| 覆盖安装后仍要求输入旧密码 | SharedPreferences 被保留；`adb shell pm clear com.jager.tvquiz` 重置 |
| 遮罩卡住卸载确认框 | `adb shell am force-stop com.jager.tvquiz`，或先关守护 |
| 题库升级不生效 | 确认 `app/assets/quiz.db.version` 与库内 `meta.version` 一致 |

## 6. 为什么不用 Gradle

这个工程只有一个模块、28 个 Java 文件、零第三方依赖，用 Gradle 需要 AGP、
Gradle Wrapper、联网拉依赖和一套额外的构建缓存。命令行链路把每一步都显式暴露出来，
便于审查、便于在离线环境复现，也让「APK 里到底装了什么」一目了然。

## 7. v1.0.1 构建门禁

`app/build.sh` 的 `[0/9] static guards` 改为**语义符号检查**，不再依赖精确行文本
（旧版把 `FLAG_NOT_FOCUSABLE` 清零当成「窗口焦点已保证」是错误理解）。当前检查：
`QuizFlow` / `flow.submit`（判题状态机确实被推进）、`KeyRouter.decide`（按键自主路由）、
`new OverlayQuizView`（每次弹题不复用 View）、`requestContinue` + `onWindowFocusChanged`
（不在按键栈内摘窗口、失焦有回调）、`fgReady = enterForeground()`（FGS 契约在入口覆盖全分支）、
`updateViewLayout` / `rebuildOverlay`（焦点重抢状态机）、`issueStopToken` /
`consumeStopToken` / `EXTRA_STOP_TOKEN`（一次性 STOP 令牌），并**禁止**残留
`EXTRA_STOP_VERIFIED`。另外确认 `QuickBootReceiver` 已注册、
`KeyRouter` / `QuizFlow` / `BehaviorV601Test` 真的被编译与执行。

`[0/9]` 还会执行 `python3 tests/layout_contract.py`：**114 条**源码级布局与发布契约断言，
覆盖首页 3×2 网格、设置页单列卡片流、等宽 weight 布局、四向 DPAD 焦点链、
滚动容器参数（`setFillViewport` / `Ui.fill(1)` / wrap-content 子容器）、
四槽学科权重、双路径 SQL、题目学科标签反推以及证书门禁本身；复审加固后还钉死
OTHER 池路由字面量、SQL 双份参数绑定、池保持兜底常量、`hasOtherQuestions()`、
空池安全 `MAX(1, COUNT(*))` OFFSET、注释诱饵防护与 mutation harness 的存在性。
调用次数类断言基于带 `/* */` 块注释状态机的去注释代码行，行注释与块注释掉调用都会被拦下；
负向门禁同时禁止 `wireColumns` / `addColumn` 三栏助手与 `gradeWide` / 15% / `int[3]` 旧抽题残留。
另执行 `python3 tests/bank_distribution_check.py`：先 `strip_comments()` 去注释，再从
`QuizDb.java` 的 `POOL_*` 命名常量提取与 Java 同源的真实 SQL，校验双份绑定与三层兜底，
对内置题库做 9 年级 × 4 池 × 60 次池隔离、两次 5000 次配比分布、全学科关闭 0 题校验，
以及 `FALLBACK_POOL_OK`（模拟三年级英语池 / 其他池为空，兜底仍保持学科约束、不串池）。
`tests/mutation_suite.py` 落仓库可复核：**29 组负向变异**（含注释诱饵、破坏绑定、
去掉池保持兜底、不带发布密钥构建、恢复 gradeWide SQL）全部被契约 / 运行时分布 / 构建门禁拦下，
末尾自动恢复源码哈希并用发布密钥重建 `dist/`。
注意 `grep -q 'new int[Subjects.COUNT]'` 会把方括号当正则字符类；必须用 `grep -qF`。
`[0/9]` 另钉死本轮新增结构：`ROW_GAP_DP`（设置页行间距）、`LaunchPolicy.shouldAutoGuard`
（首页冷启动自动守护）、Manifest 里的 `DonateActivity`，以及两张收款码 PNG 与
`tools/branding/make_icons.py` 必须存在。图标 / TV banner / 状态栏小图标由该脚本用 PIL
确定性生成（5 档 launcher 密度 + 640×360 banner），改设计只需改脚本后重跑，无需设计工具。
`[9/9]` 打包源码前会先删除旧的 `PoHW-src.zip`（`zip -r` 对已存在归档只做增量更新，
旧条目不会消失），并硬性拒绝 `__pycache__` / `*.pyc` 混进交付源码包。

`[2/9]` 现在跑 8 组共 **204** 个 JVM 单测（`BehaviorV601Test` 51 项与 `SubjectWeightsTest` 20 项，
覆盖按键 ACTION_DOWN/UP 与 repeat、2x2 高亮邻接、答题状态机、令牌 TTL/重放/进程重启）；
`[6/9]` 仍检查 dex 中含「错题库」字符串。

覆盖安装保持签名一致：**`[8/9]` 会用 `apksigner verify --print-certs` 读取成品 APK 的
证书 SHA-256，与钉死的 `76eb6063b574…26172` 比对，不一致直接 `exit 1`**。
`apksigner verify --verbose` 只证明签名有效、不证明签名者相同，所以这层门禁不能省。
仅本地实验构建可显式 `POHW_ALLOW_DEV_SIGN=1` 跳过并接受醒目警告；
正常发布必须显式指定与已发布版本相同的 keystore：

```bash
JAVA_HOME=/opt/jdk17 ANDROID_HOME=/path/to/sdk POHW_KEYSTORE=/path/to/debug.keystore POHW_KEYSTORE_PASS=android bash app/build.sh
```

不指定 `POHW_KEYSTORE` 时脚本会在 `.build/` 随机生成密钥，
自行构建的 APK 与官方 Release 签名不同，覆盖安装前需先卸载。
