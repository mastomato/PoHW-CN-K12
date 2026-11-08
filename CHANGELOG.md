# 更新记录

## 1.0.5 · 2026-11-08

家长可配置退出门禁 + 全站大字版，versionCode 升至 22。

- **「答对才能返回」可配置**：家长设置新增 1–10 题的循环选择，**默认 5 题**；`SettingsStore.requiredCorrect()` 用 `commit()` 同步落盘并夹到合法区间。`ExitPolicy` 保留纯 Java 单一规则源，改为 `canExit(sessionCorrect, required)` / `normalizeRequired()` / `remaining()`，兼容单参重载按默认 5 题判断。
- **双端门禁同步**：`QuizOverlayService.showQuiz()` 每次弹题重新读取阈值并重置会话计数；答对后用 `setExitGate(required, correct, pinSet)` 把阈值与进度下发给 `OverlayQuizView`；`finishQuiz()` 与 tick 的两道校验都改用 `ExitPolicy.canExit(sessionCorrect, requiredCorrect)`。提示与拦截文案显示「需答对 N 题 / 还差 K 题」，主页「学习配置」也显示当前阈值。
- **全站字号与视觉**：`Ui.TYPE_SCALE = 1.35f` + `scaledSp()` 统一放大 `Ui.tv()` / `Ui.button()` 产出的所有文字；`QuizTextSizer` 新档位 26/29/32/35/38（长题最小约 35sp 实际字号），答题页题干用 `QUESTION_BASE_SP` 与 `Ui.scaledSp()` 对齐。加深 `MUTED` 对比度、统一 `RADIUS_DP` 卡片圆角、加粗焦点描边，主页/设置/答题/密码浮层/错题库的按钮、行高、卡片内边距与固定高度同步放大，WRAP_CONTENT 行仍随系统字体缩放自适应。
- **验证**：布局契约 145 条、JVM 检查 9 组 238 项通过；`ExitGateTest` 扩展到 37 项覆盖默认值、边界夹取、可配置阈值与进度文案；变异套件新增 3 组大字/门禁回退用例。签名仍沿用 `e5816bdd…212286c`，可直接覆盖 1.0.1 / 1.0.2 / 1.0.4。


## 1.0.4 · 2026-11-07

修复 1.0.3 连续答题改造引入的两个行为问题，versionCode 升至 21。

- **答题时真正暂停播放**：`MediaPauseController` 由「弹题瞬间一次媒体键 + 一次短暂音频焦点」改为答题全程持续持有 `AUDIOFOCUS_GAIN_TRANSIENT`，并按 0.35s / 1s / 之后每 2.5s 重复派发 `KEYCODE_MEDIA_PAUSE`（单链 pulse，最多 240 次，避免按键链分裂）。家长授予「通知使用权」后，通过新增的 `MediaSessionListener` 拿到 `MediaSessionManager.getActiveSessions()`，对每个活跃会话调用 `TransportControls.pause()`；退出时归还焦点并按需补发 `KEYCODE_MEDIA_PLAY`。家长设置新增「强制暂停播放器」入口与授权状态显示。
- **不答对不许返回**：新增纯 Java `ExitPolicy`（`REQUIRED_CORRECT = 1`）作为单一规则源；`QuizOverlayService` 按「一次弹题会话」累计 `sessionCorrect`，换题、新一轮 `QuizRound.reset()` 都不清零。`OverlayQuizView.requestExit()` 与 `QuizOverlayService.finishQuiz()` 双重校验，答对前的返回键只给提示、遮罩不撤；HOME / 最近任务键在能收到时一律吞掉，窗口被系统摘掉则每秒巡检重建。
- **家长应急出口**：遥控器菜单键或数字 0 可调出 `PinVerifyView` 输密码退出（未设置家长密码时不开放），避免孩子被题目卡死时无解。
- **签名**：改回 1.0.1 / 1.0.2 使用的发布证书 `e5816bdd…212286c`。1.0.3 的 `42cbf6a7…07cdb` 私钥未随仓库保存，装有 1.0.3 的设备需先卸载再安装，详见 README「安装与升级」。
- **验证**：布局契约 125 条、JVM 检查 9 组 226 项、题库分布校验、35 组负向变异全部通过；新增 `ExitGateTest`。

## 1.0.3 · 2026-11-06

- 全屏答题页采用知行答题 TV 的浅色卡片、紫色焦点与竖排选项。
- 首页、家长设置与答题页统一浅色紫色配色；首页改为横向状态卡，答题页铺开显示并放大题干、选项与解析。
- 答对自动加载下一题；答错显示答案与解析，再按确定进入下一题。
- 家长设置新增每轮题数；完成后显示成绩，按确定开始下一轮，仅返回键退出答题。
- 新增答对、答错与完成音效，重新设计应用图标、电视横幅和通知图标。
- 修正一道英语题的重复干扰项，更新离线题库版本戳。
- 延续上游 1.0.2 的打赏功能移除；versionCode 升至 20。发布签名密钥与上游旧版不同，旧版安装需先卸载。

## 1.0.2

- 移除打赏入口、页面和静态收款码。

## 1.0.1

- 学期筛选、题库合并、守护和家长设置改进。详见 [上游变更记录](docs/UPSTREAM-CHANGELOG.md)。
