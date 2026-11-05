# PoHW — Proof of Homework

> **答对一题，续租 30 分钟。**

一个装在自己电视上的反沉迷小实验：开启守护后，前台服务会按设定周期在**任意应用之上**弹出全屏答题页，答对才能继续看电视；同一题答错 2 次自动换题并记入错题库。

**PoHW** 是 Proof of Work 的一个玩笑 —— 看电视的通行权不是靠算力，而是靠周期性交一次「作业量证明」来续租。租约到期，重新出题。

本项目是**个人非商业**的 Android TV 实验，只发布 APK，不上架任何应用商店。

---

## 它能做什么

| 能力 | 说明 |
| --- | --- |
| 跨应用全屏答题 | `TYPE_APPLICATION_OVERLAY` 全屏窗口，切到视频 App / 桌面后照样弹出 |
| 完全离线题库 | 内置 SQLite，本地 `ORDER BY RANDOM()` 抽题，**默认一次网络请求都不发** |
| 全量 K12 纲目 | 4,319 个知识点 / 19 个学科 / 21 个年级段，幼儿园到九年级全量导入 |
| 年级与学科 | 1-9 年级、**分学科教材版本**（数学/语文/英语/其他各自可选）、出题频率、数/语/英配比，以及「全学科」出题开关 |
| 家长密码 | PBKDF2 + 随机 salt，保护设置页；**关闭守护也必须先验证**，服务侧二次校验 |
| 就地验证 | 点合并后的守护开关（运行中显示「关闭守护」）不跳页、不退出，只叠加验证层并切换按钮颜色/文字 |
| 答题暂停视频 | 弹题时申请短暂音频焦点并派发媒体暂停键，答对解锁后归还焦点并尝试恢复（best-effort） |
| 开机自启 | 「开机自启」默认关闭（家长自行开启）+「启动即守护」默认开启（打开 APP 即自动守护）两个独立开关，并提供厂商自启动权限设置入口 |
| 错题库 | 同一题答错 2 次自动换题；错题按题面去重记入本地 SQLite，控制台可翻页查看/删除/清空 |
| 遥控器答题 | 全屏遮罩自主路由方向键/确定键/数字键 1-4，不依赖窗口 View 焦点；服务侧还有窗口焦点重抢状态机，反复弹题/失焦后可恢复 |
| 可选在线源 | 预留 `index.json + packs/*.json` 同步协议，需家长自己填地址才启用 |

## 它不能做什么

- 拦不住 **Home 键**、系统「强制停止」、卸载、清除数据 —— 第三方应用没有这些能力。
- 没有 Device Admin / Device Owner / 锁屏任务 / 防卸载；本仓库明确禁止这类 API。
- Android 12+ 视频 App 可用 `HIDE_OVERLAY_WINDOWS` 隐藏非系统浮层，各家 ROM 与小米电视的实际表现**需要上机实测**。
- 题目是**由知识点纲目参数化生成**的，不是教材同步真题，也不是教辅题库。
- **暂停视频是 best-effort**：Android 没有「强制暂停第三方播放器」的公开 API，只能请求音频焦点 + 派发媒体暂停键；不保证所有视频 App 响应。
- 内置离线题库按课标纲目组织、**不含出版社版本字段**，分学科教材版本用于在线题包筛选、题面标注与错题标记，不会改变离线抽题结果。
- 打赏页只显示两张静态收款码，**不发起支付、不联网**，APP 不接触金额与账号；扫码与付款都在家长手机上完成。
- **窗口焦点重抢也是 best-effort**：Android 没有强制夺取 focused window 的公开 API。通知栏/系统对话框抢走焦点时，服务会有限次重下发窗口参数并重建一次窗口；极端情况下遥控器可能仍无法作答，此时遮罩保留（不自动放行），触摸/鼠标点击仍可答题。

---

## 快速开始

```bash
# 1) 装 APK（自己构建产物在 dist/，或到本仓库 Releases 页面下载）
adb install -r dist/PoHW-v1.0.1.apk

# 2) 打开 APP：点「授权悬浮窗」打开系统开关并允许
# 3) 进「家长设置」：首次进入会要求初始化 4-8 位数字密码
# 4) 设好年级 / 频率 / 学科配比，返回首页点「开启守护」
# 5) 按 Home 切到任意视频 App，到点会弹出全屏答题页
```

忘记密码：`adb shell pm clear com.jager.tvquiz`（会清空全部设置与统计）。
遮罩卡住卸载流程：`adb shell am force-stop com.jager.tvquiz`。

---

## 内置题库

题库由 `tools/quizbank/build_bank.py` 从开源仓库
[RedSamerai/learning-content](https://github.com/RedSamerai/learning-content) **全量**生成，
随 APK 放在 `app/assets/quiz.db`，首次启动原子复制到应用 `databases/` 目录后只读。

| 指标 | 数值 |
| --- | ---: |
| 源文件 | 4,026 个 JSON（两份总图谱 + `output/` 全量逐知识点文件） |
| 去重后知识点 | **4,319** 条（`knowledge` 表，原始字段完整保留） |
| 学科 / 年级段 | **19** 个 / **21** 个 |
| 四选一题目 | **5,129** 道（4,319 纲目题 + 810 程序出题） |
| 数据库大小 | 约 4.5 MB（APK 内压缩后约 1 MB） |

> 上游仓库只有知识点纲目，**没有题目、选项和答案**。本项目不引用任何第三方真题文本；
> 所有题目由脚本参数化生成，正确答案由纲目数据或程序计算确定，并通过独立校验脚本
> 复现 App 端的合法性检查、随机抽题压测、答案重算与篡改检测。详见
> [`docs/QUESTION_BANK.md`](docs/QUESTION_BANK.md) 与 [`docs/BANK_STATS.md`](docs/BANK_STATS.md)。

抽题链路（每一级都会兜底，保证「一定能出题」）：

```
内置 SQLite 随机抽题  ->  在线源缓存题目  ->  QuizEngine 参数化生成器
```

### 在线题库源（可选，默认关闭）

火花 K12 这类需要用户自己申请账号 / 配额 / 密钥的在线题源，本项目**不内置、不代管、不打包**。
App 只在「家长设置」里预留了一个 URL 输入框与同步协议：

```
GET <base>/index.json        { schema, version, updatedAt, packs:[{id,grade,edition,subject,url,sha256,count}] }
GET <base>/packs/<file>      { schema, id, grade, edition, subject, questions:[{topic,text,options[4],answerIndex,explain}] }
```

- 地址为空时：App 完全离线、不发起任何请求；「家长设置」里的「检查题库更新」会直接提示当前使用内置题库。
- 填写地址后：按设定周期比对版本、分块下载、SHA-256 校验、原子落盘缓存；失败一律沿用旧缓存。
- 出于安全，默认禁止明文 HTTP（仅本机调试地址在 `network_security_config.xml` 白名单内）。
- APK 里**没有**任何 access-key / token / 付费题数据。

---

## 家长设置

界面为**单列卡片流**：一个居中窄栏纵向滚动，按出题基础 / 学科设置 / 开机与播放 /
错题库 / 在线题库源分成 5 张卡片，底部固定「修改家长密码 / 保存并返回」。
遥控器上下按行移动、学科行左右切「教材版本 ↔ 配比」，不再需要跨栏跳转。

| 项 | 取值 |
| --- | --- |
| 所在年级 | 小学 1-6 / 初中 1-3（内部 1-9） |
| 学科设置（合并行） | 数学 / 语文 / 英语 / 其他学科**各一行**：左按钮切教材版本（通用(课标)、人教版、部编版、北师大版、苏教版、外研版、译林版、沪教版），右按钮切题目配比 0-5（0 表示该科不出题）；默认 5:3:2:2，其他学科配比仅在「出题范围 = 全学科」时生效 |
| 开机自启 | 开机自动启动（默认开，兼容旧版）、开机自动开启守护（默认关） + 厂商自启动设置入口 |
| 答题播放控制 | 答题时暂停视频（默认开）、答对后尝试恢复播放（默认开） |
| 答案策略 | 同一题累计答错 2 次自动换题，原题记入错题库 |
| 出题频率 | 15 秒 / 30 秒（演示）、1 / 5 / 10 / 20 / 30 分钟 |
| 出题范围 | 仅数/语/英（默认，按 5:3:2 配比）或**全学科**（四槽 5:3:2:2，其他学科约 16.7%，含物理、化学、历史、地理等） |
| 自动更新 | 每 1 / 6 / 12 / 24 小时（仅在配置了在线源时生效） |
| 家长密码 | 4-8 位数字，PBKDF2（60,000 次迭代）+ 16 字节随机 salt，同步落盘并回读校验 |

---

## 构建

零 Gradle：直接用 Android SDK 命令行工具 `aapt2 -> javac -> d8 -> zipalign -> apksigner`。
需要 JDK 17、Android SDK platform `android-34`、build-tools `34.0.0`。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
bash tests/run.sh          # 204 个 JVM 单测（8 组，含启动策略与四槽权重回归）
python3 tools/quizbank/verify.py app/assets/quiz.db
bash app/build.sh          # -> dist/PoHW-v1.0.1.apk + dist/PoHW-src.zip
```

`app/build.sh` 会做静态守卫（禁止写死版本号、防卸载 API、第三方题包残留）、
校验题库版本戳、跑单测、签名并校验 v1/v2/v3 签名与 `assets/quiz.db`。

签名密钥会在 `.build/` 里**随机生成并 gitignore**；正式分发请用
`POHW_KEYSTORE` / `POHW_KEYSTORE_PASS` 指向自己的 keystore，
避免任何人都能用同一把公开密钥重签 APK。
详细步骤与常见报错见 [`docs/BUILD.md`](docs/BUILD.md)。

### 重新生成题库

```bash
git clone https://github.com/RedSamerai/learning-content /path/to/learning-content
python3 tools/quizbank/build_bank.py \
    --lc /path/to/learning-content \
    --out app/assets/quiz.db \
    --stats docs/BANK_STATS.md \
    --seed 20261104
python3 tools/quizbank/verify.py app/assets/quiz.db
```

`--seed` 固定后结果可复现；脚本会同时写出 `app/assets/quiz.db.version`，
App 用它判断内置库是否需要升级替换。

---

## 权限

| 权限 | 用途 | 必需 |
| --- | --- | --- |
| `SYSTEM_ALERT_WINDOW` | 在其他应用上层弹出全屏答题页 | 是（需手动授权） |
| `FOREGROUND_SERVICE` | 常驻计时服务 | 是（Android 8+） |
| `POST_NOTIFICATIONS` | Android 13+ 显示前台服务通知 | 运行时申请，可拒绝 |
| `RECEIVE_BOOT_COMPLETED` | 开机恢复守护 | 可选 |
| `INTERNET` | **仅**可选在线题库源 | 默认不使用 |

清单里 `allowBackup=false`（避免家长密码哈希随备份导出）、
`usesCleartextTraffic=false` + 网络安全配置（默认禁止明文 HTTP）、
`exported` 最小化，服务与设置页均不对外部应用开放。

更多组件与安全边界见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

---

## 项目结构

```
PoHW/
├── README.md · LICENSE · CHANGELOG.md · .gitignore
├── app/
│   ├── AndroidManifest.xml
│   ├── build.sh                 # 零 Gradle 构建链
│   ├── src/com/jager/tvquiz/    # 30 个 Java 源文件
│   ├── res/                     # 5 档图标 / TV banner / 收款码 / strings / network_security_config
│   └── assets/quiz.db(.version) # 内置离线题库
├── tests/
│   ├── run.sh                   # JVM 单测入口
│   └── src/                     # 8 组测试 + Android 轻量 stub
├── tools/branding/make_icons.py  # 可复现图标 / TV banner 生成
├── tools/quizbank/
│   ├── build_bank.py            # 全量纲目 -> SQLite
│   └── verify.py                # 独立校验
└── docs/
    ├── BUILD.md · ARCHITECTURE.md · QUESTION_BANK.md · BANK_STATS.md
```

---

## v1.0.1 微调（versionCode 17）：题库说明归位 + 题库粒度调研

> 展示版本仍是 **v1.0.1**；`versionCode` 16 → 17，签名证书 `76eb6063…6172` 连续，可直接 `adb install -r` 覆盖安装。

- 家长设置「在线题库」卡片里，「默认完全离线：内置全量 K12 题库，不联网……」这条说明从打赏按钮之后
  移到「检查题库更新」按钮**正下方**：它解释的是在线源开关本身，不该和打赏说明挤在一起。
  新增一条源码级顺序契约（四点顺序断言），布局 / 发布契约 113 → **114 条**。
- 同时做了一次**只读调研**：内置 SQLite 题库能否按上下学期 / 教材单元限制出题范围。
  字段与题量实测、两个质量事实、A/B/C/D 四条可选路径写在
  `docs/QUESTION_BANK.md` 的「题库粒度与学习进度」一节。**本版没有改动任何抽题逻辑**，
  `QuizDb.random()` 仍只按 `grade + subject` 抽题，等方案确认后再实现。

**验证**：`LAYOUT_CONTRACT_OK total=114`、`JVM_TESTS_OK total=204`、
`BANK_DISTRIBUTION_OK`（含 `FALLBACK_POOL_OK`）、`MUTATION_SUITE_OK 29/29`、
`BUILD_OK version=1.0.1 code=17`，v1/v2/v3 签名与证书一致。

---

## v1.0.1 设置页体验 / 启动即守护 / 打赏与图标（versionCode 16，历史）

> 展示版本仍是 **v1.0.1**；`versionCode` 15 → 16，签名证书 `76eb6063…6172` 连续，可直接 `adb install -r` 覆盖安装。

按真机反馈做的 5 项改动：

| 项 | 改动 |
| --- | --- |
| **行间距** | 家长设置里所有取值行、按钮、说明文字统一走 `rowLp()`，上下各 6dp（`ROW_GAP_DP=12`），相邻控件 12dp 间距；卡片间距 4/10 → 6/14dp；卡片标题行改 `WRAP_CONTENT + 28dp minHeight`，系统大字模式不再裁切。 |
| **文案** | 「开启（开机即守护）」改为「开启（启动即守护）」；首页状态行同步改成「启动即守护」。 |
| **默认开关** | 「启动即守护」默认开、「暂停视频」/「恢复播放」默认开、「开机自启」默认关（家长显式选择并放行厂商自启动权限后才随开机拉起）。`SettingsStore` 键名保持不变，覆盖安装不丢家长旧设置。 |
| **启动即守护语义** | 新增纯 Java `LaunchPolicy`：APP **冷启动**（`savedInstanceState == null`）+ 开关开启 + 已授权悬浮窗 + 服务未运行，四个条件同时满足才自动拉起守护；从后台返回前台只走 `onResume`，不会把家长手动关掉的守护又打开。开机广播链路仍由 `BootPolicy` 管，两条链路共用同一个开关。 |
| **打赏作者** | 「检查题库更新」下方新增「打赏作者 · 请喝一杯咖啡」按钮与说明；点开 `DonateActivity` 全屏展示收款码，遥控器 ← / → 切换微信支付 / 支付宝，返回键退出。两张二维码是从家长提供的海报里裁出的白色卡片（保留二维码静区与署名），作为 `res/drawable-nodpi/*.png` 打包。本页**不含任何支付 SDK / 订单 / 网络请求**，扫码与付款都在手机上完成，APP 不接触金额与账号。 |
| **APP 图标** | 新增可复现的 `tools/branding/make_icons.py`（PIL，零第三方设计工具）：深蓝渐变圆角底 + 琥珀计时弧 + 屏幕里的青绿对勾 + 底座，呼应「答对一题，续租 30 分钟」；生成 5 档密度的 `ic_launcher.png`、640×360 的 `tv_banner.png` 与 2 档白色状态栏小图标。 |

**回归护栏**：布局 / 发布契约 88 → **113 条**；`tests/mutation_suite.py` 21 → **29 组**（新增行间距、四个默认值、启动策略、打赏页位置 / DPAD / 资源、品牌资源等 8 组）；JVM 单测 198 → **204**（`FeatureV6Test` 新增 6 项 `LaunchPolicy` 断言，并同步修正 F1–F4 默认值预期）。

**验证**：`LAYOUT_CONTRACT_OK total=113`、`JVM_TESTS_OK total=204`、
`BANK_DISTRIBUTION_OK`（含 `FALLBACK_POOL_OK`）、`MUTATION_SUITE_OK 29/29`、
`BUILD_OK version=1.0.1 code=16`，v1/v2/v3 签名与证书一致。

**真机待确认**：12dp 行间距在小米电视上的观感、打赏页二维码远距离可扫性、
左右键切渠道与「返回」焦点链、冷启动自动守护与 PIN 关闭守护的交互、
新图标 / TV banner 在 launcher 里的实际显示。

---

## v1.0.1 复审加固（versionCode 15，历史）

> 本节记录 code15 的独立复审加固；当前定版为 **code17**，见最上方章节。
> 签名证书 `76eb6063…6172` 连续；下一节记录的是同一 code 的初版实现。

独立只读复审（`weighting-reviewer`）结论为**有条件通过、无 P0**，但指出 4 个 P1 都落在
「门禁自己没守住、最易回归的代码点」，另有 6 个 P2。全部已在代码层面修掉：

| 复审项 | 修复 |
| --- | --- |
| P1-1 OTHER 池路由条件没有门禁，改坏后所有测试仍然全绿 | `build.sh`、布局契约、运行时分布校验三处同时钉死 `boolean other = s == Subjects.OTHER && allSubjects;`；变异 Q 覆盖 |
| P1-2 SQL 参数绑定次数没有断言；绑错时 `rawQuery` 抛异常被 catch 吞掉，静默退化成全库任意题 | 钉死 `new String[args.length * 2]` 与两次 `System.arraycopy`（主查询与 COUNT 子查询各绑一次）；变异 R 覆盖 |
| P1-3 分布校验从含注释的源码里 `re.search` 取首个 SQL，注释诱饵可绕过 | 新增 `strip_comments()`；SQL 改为从 `POOL_*` 命名常量同源提取，Java 与校验脚本共享同一批常量；变异 S 覆盖 |
| P1-4 池为空时 `WHERE grade=?` / `pick("", null)` 兜底会静默恢复「配比失效 + 标签串科」 | 兜底改为**池保持**：先 `POOL_CORE_ALL` / `POOL_OTHER_ALL`（只放宽年级、保留学科约束），最后才全库；空池用 `MAX(1, (SELECT COUNT(*) ...))` 保证 OFFSET 不为 NULL；`QuizDb.hasOtherQuestions()` 在其他池为空时直接禁用第 4 槽；`FALLBACK_POOL_OK` 与变异 T/U 覆盖 |
| P2 状态 / 默认值 / 文案 | `SubjectWeights.otherAvailable` 默认改为 `false`（只有 `QuizEngine` 确认题库真的有非三科题才置位）；`setBank(null)` 也刷新可用性；删除会静默抽数学题的 2 参 `random()` 重载；SQL 异常改 `Log.e` 而非静默吞掉；修正 197/178 过期计数、设置页第 4 行注释与说明文案 |

**回归护栏**：布局 / 发布契约 69 → **88 条**；`tests/bank_distribution_check.py` 重写为
去注释 + 同源 SQL 常量 + 双份绑定校验 + 复刻 Java 三层兜底，新增 `FALLBACK_POOL_OK`
（模拟三年级英语池 / 其他学科池为空，验证兜底仍不串池）；新增落仓库、可复核的
`tests/mutation_suite.py`（**21 组**变异，含不带发布密钥构建被证书门禁拒绝）。

**验证**：`LAYOUT_CONTRACT_OK total=88`、`JVM_TESTS_OK total=198`
（`SubjectWeightsTest` 20 项，新增 B20「未经 QuizEngine 置位时 OTHER 默认不参与轮盘赌」）、
`POOL_ISOLATION_OK grades=9 pools=4 draws=2160`、
`DISTRIBUTION_OK` 三年级 42.54/24.60/16.06/16.80%、九年级 42.26/24.76/15.26/17.72%
（期望 41.67/25.00/16.67/16.67）、`CORE_ONLY_OK other=0 draws=5000`、
`FALLBACK_POOL_OK`、`BANK_DISTRIBUTION_OK`、`MUTATION_SUITE_OK 21/21`、
`BUILD_OK version=1.0.1 code=15`，v1/v2/v3 签名与证书一致。

**仍待真机确认**：3×2 DPAD 路径、长列表滚动跟随焦点、系统字体 150% 行高、640dp 窄屏余量、
URL 输入框 TV IME 上下键、第三方视频 App 暂停/恢复、通知栏抢焦点后的重抢。

---

## v1.0.1 学科配比修订（versionCode 15）：四槽权重 + 标签修正（初版）

> 展示版本仍是 **v1.0.1**；只把 `versionCode` 14 → 15，签名证书 `76eb6063…6172` 不变，可直接覆盖安装。
> **本节记录初版（commit `ceae012`）；最终定版的门禁与验证数据见上一节。**

**修的是什么**：此前打开「出题范围 = 全学科」后，`QuizDb.random()` 会把 SQL 换成 `WHERE grade=?`（gradeWide），
忽略配比选出的学科。家长设置的 5:3:2 因此完全不决定题目内容，其他学科占比由题库题量决定
（三年级约 7%、九年级约 58%）；硬编码 15% 分支又只在配置了在线题包时才会触发。
更糟的是三科题被贴上「轮盘赌选中的学科」标签，错题库的学科名会串科。

| 改动 | 说明 |
| --- | --- |
| **四槽权重** | 新增纯 Java `SubjectWeights`：数 / 语 / 英 / 其他共 4 槽，默认 **5:3:2:2**（0-5 可调）。全学科关闭时第 4 槽有效权重为 0；内置题库与其他学科在线题包都不可用时也自动失效，避免选中 OTHER 后只能回退成数学题。旧的 15% 硬编码删除。 |
| **配比真正生效** | `QuizDb.random()` 拆成两条 SQL：三科 `WHERE grade=? AND subject=?`，其他学科 `WHERE grade=? AND subject NOT IN ('数学','语文','英语')`；`gradeWide` 全年级跨科抽样删除。取不到时先同年级兜底、再全库兜底（初版行为；定版改为池保持兜底，见上一节）。 |
| **学科标签修正** | `QuizDb.pick()` 用题目真实 `subject` 反推 `q.subject`（`subjectFromBank`），不再沿用选中的池编号；错题库、统计与题面学科名的错标问题消除。 |
| **设置页** | 「其他学科」那格从「—」变成可点的「配比 N」，关闭全学科时显示「配比 N · 未启用」（仍可预先设置）；首页状态行显示四科配比。 |
| **回归护栏** | 布局 / 发布契约 40 → **69 条**，复审加固后 88 条（见上一节）；禁止 `gradeWide` / 15% / `int[3]` / 旧标签路径回归；新增 `tests/bank_distribution_check.py`，从 `QuizDb.java` 提取真实 SQL 实跑题库：9 年级 × 4 池 × 60 次隔离校验、5000 次配比分布校验、全学科关闭 0 题校验，并纳入 `[0/9]` 门禁。 |

**验证（初版）**：`LAYOUT_CONTRACT_OK total=69`、`JVM_TESTS_OK total=197`（新增 `SubjectWeightsTest` 19 项）、
`BANK_DISTRIBUTION_OK`（三年级 42.54/24.60/16.06/16.80%、九年级 42.26/24.76/15.26/17.72%，
期望 41.67/25.00/16.67/16.67）、`MUTATION_SUITE3_OK 16/16`；
`BUILD_OK version=1.0.1 code=15`，v1/v2/v3 签名与证书一致。

**兼容边界**：① 覆盖安装保留 `w0/w1/w2`，`w3` 取默认值 2；② 内置题库首启异步打开前，
第 4 槽暂时不参与抽题；③ 教材版本仍只作用于在线题包筛选、题面标注与错题标记，不改变离线抽题；
④ 真机 DPAD / TV IME / 系统大字模式仍待确认。

---

## v1.0.1 界面修订（versionCode 14）：3×2 首页 + 单列设置

> 展示版本仍是 **v1.0.1**，不新增版本号；只把 `versionCode` 从 13 升到 14，
> 保证 `adb install -r` 能覆盖安装，签名证书 `76eb6063…6172` 不变。

| 改动 | 说明 |
| --- | --- |
| **首页 3 列 × 2 排** | 6 个主按钮从 2×3 改为 3×2：上排「开启守护 / 立即出题 / 家长设置」，下排「授权悬浮窗 / 错题库 · N 题 / 厂商自启设置」。按钮改为 `weight` 等宽（不再写死 190dp），标题行加高到 46dp 避免文字被裁切。 |
| **网格焦点链** | 新增 `wireGrid()`：按 `(行, 列)` 显式连 `nextFocusLeft/Right/Up/DownId`，遥控器上下落在同列、左右落在同行，边缘不绕回。 |
| **家长设置单列卡片流** | 三个并排独立 `ScrollView` 改成一个居中窄栏 `ScrollView` + 5 张编号卡片（出题基础 / 学科设置 / 开机与播放 / 错题库 / 在线题库源），每行「左标签 + 按 weight 填充的取值按钮」，说明文字自然换行，不再挤压或裁切。 |
| **底部固定操作条** | 「修改家长密码 / 保存并返回」从滚动区移到页面底部常驻，长表单滚到任何位置都能直接保存。 |
| **纵向焦点链** | `wireVerticalFocus()` 把跨卡片、跨嵌套 ViewGroup 的可聚焦控件按行串成上下链；`wireSubjectRows()` 仍负责学科行「教材 ↔ 配比」的左右链；URL 输入框与底部按钮也在链内。三栏时代的 `wireColumns()` / `addColumn()` 已移除。 |
| **布局契约** | 新增 `tests/layout_contract.py` 的 **40 条**源码级断言并纳入 `build.sh` 的 `[0/9]` 门禁。纯 JVM 单测无法实例化 `android.view.*`，由它守住结构，`javac` / dex 再守住符号真的被编译；调用次数类断言基于带块注释状态机的去注释代码行，`//` 与 `/* */` 注释掉调用都会被拦下。 |
| **独立复审加固** | 复审指出 1 个 P1 + 6 个 P2，无 P0。已修：`build.sh` 在 `[8/9]` 强制比对 APK 证书 SHA-256 与已发布指纹（不一致直接 `exit 1`，本地实验须显式 `POHW_ALLOW_DEV_SIGN=1`）；契约补上 `setFillViewport` / `Ui.fill(1)` / 自适应行高等布局参数断言；单列行两端左右焦点显式自环，避免系统按几何邻近把焦点甩到相邻行（URL 输入框例外，左右键留给光标）；行按钮改 `WRAP_CONTENT` + 44dp 最小高度，兼容系统大字模式。 |

**验证**：`LAYOUT_CONTRACT_OK total=40`、`JVM_TESTS_OK total=178`、题库校验通过；
全链 `aapt2 → javac → d8 → zipalign → apksigner` 与 v1/v2/v3 签名通过；
`aapt dump badging` 为 `versionCode='14' versionName='1.0.1'`；
**8 组负向变异**（2 列回归 / 三栏残留 / `//` 注释焦点链 / `/* */` 块注释学科链 / 删 `fillViewport` / 滚动区改固定高度 / 改写证书指纹 / 不带发布密钥构建）全部被契约或构建门禁拦下，恢复后源码哈希一致。证书指纹门禁已在构建时强制校验，签名证书与已发布 v1.0.1 完全一致。
**待真机确认**：3×2 网格的 DPAD 移动路径、长列表滚动跟随焦点、不同 ROM 下窄栏宽度、系统字体 150% 时的行高、URL 输入框 TV IME 的上下键行为。
**已知既有边界（非本次回归）**：进入「修改家长密码」门禁后按返回键会直接退出并丢弃未保存的在线源 URL 输入；如需修复需单独调整 PIN 门禁返回栈。

---

## v1.0.1：控制台与设置界面整合

| 改动 | 说明 |
| --- | --- |
| **守护开关合并** | 「开启守护 / 关闭守护」两个按钮合成一个 `btnGuard`，点击切换。未运行显示「开启守护」+ 翡翠绿 `#1D9E75`，运行中显示「关闭守护」+ 砖红 `#A32D2D`，焦点统一高亮蓝 `#185FA5`。颜色表达「当前可执行的动作」，沿用 `GuardState` 色板；运行中点击仍先过就地 PIN 验证层，界面不退出。 |
| **题库更新入口归位** | 删除首页重复的「检查题库更新」；家长设置里同名按钮接手（原「立即检查更新」改名），与在线源地址/状态卡片同栏，并继承空地址时「当前使用内置离线题库」的提示。首页只保留只读状态信息。 |
| **学科设置聚合** | 数学 / 语文 / 英语 / 其他学科各一行：`学科名 │ 教材版本 │ 配比 N`，左按钮切教材、右按钮切配比；原本分散两栏的 7 行压成 4 行。初版按三栏重排为「出题 + 学科设置」「开机与播放 + 错题库」「在线源 + 账户」（该三栏布局已在 versionCode 14 改为单列卡片流，见上一节）。 |
| **TV 焦点链（初版实现）** | `wireColumns()` 先连跨栏，再由 `wireSubjectRows()` 覆盖同一行内部的左右关系，DPAD 可在「教材 ↔ 配比」间切换，行两端仍能跨栏；「其他学科」右位禁用焦点。 |

**验证**：`tests/run.sh` 7 组 **178** 项断言通过（`StopStateTest` 新增 4 项覆盖合并开关的绿/红配色、文案切换、两态可辨与无停用灰）；题库校验通过；全链构建与 v1/v2/v3 签名通过；证书 `76eb6063…6172` 不变。版本 `1.0.1` / versionCode `13`，按 PATCH 递增，支持 `adb install -r` 覆盖安装（本节为初版验证；界面修订版见上一节）。

---

## v1.0.0：首个正式版

> **版本口径**：`v1`–`v6` 是开发期内部迭代编号，不是语义化版本，主版本号曾随功能
> 跳变。为纠正口径，已下架 `v5.0.0` / `v6.0.0` / `v6.0.1` 三个历史 Release
> （commit 历史保留）。自 v1.0.0 起按 `MAJOR.MINOR.PATCH` 递增，仅不兼容变更才升
> MAJOR。Android `versionCode` 是单调技术计数、不随展示版本重置（本版 12 > 前值
> 11），因此可直接覆盖安装。下文带 v4/v5/v6 编号的章节均为开发期迭代记录，
> 不对应任何现存 Release。

开发期 v6.0.0 发布后做了两轮独立复审（功能验收 + Android/AOSP 规范审查），结论都是
「不建议按 5 项全部完成直接交付」。本版针对其中的 P0/P1 集中修复，
并补上此前缺失的行为级单测。

| 问题 | 修复 |
| --- | --- |
| **P0 窗口焦点** | 遮罩 `onWindowFocusChanged(false)` 回调服务；服务有限次 `updateViewLayout` 重抢 + 一次 `remove/add` 重建窗口。明确说明 `FLAG_NOT_FOCUSABLE` 清零不等于 WMS 一定给焦点；重抢失败时保留遮罩，不自动放行。 |
| **P0 前台服务契约** | `startForeground()` 移到 `onStartCommand` 入口，覆盖 STOP / 拒绝 / 权限不足 / 通知构造失败等全部分支，消除 Android 8-11 ANR 与 12+ `ForegroundServiceDidNotStartInTimeException`。 |
| **发现更严重的状态机断线** | 重写后的遮罩虽引入 `QuizFlow` 却从未调用 `submit()`，导致「继续观看」永远不可点、答错禁用项不生效。现在判题由 `QuizOverlayService.answer()` 统一通过 `flow.submit()` 推进，`bind()` 换题时 `reset()`。 |
| **答对确认时序** | 「继续观看」不在 KeyEvent / click 分发栈里同步摘窗口，改为 `root.post()` 到下一帧，避免 detach 当前 `ViewRootImpl`。 |
| **P1 STOP 授权** | 不再信任客户端 `EXTRA_STOP_VERIFIED` 布尔值；密码验证成功后由本进程签发一次性令牌（`commit()` 落盘、60 秒 TTL、消费即删、常量时间比较），服务侧 `consumeStopToken()` 后才交给 `GuardPolicy`。 |
| **P1 开机广播** | `BOOT_COMPLETED` 与厂商 QUICKBOOT 拆成两个 receiver：后者不受 `RECEIVE_BOOT_COMPLETED` 保护、也不在 Android 12+ 后台启动 FGS 豁免白名单里，单独接收后可显式降级。 |
| **P1 按键时序** | 抽出纯 Java `KeyRouter`：音量键 DOWN/UP 成对放行；确认键/数字键/方向键长按 repeat 全部过滤；BACK 与媒体键一律吞掉。 |
| **P1 音频焦点** | `MediaPauseController` 改用 API 26+ `AudioFocusRequest` + 非空 listener；拿到过焦点时不再额外派发 `MEDIA_PLAY`，避免误唤醒其他 MediaSession 或二次翻转播放状态。 |
| **P1 在线题包空转** | `QuizEngine.next()` 改为内置 DB 与在线题包按概率混合，分学科教材版本筛出的在线题包不再被 `db.random()` 抢先返回架空；未知学科归入 `Subjects.OTHER`。 |
| **P1 网络与设置** | 禁用自动重定向并手动校验 `Location` 同主机；设置页未保存的在线源 URL 不再被刷新清空；明文示例地址改为本机调试域；三栏显式跨栏焦点链路。 |
| **P1 错题库** | 去重键纳入学科与正确答案下标；`record()` 读写进事务、写失败不推进摘要；`onUpgrade()` 不再 DROP 用户数据；首次打开走后台 `refreshAsync()`；标题恒用真实 `COUNT(*)`，列表 500 条上限并提示截断；清空二次确认 5 秒复位。 |
| **P2 性能与门禁** | 控制台按「题库实例 + 年级」缓存聚合行、悬浮窗权限只在 `onResume` 重查、`setText` 前比较、Runnable 字段化并在 `onDestroy` 统一撤销；PIN 浮层隔离底层焦点；构建静态守卫改为语义符号检查。 |

**验证**：`tests/run.sh` 共 7 组 **174** 项断言全部通过（其中 `BehaviorV601Test`
51 项覆盖按键时序、答题状态机、一次性令牌）；`tools/quizbank/verify.py` 通过；
全量 `aapt2 -> javac -> d8 -> zipalign -> apksigner` 构建与 v1/v2/v3 签名校验通过。
`dist/PoHW-v1.0.0.apk` 由定版源码重新出包（开发期 v6.0.0 旧包不含本轮修复），
dex 中可检出 `refreshAsync` / `wireColumns` / `consumeStopToken` / `rebuildOverlay`；
签名证书 SHA-256 `76eb6063…6172` 与各开发期版本一致。版本 `1.0.0` /
versionCode `12`，支持 `adb install -r` 覆盖安装。

> 仍属 best-effort、需真机验证：第三方视频 App 是否响应暂停；连续弹题时
> 通知栏/系统对话框抢焦点后能否重抢；Android 12+ `HIDE_OVERLAY_WINDOWS` 行为。
> 审查结论里「5 项全部完成」不成立的部分（离线题库无出版社版本字段、
> 暂停与窗口焦点均非强制能力）已在此明确写成边界，不再表述为「已修复」。

---

## 开发期 v6.0.0：小米电视真机反馈的 5 项改进

| 反馈 | 落地实现 |
| --- | --- |
| 弹题时视频还在播 | `MediaPauseController`：弹题后申请 `AUDIOFOCUS_GAIN_TRANSIENT` + 派发 `KEYCODE_MEDIA_PAUSE`；答对解锁后归还焦点、按设置派发 `MEDIA_PLAY`。答题期间遮罩吞掉媒体键，防止遥控器把后台视频重新播放。设置里可分别开关「暂停」「恢复」。 |
| 按学科选教材版本 | `SettingsStore` 用 `edition_s0..3` 四个独立键（兼容旧 `edition` 单值作为默认值）；设置页四行分别循环切换；`BankRepository.load(grade, String[])` 按学科筛选在线题包；题面与错题记录带对应版本。 |
| 开机自启 + 自动守护 | `bootAutostart`（默认开）/ `autoGuardOnBoot`（默认关）两个开关，`BootPolicy` 统一判定开机广播白名单与悬浮窗授权；`BootSettings` 逐项尝试小米/OPPO/华为/vivo 的自启动设置页，最后降级到应用详情页。 |
| 答错 2 次换题 + 错题库 | `AnswerPolicy.WRONG_LIMIT=2`；第二次答错时**原地**换题（不 remove/add 窗口）、原题写入独立可写 `pohw_wrong.db`；`WrongBookActivity` 单题翻页，标出正确答案与选错项，支持删除与二次确认清空；控制台显示错题数并可进入。 |
| 第二次弹题遥控选不中 | 根因是复用同一个 View 反复 `addView/removeView` 且答对后焦点残留在「继续观看」按钮上。现在每次弹题新建 `OverlayQuizView`，窗口参数显式清掉 `FLAG_NOT_FOCUSABLE`，子按钮不再参与焦点树，方向键/确定键/数字键全部由根视图 `dispatchKeyEvent` 自主路由并手动绘制高亮。 |

> 说明：错题库是独立可写数据库，不改动只读的 `assets/quiz.db`；
> 教材版本不会改变内置离线题库的抽题结果（上游纲目没有出版社字段）。

---

## 数据与许可证

- **代码**：MIT，见 [`LICENSE`](LICENSE)。
- **题库数据**：上游 `learning-content` 仓库**未附 LICENSE**。该文件不包含在 MIT 授权范围内，
  仅用于个人非商业学习；如需再分发，请先确认上游授权并自行重建题库。
- 仓库与 APK 中**不含**任何火花 K12 / 付费接口 / 爬取所得的真题文本，也不含任何密钥。

这是一个自己家里用的玩具项目，不是商用家长控制产品；请在自己设备上、在自己孩子知情的前提下使用。
