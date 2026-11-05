# 架构与关键决策

## 组件职责

| 类 | 职责 |
| --- | --- |
| `MainActivity` | 控制台：3 列 × 2 排网格、授权入口、单一守护切换开关、就地验证浮层、运行状态与题库统计（题库更新入口不在此） |
| `SettingsActivity` | 家长设置：PIN 门禁 + 单列卡片流（居中窄栏 + 5 张分组卡片 + 底部固定操作条），学科行左教材右配比 |
| `QuizOverlayService` | 前台服务：1 秒计时、WindowManager 全屏遮罩、答题判定、可选在线同步 |
| `BootReceiver` | 开机恢复守护（校验 action + 守护开关 + 悬浮窗授权） |
| `LaunchPolicy` | 「启动即守护」冷启动判定（纯 Java，可 JVM 单测） |
| `DonateActivity` | 打赏页：微信 / 支付宝收款码 + 遥控器左右切渠道，纯静态资源、无支付 SDK |
| `QuizDb` | 内置 SQLite：版本化原子复制、只读、按学科池本地随机抽题、池保持跨年级兜底 + 全库最后兜底；用题目真实 `subject` 反推标签 |
| `QuizEngine` | 三层出题链：DB -> 在线缓存题 -> 参数化生成器；委托 `SubjectWeights` 做四槽轮盘赌 |
| `BankRepository` | 可选在线题库：版本比对、分块下载、SHA-256、原子缓存 |
| `PinGate` | 纯 Java PIN 状态机（首次设置 / 验证 / 修改），可 JVM 单测 |
| `PinVerifyView` | 首页就地验证浮层（不跳 Activity、不 finish 宿主） |
| `GuardPolicy` / `GuardState` | 关闭守护的授权规则 / 守护开关配色文案规则（`toggleColor` 绿=GO / 红=STOP），单一规则源 |
| `OverlayQuizView` / `QuizTextSizer` | 题目 UI 与自适应字号 |
| `SettingsStore` | SharedPreferences 封装 + PIN 哈希 + 四科配比（`w0..w3`） |
| `SubjectWeights` | 纯 Java 四槽学科权重；全学科开关与题库可用性共同决定第 4 槽是否有效，可 JVM 单测 |

v1.0.1 起控制台只有一个守护开关：`GuardState.toggleColor(running)` 给绿/红，
`toggleLabel(running)` 给「开启守护 / 关闭守护」；运行中点击先过 `PinVerifyView`，
界面不跳转。首页 6 个按钮按 3 列 × 2 排排列，`wireGrid()` 显式连同列上下 / 同行左右。
家长设置只有一个 `ScrollView`：5 张卡片按行登记进 `focusRows`，
`wireVerticalFocus()` 串起跨卡片的上下链，`wireSubjectRows()` 再覆盖学科行内的
左右关系，保证 TV 遥控器能在教材版本与题目配比之间移动，且不需要跨栏跳转。
单列行两端未显式定义的左右目标指向自身（URL `EditText` 例外，左右键留给光标移动），
避免系统按几何邻近把焦点甩到相邻行；行按钮 `WRAP_CONTENT` + 44dp 最小高度，兼容系统大字模式。
源码结构由 `tests/layout_contract.py` 的 **114 条**断言在构建门禁里守住，
`build.sh` 另在签名后强制比对发布证书指纹。

**学科配比（versionCode 15）**：`SubjectWeights` 保存数 / 语 / 英 / 其他四槽权重（默认 5:3:2:2）。
`QuizEngine.pickSubject()` 直接委托它的轮盘赌；全学科关闭、或内置题库与其他学科题包都不可用时，
第 4 槽有效权重自动归零，保证选中 OTHER 后一定有题可出
（`otherAvailable` 默认 `false`，由 `QuizEngine` 依据 `QuizDb.hasOtherQuestions()` 或非空其他学科题包显式置位；
`setBank(null)` 同样会刷新）。`QuizDb.random()` 按选中的池走两条 SQL：
三科用 `grade + subject`，其他学科用 `grade + subject NOT IN (数/语/英)`；旧的 `gradeWide`
全年级跨科抽样与硬编码 15% 分支已删除。`QuizDb.pick()` 由题库里的真实 `subject` 反推 `q.subject`，
因此错题库与统计的学科名与题目内容一致。`tests/bank_distribution_check.py` 从 `QuizDb.java`
提取真实 SQL 实跑内置题库，验证池隔离、配比分布与全学科关闭时 0 题，纳入 `[0/9]` 门禁。
兜底是**池保持**的：先只放宽年级（`POOL_CORE_ALL` / `POOL_OTHER_ALL`），最后才全库；
空池用 `MAX(1, (SELECT COUNT(*) ...))` 保证 `OFFSET` 不为 NULL，避免 SQL 异常被吞后静默串池。
同一批 `POOL_*` 常量、双份参数绑定与三层兜底都被 114 条契约和 `tests/mutation_suite.py` 的 29 组变异钉死。

**默认开关与启动链路**：全新安装时「开机自启」关、「启动即守护」开、答题暂停与恢复播放均开。
`MainActivity.onCreate()` 用 `LaunchPolicy.shouldAutoGuard()` 判定是否自动拉起守护，四个条件缺一不可：
冷启动（`savedInstanceState == null`）、开关开启、已授权悬浮窗、服务未运行。从后台返回前台只走
`onResume`，不会撤销家长用 PIN 手动关闭的守护；开机广播仍由 `BootPolicy` 管，两条链路共用
`SettingsStore.autoGuardOnBoot()`（键名保留以兼容覆盖安装）。家长设置页所有行、按钮与说明
统一走 `rowLp()` 拿 12dp 行间距，卡片标题按内容自适应高度。

## 主流程

```
开启守护
  -> MainActivity.send(ACTION_START)
  -> QuizOverlayService.startGuard()
       校验 Settings.canDrawOverlays
       startForeground(NOTIF_ID, buildNotification())
       reload(): QuizDb.open(this) -> engine.setDb/setBank/setAllSubjects
  -> Handler 每 1 秒 tick，倒计时归零 -> showQuiz()
       wm.addView(quizView, TYPE_APPLICATION_OVERLAY 全屏参数)
  -> 答对 -> correct() -> 「继续」-> finishQuiz() -> removeViewImmediate()
       remainSec = interval（续租）
```

为什么用 `WindowManager.addView` 而不是 `startActivity`：Android 10+ 有后台启动
Activity（BAL）限制，从前台服务弹 Activity 会被系统拦截；加窗口是服务自己的 View，
不受该限制，也不需要 `HIDE_OVERLAY_WINDOWS` 对抗 —— 它是正常可聚焦的顶层窗口。

## 关闭守护的双重门禁

```
MainActivity: GuardPolicy.requiresPinToStop(hasPin)
  -> true : PinVerifyView 就地验证（界面不退出，仅叠加半透明层）
  -> false: 直接 doStop()
doStop() -> QuizOverlayService.requestStop(c, verified=true)
  -> SettingsStore.issueStopToken()：随机 24 字节、commit 落盘、60 秒 TTL
  -> startForegroundService(ACTION_STOP + EXTRA_STOP_TOKEN)
  -> Service.onStartCommand() 开头无条件 startForeground()（满足 5 秒契约）
     consumeStopToken(token)：消费即删、常量时间比较、过期/重放一律 false
     GuardPolicy.allowStop(hasPin, verified)  // 服务侧二次校验
     -> 拒绝则 Toast 并返回 START_STICKY（服务未运行则立即 stopForeground + stopSelf）
     -> 放行则 stopAll()
MainActivity.paintToggleButtons() 只用 GuardState 改文字/颜色，绝不 finish()
```

**FGS 契约（P0）**：`startForeground()` 位于 `onStartCommand` 最开头，因此
STOP、被拒绝的 STOP、悬浮窗权限不足、通知构造失败等**所有分支**都满足
Android 8+ 的「5 秒内前台化」要求；`onDestroy()` 还会显式 `stopForeground(true)`。

**为什么不信任布尔值**：`startService` 的 Intent extras 由调用方提供，
`EXTRA_STOP_VERIFIED=true` 可被伪造。审查加固后（自 v1.0.0 起）授权依据是只有本进程能签发的
一次性令牌；服务读完立即删除，重放与过期都失败。

边界：服务 `exported=false`，普通第三方应用无法直接发该 Intent；但 `adb shell am`、
系统「强制停止」、卸载、清除数据属系统级能力，第三方无法防御，本项目也不尝试防御
（静态守卫明确禁止 DeviceAdmin / `startLockTask` / `DELETE_PACKAGES`）。

## PIN 存储

- `SettingsStore` 实现 `PinGate.Store`：`hasPin` / `checkPin` / `setPin`。
- 每个密码生成 16 字节 `SecureRandom` salt，用 `PBKDF2WithHmacSHA1`
  60,000 次迭代派生 256 位密钥（API 21+ 与标准 JVM 均可用；
  `PBKDF2WithHmacSHA256` 需要 API 26+）；
  比较使用 `MessageDigest.isEqual`（常量时间）。
- `setPin()` 用 `commit()` 同步落盘并回读校验，避免「提示成功但没保存」。
- 兼容 v4.x 的旧 `SHA-256(salt:pin)` 格式（`pinIter=0` 走旧逻辑），重设密码即迁移。
- 年级、出题范围、守护开关等关键设置同样用 `commit()`。
- `android:allowBackup=false`，避免哈希随备份导出。
- 4-8 位数字没有速率限制，KDF 只提高离线爆破成本；拿到 root / adb 的设备
  可以直接改 SharedPreferences，这属于物理接触设备的边界，不在防护范围内。

## 悬浮窗与生命周期

- `Build.VERSION.SDK_INT >= 26` 用 `TYPE_APPLICATION_OVERLAY`，旧平台回退 `TYPE_PHONE`。
- 参数：`MATCH_PARENT × MATCH_PARENT`、`FLAG_LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS |
  KEEP_SCREEN_ON | DIM_BEHIND`、`dimAmount=0.88`。
- `hideQuiz()` 无条件尝试 `removeViewImmediate()`，`showQuiz()` 的异常分支也会清理，
  避免「addView 成功但标志位没置上」留下永久僵尸窗口盖住卸载确认框。
- `onDestroy()` 移除 Handler 回调、摘掉窗口；`startGuard()` 前先 `removeCallbacks(tick)` 保证幂等。
- 通知渠道 `tvquiz_guard` 在 Android 8+ 创建，使用专用白色剪影小图标
  `R.drawable.ic_stat_quiz`（不再借用系统图标）；Android 13+ 的 `POST_NOTIFICATIONS`
  在 `MainActivity` 运行时申请，拒绝时 Toast 提示但守护仍可运行。

## 题库与在线源

- 默认链路完全离线：`assets/quiz.db` -> `databases/quiz.db` -> 只读随机抽题。
- 首启需要复制约 4.5 MB 数据库，因此 `MainActivity` 与 `QuizOverlayService`
  都在后台线程调用 `QuizDb.open()`；就绪前控制台显示「正在加载」、出题引擎
  走参数化生成器兜底。UI 线程只用 `QuizDb.peek()` 读已就绪实例。
- `app/assets/quiz.db.version` 与库内 `meta.version` 比对，不一致时写 `.tmp`、
  `fsync`、校验可读、再 `renameTo` 原子替换。
- `QuizDb.random(grade, subject, allSubjects)`：三科优先，指定组合无题时
  回退该年级全学科，再回退全库；`QuizEngine` 最后还有参数化生成器兜底。
- 在线源为**可选项**：`bankUrl` 为空时不发请求；非空时 `BankRepository` 在单线程池
  下载、`index.json` 版本比对、逐包 SHA-256 校验、原子落盘，回调切回主线程。
- 默认禁止明文 HTTP（`res/xml/network_security_config.xml` 仅放行本机调试地址）。

## 已知技术债（欢迎 PR）

- UI 全部由 Java 代码构建，仍有一部分文案硬编码；`strings.xml` 目前只抽了
  应用名、通知渠道与通知文案，完整资源化 / 多语言是后续工作。
- 未启用 R8 / 混淆（命令行 d8 链路不带 R8）；发布前可接入 `r8.jar`。
- `targetSdk=33`。升到 34 需补 `foregroundServiceType`、`FOREGROUND_SERVICE_*`
  权限与更严格的后台启动校验。
- 控制台每秒 `Handler` 轮询刷新，可改成状态变化事件驱动。
- 随机抽题已改为 `LIMIT 1 OFFSET (ABS(RANDOM()) % COUNT(*))`，避免整表排序；
  十万行以上可改为主键区间采样。
- 纲目题偏「知识点辨识」，不是教材同步真题，题型丰富度有提升空间。

## v6 架构增补

- **遮罩输入层**：`OverlayQuizView` 不再依赖 Android View 焦点树。根 `FrameLayout`
  覆写 `dispatchKeyEvent`，按键语义翻译后交给纯 Java `KeyRouter.decide()`
  （音量键成对放行、长按 repeat 过滤、BACK/媒体键吞掉）；2x2 选项矩阵用
  `NEXT` 邻接表移动高亮，`paint()` 手动绘制。子按钮 `setFocusable(false)`，
  只保留触屏点击。`showQuiz()` 每次新建视图并 `addView`；换题走 `bind()` 原地刷新。
- **答题状态机**：`QuizFlow`（纯 Java）是 `wrongAttempts` / `disabled[]` /
  `selected` / `answered` 的**唯一**来源，由 `QuizOverlayService.answer()` 调用
  `flow.submit(idx, answerIndex)` 推进，`bind()` 换题时 `reset()`。
  答对后的「继续」通过 `root.post()` 延后，不在 KeyEvent 分发栈里同步摘窗口。
- **窗口焦点状态机（P0）**：View 树 `requestFocus()` 与 WMS 的 focused window
  是两回事；清掉 `FLAG_NOT_FOCUSABLE` 也不保证焦点。根视图
  `onWindowFocusChanged(false)` 通知服务，服务有限次 `updateViewLayout` 重新
  下发参数、必要时 `remove` + `add` 同一个 View 重建窗口（保留 `QuizFlow` 状态），
  用 `focusRecovering` 过滤嵌套回调防止自旋。重抢失败则保留遮罩、不自动放行。
- **播放暂停**：`MediaPauseController` 只使用 `AudioManager` 的公开能力
  （短暂音频焦点 + `dispatchMediaKeyEvent`），不创建 MediaSession、不申请通知使用权、
  不注入按键。服务 `onDestroy` / `stopAll` 都会 `release()` 归还焦点。
- **错题库**：独立 `SQLiteOpenHelper`（`pohw_wrong.db`），与只读 `quiz.db` 完全隔离；
  单线程 `ExecutorService` 异步写入，主线程读走 `cachedCount()` 避免每秒轮询 SQLite。
  题目去重键为长度前缀编码的 `subject + answerIndex + 题干 + 四个选项`
  的 SHA-256；纳入答案下标后，题面相同但正确答案不同的题不会被合并。
- **分学科版本**：`Subjects.editionKey(s)` 生成 `edition_s0..3`；
  `BankRepository.load(grade, String[])` 按学科匹配在线题包；
  `SettingsStore.edition()` 保留为数学版本，兼容 v5 调用点。
- **开机自启**：`BootPolicy` 是零依赖策略源；`BootReceiver` 只接收受保护的
  `BOOT_COMPLETED`，厂商 QUICKBOOT / HTC QUICKBOOT 拆到 `QuickBootReceiver`
  （不声明 `RECEIVE_BOOT_COMPLETED` sender permission，否则厂商广播会被过滤）。
  两者共用 `BootReceiver.tryStartGuard()` 判定两个开关与悬浮窗授权再拉服务。
  Android 12+ 上 QUICKBOOT 不在后台启动 FGS 豁免白名单，拉起失败会被显式捕获记录，
  `BOOT_COMPLETED` 是可靠路径。厂商「自启动」权限仍需家长在系统设置里手动允许。

### v6 待验证 / 已知边界

- 暂停视频是 best-effort，不同视频 App 对音频焦点与媒体键的响应不一致，需要在真机逐个验证。
- 内置离线题库没有出版社版本字段，分学科版本不会改变离线抽题结果。
- 错题库不做云同步、不跨设备；`adb shell pm clear` 会一并清空。
