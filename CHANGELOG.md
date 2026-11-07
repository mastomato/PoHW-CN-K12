# 更新记录

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
