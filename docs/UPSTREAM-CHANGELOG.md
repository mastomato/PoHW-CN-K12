# Changelog

> **版本口径（2026-11-05 起）**：`v1`–`v6` 是开发期内部迭代序号，不是语义化版本，
> 主版本号曾随功能迭代跳变。为纠正这一口径，已下架 `v5.0.0` / `v6.0.0` /
> `v6.0.1` 三个历史 Release，自 **v1.0.0** 起统一采用 `MAJOR.MINOR.PATCH`
> （仅不兼容变更才升 MAJOR）。下方历史条目保留原始编号以便追溯 commit，但不再
> 对应任何可下载的 Release；部分内部测试类名（如 `BehaviorV601Test`）沿用开发期
> 标识，同样不代表发布版本。Android `versionCode` 是单调技术计数，不随展示版本
> 重置：v1.0.0 为 12（前值 11），因此可直接 `adb install -r` 覆盖安装。

## v1.0.1 (versionCode 17) - bank note placement; question-bank granularity study

Display version stays **1.0.1**; `versionCode` moves 16 -> 17 with the same
release keystore (SHA-256 `76eb6063b574...26172`).

- Move the `默认完全离线：内置全量 K12 题库...` note from after the donation
  button to directly below `检查题库更新` in the online-bank card: it documents
  the bank-source switch, not the donation. A new source-level ordering
  assertion pins the four elements (`检查题库更新` < offline note < donate
  button < donate note), so `tests/layout_contract.py` grows 113 -> **114**.
- Read-only study of whether the bundled SQLite bank can scope questions by
  term / textbook unit, documented in `docs/QUESTION_BANK.md`. **No
  quiz-drawing logic changed**: `QuizDb.random()` still filters on
  `grade + subject` only. Implementation waits for a product decision.
- Verification: `LAYOUT_CONTRACT_OK total=114`, `JVM_TESTS_OK total=204`,
  `BANK_DISTRIBUTION_OK` (incl. `FALLBACK_POOL_OK`), `MUTATION_SUITE_OK 29/29`,
  `BUILD_OK version=1.0.1 code=17`, v1/v2/v3 signatures valid.

## v1.0.1 (versionCode 16) - settings polish, launch-to-guard, donation, branding

Display version stays **1.0.1**; `versionCode` moves 15 -> 16 with the same
release keystore (SHA-256 `76eb6063b574...26172`), so `adb install -r` is a
real upgrade.

- **Row gap.** Every settings row, button and note now goes through `rowLp()`
  (`ROW_GAP_DP=12`, 6dp on each side); card spacing moves 4/10 -> 6/14dp and
  the card header uses `WRAP_CONTENT` + 28dp minHeight so font scale 1.5 no
  longer clips it. This fixes buttons visually glued together on the TV.
- **Wording.** `开启（开机即守护）` becomes `开启（启动即守护）`; the home
  status line uses the same wording.
- **Defaults (fresh installs).** `bootAutostart` false (parent must opt in and
  grant the vendor autostart permission), `autoGuardOnBoot` true, `pauseOnQuiz`
  and `resumePlayback` true. SharedPreferences keys are unchanged so an
  in-place upgrade never resets a parent's own choices.
- **Launch = guard.** New pure-Java `LaunchPolicy.shouldAutoGuard()` gates the
  cold start on four conditions (cold start, switch on, overlay permission
  granted, service not already running). `MainActivity.onCreate()` delegates to
  it; returning from background only runs `onResume`, so a guard the parent
  stopped with PIN is not silently re-enabled. The boot broadcast path stays
  in `BootPolicy`; both share the same switch.
- **Donation.** A `打赏作者 · 请喝一杯咖啡` button sits directly below the bank
  update option in parent settings. `DonateActivity` shows full-screen QR codes,
  DPAD left/right switches WeChat Pay / Alipay, BACK exits. The two QR cards
  are cropped from the posters supplied by the author (quiet zone and signature
  preserved) and shipped as `res/drawable-nodpi/*.png`. There is no payment SDK,
  no order and no network call: scanning and paying happen on the phone.
- **Branding.** New reproducible `tools/branding/make_icons.py` (PIL only)
  draws a dark-navy rounded icon with an amber timer arc, a screen and a mint
  checkmark; it emits launcher icons at 5 densities, a 640x360 TV banner and
  2 white status-bar glyphs.
- **Gates.** `tests/layout_contract.py` grows 88 -> **113** assertions;
  `tests/mutation_suite.py` grows 21 -> **29** mutations (row gap, four
  defaults, launch policy, donation position/DPAD/resources, branding);
  `FeatureV6Test` adds 6 `LaunchPolicy` assertions and updates F1-F4, so JVM
  tests grow 198 -> **204**.
- Verification: `LAYOUT_CONTRACT_OK total=113`, `JVM_TESTS_OK total=204`,
  `BANK_DISTRIBUTION_OK` (incl. `FALLBACK_POOL_OK`), `MUTATION_SUITE_OK 29/29`,
  `BUILD_OK version=1.0.1 code=16`, v1/v2/v3 signatures valid. TV-side checks
  still needed: 12dp gap appearance, QR scannability at distance, DPAD channel
  switch focus chain, cold-start auto-guard vs PIN stop, new icon/banner.

## v1.0.1 (versionCode 15, final) - review-driven hardening

Display version and `versionCode` are unchanged; this is the final hardened
build of the four-slot weighting change, signed with the same release keystore
(SHA-256 `76eb6063b574...26172`) and shipped under the same artifact names.
An independent read-only review (`weighting-reviewer`) returned CONDITIONAL PASS
with no P0, 4 x P1 and 6 x P2. All code findings are fixed here.

- **P1-1 route gate.** `boolean other = s == Subjects.OTHER && allSubjects;`
  had no assertion anywhere: breaking it left every test green. The literal is
  now pinned by `app/build.sh`, `tests/layout_contract.py` and
  `tests/bank_distribution_check.py`; mutation Q covers it.
- **P1-2 double binding.** The main query and its `COUNT(*)` OFFSET subquery
  each need the WHERE args. A single binding throws inside `rawQuery`, was
  caught silently and degraded to an arbitrary full-bank question. Both
  `new String[args.length * 2]` and the two `System.arraycopy` calls are now
  asserted; mutation R covers it.
- **P1-3 comment decoy.** `bank_distribution_check.py` used `re.search` over
  raw source including comments, so a commented fake SQL could pass. It now
  strips comments (line + block state machine) and extracts the same
  `POOL_*` named constants used by `QuizDb.java`; mutation S covers it.
- **P1-4 pool-preserving fallback.** Empty pools used to fall back to
  `WHERE grade=?` / `pick("", null)`, silently restoring the original bug.
  Fallback now drops only the grade (`POOL_CORE_ALL` / `POOL_OTHER_ALL`) and
  only then the whole bank. Empty pools use `MAX(1, (SELECT COUNT(*) ...))`
  so `OFFSET` is never NULL, and `QuizDb.hasOtherQuestions()` disables the
  fourth slot when no non-core questions exist. `FALLBACK_POOL_OK` plus
  mutations T/U cover it.
- **P2.** `SubjectWeights.otherAvailable` now defaults to `false` and is set
  only after `QuizEngine` confirms a real non-core pool; `setBank(null)` also
  refreshes availability; the two-arg `random(grade, subject)` overload that
  silently drew math is deleted; SQL failures are logged via `Log.e`; stale
  197/178 counts, the fourth-row UI comment and the settings note are fixed.
- **Gates.** `tests/layout_contract.py` grows 69 -> **88** assertions.
  `tests/bank_distribution_check.py` is rewritten to share the Java SQL
  constants, strip comments, verify double binding and replay the three-tier
  fallback (new `FALLBACK_POOL_OK`: grade-3 english pool and the non-core pool
  emptied, fallback still keeps the subject constraint).
  `tests/mutation_suite.py` is now committed and reproducible (**21/21**),
  including the dev-signed build rejected by the certificate gate.
- Verification: `LAYOUT_CONTRACT_OK total=88`, `JVM_TESTS_OK total=198`
  (`SubjectWeightsTest` 20 assertions, new B20), `POOL_ISOLATION_OK grades=9
  pools=4 draws=2160`, `DISTRIBUTION_OK` grade 3
  42.54/24.60/16.06/16.80 and grade 9 42.26/24.76/15.26/17.72
  (expected 41.67/25.00/16.67/16.67), `CORE_ONLY_OK other=0 draws=5000`,
  `FALLBACK_POOL_OK`, `BANK_DISTRIBUTION_OK`, `MUTATION_SUITE_OK 21/21`,
  `BUILD_OK version=1.0.1 code=15`, v1/v2/v3 signatures valid.
  TV DPAD, IME, font-scale and 640dp narrow-screen checks still need a
  physical Xiaomi TV pass.

## v1.0.1 (versionCode 15) — four-slot subject weighting (initial)

Display version stays **1.0.1**; `versionCode` moves 14 -> 15 with the same
release keystore (SHA-256 `76eb6063b574…26172`). Initial implementation
(commit `ceae012`); final hardened numbers are in the entry above.

- **Bug fixed: weights were bypassed in all-subjects mode.** `QuizDb.random()`
  previously switched to `WHERE grade=?` (gradeWide) whenever `allSubjects`
  was on, so the configured 5:3:2 ratio did not control which subject the
  question came from; the share of non-core subjects was decided by bank size
  (~7% in grade 3, ~58% in grade 9), and the hardcoded 15% OTHER branch only
  fired when an online question pack existed. Core-subject questions were also
  labelled with the roulette-selected pool instead of their real subject,
  corrupting wrong-book subject names.
- **Four slots.** New pure-Java `SubjectWeights` holds math/chinese/english/other
  weights (default 5:3:2:2, range 0-5). The fourth slot is zeroed when
  all-subjects is off, and also when neither the builtin DB nor an OTHER online
  pack is available, so a missing bank can never degrade into a math fallback.
  `QuizEngine` delegates `pickSubject()` to it; the 15% constant and the
  `OTHER -> MATH` downgrade are removed.
- **Two SQL pools.** `QuizDb.random()` now uses `grade + subject` for the three
  core subjects and `grade + subject NOT IN (...)` for other subjects, with
  same-grade then full-bank fallbacks. `QuizDb.pick()` derives `q.subject` from
  the real bank subject via `subjectFromBank()`.
- **UI.** The fourth subject row is an adjustable weight button (shows
  "not active" when all-subjects is off but can be preset); the home status
  line shows all four weights. Existing `w0/w1/w2` survive an in-place upgrade.
- **Gates.** `tests/layout_contract.py` grows to 69 assertions (forbids
  gradeWide / 15% / `int[3]` / legacy labels); new
  `tests/bank_distribution_check.py` extracts the real SQL from `QuizDb.java`
  and exercises the bank (9 grades x 4 pools x 60 draws, two 5000-draw
  distribution checks, zero-OTHER check), wired into `[0/9]`. A regex-trap in
  the build gate was fixed (`grep -qF` for `new int[Subjects.COUNT]`).
- Verification: `LAYOUT_CONTRACT_OK total=69`, `JVM_TESTS_OK total=197`
  (new `SubjectWeightsTest`, 19 assertions), `BANK_DISTRIBUTION_OK`
  (grade 3: 42.54/24.60/16.06/16.80, grade 9: 42.26/24.76/15.26/17.72,
  expected 41.67/25.00/16.67/16.67), `MUTATION_SUITE3_OK 16/16`,
  `BUILD_OK version=1.0.1 code=15`, v1/v2/v3 signatures valid. TV DPAD, IME
  and font-scale checks still need an on-device pass.

## v1.0.1 (versionCode 14) — home grid & single-column settings

Display version stays **1.0.1** (no version-number churn); `versionCode` moves
13 -> 14 so `adb install -r` still upgrades, signed with the same release
keystore (SHA-256 `76eb6063b574…26172`).

- **Home grid 3x2.** Six action buttons move from 2 columns x 3 rows to
  3 columns x 2 rows. `actionButton()` now uses `weight=1` equal-width cells
  instead of a hardcoded 190dp; the title row grows to 46dp to avoid clipping.
  `wireGrid()` wires explicit `(row, col)` DPAD neighbours in all four directions.
- **Settings single-column card flow.** The three side-by-side `ScrollView`s are
  replaced by one centred gutter column with five numbered cards (quiz basics /
  subjects / boot & playback / wrong-book / online source). Each row is a fixed
  label plus a value button that fills the column by weight; notes wrap instead
  of being clipped. Change-PIN and Save move into a fixed footer bar.
- **Focus.** `wireVerticalFocus()` links focusable controls row-by-row across
  nested cards; `wireSubjectRows()` still handles edition <-> weight, and the
  URL field plus footer buttons join the chain. `wireColumns()` / `addColumn()`
  are removed.
- **Independent review hardening.** A read-only review found no P0 and one
  P1: `build.sh` verified signature validity but not signer identity, so a
  default build silently produced an APK that cannot upgrade an installed
  release. `[8/9]` now pins and compares the APK certificate SHA-256
  (`76eb6063b574…26172`) and exits on mismatch; local experiments must opt in
  with `POHW_ALLOW_DEV_SIGN=1`. Additional fixes: undefined left/right focus
  targets are pinned to self (EditText keeps cursor keys), row buttons use
  `WRAP_CONTENT` + 44dp min-height for large font scales, and the contract now
  covers `setFillViewport`, `Ui.fill(1)` and wrap-content scroll children.
- **Gates.** `tests/layout_contract.py` adds 40 source-level assertions wired
  into `[0/9] static guards`; call counts run on code stripped of both `//`
  and `/* */` comments via a block state machine. Eight negative mutations
  (2-column regression, legacy column helper, line/block-commented focus
  chains, removed fillViewport, fixed-height scroll area, wrong cert pin,
  dev-signed build) were all rejected by the contract or `build.sh`.
- Verification: `LAYOUT_CONTRACT_OK total=40`, `JVM_TESTS_OK total=178`, bank
  verifier PASS, full `aapt2 -> javac -> d8 -> zipalign -> apksigner` build,
  v1/v2/v3 signatures valid, `versionCode='14' versionName='1.0.1'`,
  `MUTATION_SUITE2_OK 8/8`. TV DPAD behaviour, font scale 1.5 and the URL
  field IME still need an on-device pass.

## v1.0.1 (versionCode 13) — console & settings UI consolidation

- **Merged guard toggle.** `btnStart`/`btnStop` replaced by a single `btnGuard`.
  `GuardState.toggleColor()` / `toggleLabel()` drive the state: idle = ON_GREEN
  `#1D9E75` + "开启守护", running = STOP_RED `#A32D2D` + "关闭守护"; focus
  highlight stays BLUE. Colour now expresses the *action currently available*
  instead of a disabled state. The four legacy methods and `mutuallyExclusive()`
  are kept for the existing JVM suite; `toggleDistinct()` and
  `toggleAlwaysAccented()` are new. Stopping still passes through the in-place
  PIN overlay and never leaves the page.
- **Bank update entry moved.** The duplicate home-screen "检查题库更新" button is
  gone. `SettingsActivity` already had the same action ("立即检查更新"); it is
  renamed "检查题库更新", sits with the online-source URL/status card, and now
  inherits the empty-URL hint from the removed home button. The console keeps
  only read-only bank status lines.
- **Subject rows consolidated.** `addSubjectRow()` renders one row per subject as
  `name | edition | weight` with two independently focusable buttons. The three
  weight rows in column 1 and four edition rows in column 2 (7 rows) become 4.
  "其他学科" has no independent weight and shows a disabled dash. Columns are now
  "quiz basics + subjects" / "boot, playback + wrong-book" / "online source +
  account".
- **TV focus chain.** `wireColumns()` still links columns; `wireSubjectRows()`
  then overrides intra-row left/right so DPAD moves between edition and weight,
  while the row's outer edges still cross columns.
- **Gates & tests.** New semantic build guards assert `GuardState.toggleColor`,
  `GuardState.toggleLabel`, `toggleGuard()`, `addSubjectRow`, `wireSubjectRows`,
  and forbid a home-screen bank-update button. `StopStateTest` gains 4
  assertions: **178/178 total** across 7 suites.
- Version 1.0.1 / versionCode 13 (PATCH: UI consolidation, no new capability).
  Same externally-supplied keystore, so `adb install -r` works.

## v1.0.0 (versionCode 12) — first stable release

Two independent reviews of the dev build labelled v6.0.0 (feature acceptance + Android/AOSP compliance)
both concluded "do not ship as 5/5 complete". This release fixes the P0/P1 items
and adds the missing behaviour-level tests.

- **P0: overlay window focus state machine.** The overlay now reports
  `onWindowFocusChanged(false)` to the service; the service re-applies
  `WindowManager.LayoutParams` up to 6 times and rebuilds the window once
  (`remove` + `add` of the same View, preserving `QuizFlow` state). Documented
  that clearing `FLAG_NOT_FOCUSABLE` does NOT guarantee the WMS focused window.
  On exhaustion the quiz stays visible (never auto-unlocks) and touch/mouse
  clicks still work.
- **P0: foreground-service 5s contract on every branch.** `startForeground()`
  moved to the top of `onStartCommand()`, covering STOP, rejected STOP, missing
  overlay permission and notification-build failure. `onDestroy()` explicitly
  calls `stopForeground(true)`.
- **Critical: the new `QuizFlow` was wired but never advanced.** `flow.submit()`
  was never called, so the "continue watching" button stayed unclickable and
  disabled options never stuck. Judging now runs through
  `QuizOverlayService.answer()` -> `flow.submit()`, and `bind()` calls `reset()`
  on every question swap. The service no longer keeps its own `wrongAttempts`.
- **Correct-answer confirmation timing.** `onContinue()` is posted via
  `root.post()` instead of removing the window synchronously inside the key /
  click dispatch stack.
- **P1: one-shot STOP token.** The client-side `EXTRA_STOP_VERIFIED` boolean is
  gone. After PIN verification the app issues a random 24-byte token stored with
  `commit()` (60s TTL, deleted on every consume attempt, compared with
  `MessageDigest.isEqual`); the service consumes it before `GuardPolicy`.
  `Build.VERSION.SDK_INT >= 26` uses `startForegroundService` for STOP.
- **P1: boot receivers split.** `BootReceiver` handles only protected
  `BOOT_COMPLETED`; a new `QuickBootReceiver` handles vendor quickboot actions
  without the `RECEIVE_BOOT_COMPLETED` sender permission. Android 12+ may refuse
  to start an FGS from quickboot (not in the exemption list) - failures are
  explicitly caught and logged; `BOOT_COMPLETED` remains the reliable path.
- **P1: `KeyRouter` (pure Java).** Volume DOWN/UP pass through symmetrically;
  long-press repeats are suppressed for confirm / number / DPAD keys; BACK and
  media keys are swallowed. 2x2 highlight adjacency is unit-tested.
- **P1: audio focus API.** `MediaPauseController` uses `AudioFocusRequest` +
  a non-null listener on API 26+ (deprecated 3-arg API only on 21-25). When a
  player was paused by focus loss, abandoning focus is enough - no extra
  `MEDIA_PLAY` that could wake an unrelated MediaSession or double-toggle.
  `resumePlayback=false` is documented as best-effort.
- **P1: online packs were dead code.** `QuizEngine.next()` now mixes built-in
  SQLite questions with edition-filtered online packs (`BANK_RATIO`), routes
  `Subjects.OTHER` packs, and `Question.key()` is length-prefixed and includes
  subject + answerIndex so different-answer questions do not merge in the
  wrong-book.
- **P1: network / settings.** Automatic redirects disabled, `Location` manually
  validated to the same host (max 3 hops), streams always closed; the settings
  URL field is no longer wiped by `refreshRows()`; cleartext preset changed to
  `127.0.0.1` (allowed by network security config); explicit DPAD focus links
  between the three columns.
- **P1/P2: wrong-book & console.** `record()` wraps read+write in a transaction
  and refreshes the summary only after success; `onUpgrade()` is non-destructive;
  first open moved off the main thread; the header always uses the true
  `COUNT(*)`, while the list is capped at `LIST_LIMIT=500` with an explicit
  truncation hint; clear confirmation auto-disarms after 5s. Main console caches
  bank stats / overlay permission instead of re-querying SQLite every second,
  compares strings before `setText`, and reuses field Runnables that are all
  removed in `onDestroy()`; the PIN overlay takes focus itself and blocks
  descendant focus on the underlying console buttons.
- **Tests & gates.** New `BehaviorV601Test` (51 assertions: key timing, quiz
  state machine, one-shot token TTL/replay/process-restart). `tests/run.sh`
  compiles `KeyRouter` + `QuizFlow` and runs 7 suites: **174/174 total**.
  Build static gates now assert behaviour symbols rather than exact line text.
- The final APK is rebuilt from the finished source (the old v6.0.0 dist APK
  predated these fixes); `classes.dex` contains `refreshAsync`, `wireColumns`,
  `setUrlText`, `consumeStopToken` and `rebuildOverlay`.
- Version 1.0.0 / versionCode 12. Release artifacts are signed with the same
  externally-supplied keystore as all earlier dev builds so `adb install -r` works;
  the default (no `POHW_KEYSTORE`) still generates a random dev key and does
  NOT guarantee signature continuity.

## Dev build v6.0.0 (versionCode 10) — real-device feedback from a Xiaomi TV

- **Fix: overlay lost remote-control input on the second quiz.** The service now
  creates a fresh `OverlayQuizView` per quiz instead of re-adding the same View,
  explicitly clears `FLAG_NOT_FOCUSABLE`, and routes DPAD / ENTER / number keys
  inside the root view's `dispatchKeyEvent` with manual highlight painting. Focus
  no longer depends on the window's child-view focus state.
- **Pause background video while answering.** `MediaPauseController` requests
  transient audio focus and dispatches `KEYCODE_MEDIA_PAUSE` when the quiz shows,
  then abandons focus and optionally dispatches `KEYCODE_MEDIA_PLAY` on unlock.
  Media keys are swallowed by the overlay while the quiz is on screen. This is
  best-effort by design: Android has no public API to force-pause a third-party
  player, and no extra permission is requested.
- **Per-subject textbook editions.** Four independent `edition_s0..3` settings
  (math / Chinese / English / other), with the old single `edition` value used as
  the migration default. `BankRepository.load(grade, String[])` filters optional
  online packs by subject edition; quiz metadata and wrong-book entries carry the
  subject's edition. The built-in offline catalog has no edition column, so
  offline draws remain edition-agnostic (documented).
- **Boot autostart controls.** `bootAutostart` (default on, v5-compatible) and
  `autoGuardOnBoot` (default off) switches, a pure-Java `BootPolicy` action
  allow-list, and `BootSettings` opening OEM autostart screens (MIUI / ColorOS /
  EMUI / Funtouch) with a fallback to the app details page.
- **Wrong-answer swap and wrong-question book.** Two wrong attempts on the same
  question swap to a fresh question in place (no window re-attach) and record the
  original into a separate writable `pohw_wrong.db`, deduplicated by a SHA-256
  key of stem + options. `WrongBookActivity` gives a TV-friendly paged review
  with correct answer, last wrong pick, explanation, delete and two-step clear.
  The console shows the count and an entry button.
- Fresh question draws now avoid immediately repeating the previous question.
- Settings screen reorganized into three independently scrollable columns so all
  new rows stay reachable with a TV remote.
- Added `Hashes`, `Subjects`, `AnswerPolicy`, `BootPolicy`, pure-Java policy
  classes and 45 new JVM assertions (`FeatureV6Test`): **123/123 total**.
- Build static guards extended with v6 regression checks; version bumped to
  6.0.0 / versionCode 10. Signing certificate is unchanged from v4.x/v5 for
  in-place `adb install -r` upgrades.

## Dev build v5.0.0 (versionCode 9) — PoHW / full offline catalog

- Renamed the project to **PoHW (Proof of Homework)**; version label is now read
  from `PackageManager` at runtime instead of being hardcoded.
- **Full offline catalog**: all 4,319 unique knowledge points from
  `RedSamerai/learning-content` (19 subjects, 21 grade bands) are imported into
  `app/assets/quiz.db`, producing 5,129 locally verifiable four-choice questions.
  See `docs/BANK_STATS.md`.
- Added grade 7-9 support and an optional "all subjects" draw mode, so the full
  K12 catalog is actually reachable from the parent settings.
- **Removed all third-party / paid question packs** from the APK. Online bank
  sources are now strictly opt-in: the parent must enter a URL in settings,
  otherwise the app never touches the network.
- Hardening: `allowBackup=false`, cleartext HTTP disabled by network security
  config, boot receiver validates actions, foreground-service strings moved to
  resources, built-in DB copy is versioned + atomic + fsync'd.
- Build is now reproducible from the repository root (`app/build.sh` +
  `tests/run.sh`), with 78 JVM unit tests and an independent bank verifier.
- Security hardening after an independent Android review: PBKDF2 PIN derivation
  (legacy hash migration), mandatory SHA-256 on downloaded packs, cross-host URL
  rejection, foreground-service contract on rejected STOP, background DB open,
  dedicated notification icon, randomly generated local signing key, and removal
  of unused permissions.

## v4.4.0 (versionCode 8)
- In-place PIN verification overlay when stopping the guard; toggle buttons
  change color/label instead of leaving the screen; dynamic version display.
- Fixed the zombie overlay window on `addView` failure.

## v4.3.0 (versionCode 7)
- Stopping the guard requires the parent PIN, enforced a second time in the
  service (`GuardPolicy`).

## v4.2.0 (versionCode 6)
- Fixed clipped topic text on the quiz overlay; adaptive question font size.

## v4.1.0 (versionCode 5)
- Fixed PIN setup (synchronous commit, mode reset, hardware number keys).

## v4.0.0 (versionCode 4)
- Switched to a built-in SQLite question bank; random local draw, no API.

## v1 - v3
- Demo APK, cross-app overlay, online bank cache, parent password experiments.
