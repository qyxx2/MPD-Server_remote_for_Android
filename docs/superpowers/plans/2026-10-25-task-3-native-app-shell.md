# Task 3：原生应用壳、导航、主题与通用状态 UI Implementation Plan

> **For agentic workers:** 使用 `superpowers:executing-plans` 按 Batch 实施；每个 Batch 测试先行、单独验证和提交。Task 3 UI 范围内可以实施，不意味着 Task 2 最终集成退出门已通过。

**Goal:** 建立四入口原生导航、共享 App Shell、迷你播放器展示投影、应用内状态环/活动详情、主题和非业务偏好，并始终复用 Task 2 唯一共享运行时。

**Architecture:** 单一 `app` 模块，Compose UI 按导航/壳/组件/主题分包。Application 创建唯一 `AppRuntime`；UI 只观察快照、连接状态和共享活动，不直接创建 transport，也不向 MPD-Server 发写请求。真实页面业务由 Task 4–7 完成。

**Tech Stack:** Kotlin、当前 Gradle Wrapper 8.14.6、AGP 8.10.1、Kotlin/Compose plugin 2.0.21、Compose BOM 2025.06.00。Task 3 的依赖盘点结果与后续版本冻结如下：Navigation Compose `2.9.0`（Batch 2）；Preferences DataStore `1.1.7`（Batch 1）；Lifecycle Runtime Compose `2.9.1`（使用 `collectAsStateWithLifecycle`，Batch 1/2）；Compose UI Test (`ui-test-junit4`、`ui-test-manifest`) 由已有 Compose BOM `2025.06.00` 管理；AndroidX Test Ext JUnit `1.2.1`、Test Runner `1.6.2`（Batch 2 配置 instrumentation runner）。这些依赖当前均未在 catalog/应用模块直接声明；Batch 0 不改工程依赖，首次引入的 Batch 必须执行 Gradle 解析/编译验证，若不能解析则依据当前工具链作最小修订并记录 ruling，不得升级无关依赖。

**Spec:** `docs/superpowers/specs/2026-10-25-task-3-native-app-shell.md` 与 `docs/superpowers/specs/2026-10-25-task-3-visual-design-specification.md`；同时遵守 `docs/superpowers/plans/2026-10-24-android-client-master-plan.md` 和四份权威产品/API/行为/边界规格。视觉参数以 Task 3 视觉设计规格为准，不能改变 API/Web 既有行为语义。

## Global Constraints

- `applicationId` / `namespace`: `io.github.qyxx2.mpdserver.remote`；minSdk 26；原生 Jetpack Compose + Material 3；禁止 WebView。
- MPD-Server 是唯一业务状态来源；不新增 Room/SQLite、不复制 Queue/AutoPlay/History/Collection/Playlist/Favorites/reconciliation、不直连 MPD TCP。
- 一个 app-scoped `AppRuntime`、SnapshotStore、RealtimeController、MutationCoordinator；页面/ViewModel 不创建副本。
- HTTP/WS 共用由后续设置功能配置的 origin；默认端口仍是 18000；本 Task 不实现 origin 设置表单或真实写行为。
- stale/unknown、未接纳完整 snapshot、未确认播放观察和禁用 controls 必须诚实展示；禁止假数据和假成功。
- 只在指定 Ubuntu 仓库本地构建/测试/Lint；不增加 CI/远端构建，不修改服务端或 Web。

## Review Focus

- 导航切换/全屏进退导致 runtime/socket/coordinator 重建：Task 2 的 Application owner identity test + Batch 2/3/6 UI test。
- 设置页错误地展示 mini，或 mini/导航覆盖正文：Batch 3/4 UI test 与人工屏幕验收。
- 没有完整 snapshot / stale / unknown 时仍展示可用播放动作：Batch 4/5 状态投影测试。
- 主题偏好覆盖系统、进程重建后丢失或写到服务端：Batch 1 偏好/主题测试。
- UI 将缺失的数据伪装成推荐、播放或连接成功：Batch 3/4/5 空状态断言与静态边界检查。

---

## Batch 0：合同映射、精确接口与 UI 测试设施盘点

**目的：** 从既有四份权威规格及 Task 2 实现，确定 UI 会消费的最小接口和稳定测试 seam。仅文档/检查，不写产品 UI 功能代码。

**检查文件：**
- `docs/superpowers/specs/2026-10-25-task-3-native-app-shell.md`
- 产品总规格 §§5–8、12–13；架构合同 §§2、4–7；Web 行为合同 §9；Task 2 合同 §§2、5.2、5.3、9
- `MainActivity.kt`、`MpdServerRemoteApplication.kt`、`AppRuntime.kt`、`SnapshotStore.kt`、`RealtimeController.kt`、`MutationCoordinator.kt`、Gradle version catalog 和现有单测

- [x] 建立合同矩阵映射：Task 3 合同 §3 的每一条不变量均对应具体 Batch、自动测试通过条件及目标真机验收项。
- [x] 冻结 UI 只读接口：Application 的唯一 AppRuntime；SnapshotStore.state 提供 canonical snapshot/connection/freshness/error/controls eligibility，MutationCoordinator.activities 提供共享活动；UI 不创建 owner，不启动 runtime、不改 origin、不提交或重试 mutation。
- [x] 完成依赖盘点：当前无 Navigation Compose、Preferences DataStore、Lifecycle Runtime Compose、Compose UI Test 或 AndroidX Test runner；版本决策与首次引入批次已写入本计划，实际解析/编译验证归各依赖引入 Batch。
- [x] 记录分支 HEAD、SDK 工作区准备、基线命令、JUnit 数量、Lint 和 APK 结果到 .superpowers/sdd/2026-10-25-task-3-native-app-shell/progress.md。
- [x] `git diff --check` 通过；计划/合同接口映射和 Batch 边界经复核后单独提交。

## Batch 1：主题 tokens 与本地主题偏好

**目标：** 统一 Follow System / Light / Dark 主题行为，符合已冻结的浅/深色语义色与可读性要求。

**Files (mapped and frozen in Batch 0):**
- Create/Modify: `ui/theme/Color.kt`, `Theme.kt` 或当前主题文件
- Create: 最小主题偏好模型/存储适配，使用 Preferences DataStore `1.1.7`；同时引入 Lifecycle Runtime Compose `2.9.1` 以 lifecycle-aware 收集共享 StateFlow
- Test: theme mapping、默认跟随系统、显式浅/深色、偏好恢复

- [x] 先写主题映射与偏好行为测试并观察失败。
- [x] 实现语义色 token 和 Follow System / Light / Dark 状态。
- [x] 让启动入口从共享 UI preference 读取主题；系统主题变化时 Follow System 正确更新。
- [x] 运行主题相关单测/必要 UI test，再运行 `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`。
- [x] `git diff --check` 并提交 Batch 1。

## Batch 2：四入口导航与返回栈

**目标：** 用固定顺序和名称实现主页、搜索、音乐库、设置四个根入口，并能进出独立全屏播放器 route。

**Files:**
- Modify: `MainActivity.kt` 或专用导航宿主
- Create: `ui/navigation/AppDestination.kt`、`AppNavHost.kt`（按 Batch 0 映射调整）
- Create: 占位 screen composables；在 app/src/androidTest 加入 Compose UI instrumentation tests，并配置 AndroidJUnitRunner 与已冻结的 AndroidX Test 依赖

- [x] 编写根入口顺序/label/selected state、根入口互切、当前入口重复选择的 Compose UI 测试；测试源成功编译。真机/模拟器运行仍待执行。
- [x] 编写从每个根入口进入全屏播放器再返回、恢复原根返回栈及 runtime owner identity 的 Compose UI 测试；测试源成功编译。真机/模拟器运行仍待执行。
- [x] 实现导航图；全屏 route 为独立目的地，不做第五个根入口。
- [ ] 在 Android 设备/模拟器运行导航 instrumentation tests。当前工作机 `adb devices -l` 无设备且未安装 `emulator`，因此未运行；不以单元测试或编译代替 UI 执行验收。
- [x] 完整单测、Lint、Debug 构建、instrumentation test 编译和 `git diff --check` 已通过；保留固定依赖版本与其新版本提示，不升级无关依赖。提交后记录 Batch 2 本地提交。

**Batch 2 设备验收阻塞（未冒充通过）：** 本工作机没有已连接的 ADB 设备/模拟器，且 `emulator` 可执行文件不存在。`AppNavigationUiTest` 已编写并通过 `compileDebugAndroidTestKotlin`，但没有执行 instrumentation tests 或人工真机验收；以上两项仍是本 Batch 未闭合的验证门。

## Batch 3：共享 App Shell 与根页面布局

**目标：** 实现紧凑 AppBar、全局状态入口 placeholder、正文容器、底部导航和系统安全区布局。

**Files:**
- Create: `ui/shell/AppShell.kt`、`AppBar.kt`、`RootNavigationBar.kt`（按 Batch 0 映射调整）
- Create/Modify: 四个根页面空状态布局
- Test: 页面层级、按钮语义、正文不被迷你播放器/导航覆盖

- [x] 先写 Compose UI tests：根页面有 AppBar 和根导航；正文在可布局范围；无迷你播放器时无额外空白。增加共享壳、正文滚动语义、固定导航边界、48dp 目标和全屏隐藏断言。
- [x] 实现紧凑 app bar、根 navigation、正文 slot，确保 mini 由后续 Batch 作为正常布局节点插入。
- [x] 设置/搜索键盘预期行为按规格保留空间并让正文滚动，不改变系统字体缩放；四个根页面使用可滚动正文并保留系统字号缩放。
- [ ] 真机/模拟器运行 Compose UI tests 并检查系统安全区、键盘、大字体、短屏和窄屏。当前 `adb devices -l` 无设备，且 `emulator` 不存在；不能用测试源编译替代运行验收。
- [x] 完整单测、Lint、Debug build、instrumentation test 源编译和 `git diff --check` 通过并提交。最终验证日志与未完成的设备门记录于 `.superpowers/sdd/2026-10-25-task-3-native-app-shell/progress.md`。

## Batch 4：迷你播放器展示投影

**目标：** 在主页/搜索/音乐库页展示唯一共享状态的迷你播放器；设置页隐藏。仅展示，不写播放 API。

**Files:**
- Create: `ui/shell/MiniPlayer.kt`、共享状态到 UI presentation mapper
- Test: no snapshot、no current song、fresh/paused/playing、stale/unknown、settings hidden、button disabled

- [x] 先为空/current/stale/unknown、fresh/playing/paused 及设置显隐写投影/UI 测试；覆盖缺失/畸形标题、last-known stale 状态和播放控件 disabled。
- [x] 实现 64dp 固定高度迷你播放器、48dp 封面占位、16sp 单行曲名（不显示艺术家）与单一播放/暂停视觉位置；不显示进度/上一首/下一首。当前快照 DTO 未定义封面字段，因此使用原生占位图，不猜造 artwork URL。
- [x] Task 4 尚未接入播放动作，播放/暂停控件明确 disabled 并标注暂未接入；按钮与进入全屏播放器的主体点击区域分离，避免禁用动作触发导航。
- [x] 状态只从 Application 所有的 app-scoped runtime `SnapshotStore.state` 以 lifecycle-aware 方式读取；运行单测、Android instrumentation 源编译、Lint、Debug build 与 `git diff --check`。
- [x] 无需 MS 服务即可完成的真机验证：无线调试 Samsung SM-G986N，安装并启动 Debug APK；instrumentation `AppNavigationUiTest` 6/6 通过。手工核验主页显示空曲目/状态未确认及禁用播放键、设置页隐藏 mini、进入全屏后 mini/根导航隐藏、系统返回恢复主页与 mini。证据保存于 `.superpowers/sdd/2026-10-25-task-3-native-app-shell/device-evidence/`。

## Batch 5：状态环、活动详情与共享 runtime 投影

**目标：** 用统一 UI 呈现连接状态、snapshot freshness 与共享活动概览。状态与活动必须只投影既有 runtime owner；不得启动连接或执行写操作。**注意：本 Batch 最初按旧视觉规格将状态环放在 AppBar 右侧；2026-10-26 用户已明确改为 edge-to-edge 透明状态栏并围绕居中挖孔显示，故本 Batch 原交付不能代表新视觉要求已完成，须由后续明确批次修正。**

**Files:**
- Create: `ui/components/PlaybackStatusRing.kt`、`ActivityStatusSheet.kt`（名称可按实现调整）
- Test: empty/error/unknown activity mapping、open/close sheet、screen navigation owner stability

- [x] 先写 `ActivityPresentationTest` 映射测试并观察缺少 `ActivityVisualState`/`ActivityPresentation` 的预期编译失败；随后实现纯映射模型并通过 11 项状态/活动断言。
- [x] （旧方案，待替换）将 AppBar 的全局状态入口替换为 36dp 状态环；该位置决策已被 2026-10-26 用户新要求取代，不得作为最终视觉验收结果。共享 runtime 投影和活动详情的只读边界仍然有效。
- [x] 详情面板可通过状态环打开、关闭按钮/完成按钮/系统返回关闭；不启动 runtime、不提交 mutation、不重试 UNKNOWN。DISCONNECTED、CONNECTING、UNKNOWN、STALE、PENDING、SYNCING、FAILED、SUCCESS、INFO、IDLE 使用不同的文字/符号/环形状态；活动列表最多展示最近 20 条并限制列表最大高度，避免活动较多时挤出详情面板的关闭入口。由于共享 `MutationStatus` 未定义 RETIRED，不伪造该状态。
- [x] 已按旧 AppBar 方案运行完整单测、Lint、Debug APK、instrumentation 源编译和无线真机 Compose UI 测试；7/7 instrumentation tests、86/86 unit tests 通过。上述结果只证明旧方案代码/行为测试通过，不证明新要求的挖孔定位、edge-to-edge 或活动结束 5 秒隐藏已实现。
- [x] **后续专门修正批次 A（状态环定位与生命周期；先于 B）：代码与自动化门完成；实际活动环的真机视觉检查有明确阻塞项，见下方执行记录。**
  - **范围/流程：** 先核对当前 Window/Compose 布局与共享活动投影；先写失败测试，再实现 edge-to-edge、透明状态栏、WindowInsets/DisplayCutout 几何解析、锚点与安全边界计算、活动显示/隐藏生命周期和独立活动详情入口。不得顺手实现设置 UI 或持久化偏好。
  - **定位纯逻辑必须可独立测试：** 将 DisplayCutout 几何到锚点/环形布局参数的计算与 Android Window 读取、Composable 渲染分离。即使设置页尚未实现，也必须以纯单元测试覆盖：有效几何的居中锚点；缺失/空/不适用/不可靠几何进入手动校准回退；水平/垂直偏移正确叠加；偏移或尺寸超界时受安全边界约束；不得把屏幕中心猜测值伪装成系统挖孔锚点。测试输入中的偏移可用规格默认值及边界代表值，不依赖 B 的设置控件。
  - **生命周期：** 活动存在时显示；所有可自动结束的活动/短暂结果提示结束后计时 5 秒隐藏；计时期间新活动到来须取消旧计时并重置 5 秒；未解决 UNKNOWN/FAILED 不得因定时器清除或伪装成解决；后台时不创建跨应用覆盖层。活动详情必须有独立可访问的 App 内入口，不能依赖会隐藏的环。
  - **自动化通过条件：** 几何映射、回退分支、偏移叠加/边界裁剪、5 秒计时边界、计时取消与重置、UNKNOWN/FAILED 保留语义均有确定性单测；edge-to-edge/insets 的窗口行为有可运行的 Android/UI 测试或明确记录为设备专属门；运行相关单测、Lint、Debug 构建和 instrumentation 测试（无法运行时如实标 BLOCKED，不以编译代替）。
  - **A 的实机基线验收：** 在目标真机截图/检查透明状态栏、内容延伸到状态栏背景、系统图标与挖孔安全避让、默认可用锚点位置、活动显示/结束 5 秒隐藏/新活动重置、UNKNOWN/FAILED 不消失、独立详情入口可访问。设备没有可靠 DisplayCutout 时，应确认进入安全手动回退且仍可调整，而非要求自动定位凭空成功。
  - A 只有在代码、自动化测试及其机器验证结果记录完成，且实机基线项通过或逐项明确阻塞原因后才可标记完成；不得把旧 Batch 5 的完成声明当作 A 的证据。
  - **A 执行结果（2026-10-26）：** 已完成代码、TDD、97/97 单测、Lint、Debug APK、instrumentation 源编译与 SM-G986N Android 13 真机 8/8 UI 测试。edge-to-edge/Insets、活动详情独立入口和 idle 不显示状态环已验证，截图及完整细节见 `docs/superpowers/reports/2026-10-26-task-3-batch5a-verification.md`。**阻塞项：** 当前共享 Runtime 没有真实活动且测试设备未连接可用 MS 服务，故真实活动期间环的物理挖孔周边视觉、5 秒后的真机消失、新活动重置及 UNKNOWN/FAILED 持续显示未做真机视觉验收；由确定性单测覆盖逻辑，不冒充已实机验证。设置 UI/偏好持久化留给 B。
- [x] **后续专门修正批次 B（状态环设置；依赖 A 的定位/渲染接口）：**
  - **范围/流程：** 在设置页增加“状态环”分组，按视觉规格 §10.4 提供尺寸缩放、线宽、水平偏移、垂直偏移、实时预览与恢复默认值。先写偏好仓库/状态模型和 Compose 交互测试，再接入本地 Preferences DataStore；仅存储这四项外观参数，不写服务端，不创建业务状态副本，不更改共享 Runtime/OperationActivity/snapshot。
  - **参数权威：** 默认值、范围、步进、锚点语义与安全限制一律引用 `docs/superpowers/specs/2026-10-25-task-3-visual-design-specification.md` §10.4；不在计划/合同或实现中维护第二套数字定义。B 通过 A 暴露的定位/渲染接口应用设置，不复制一套几何/生命周期逻辑。
  - **预览边界：** 预览明确标注为预览，仅改变环的外观/位置参数；不得伪造活动、调用 Runtime mutation、改变 canonical snapshot、连接状态、活动历史或真实环的生命周期计时。预览与真实共享状态使用相同的绘制/参数转换规则，但预览活动样例只能是 UI 本地固定展示状态，不能写入共享状态。
  - **自动化通过条件：** 覆盖首次默认值、每项合法范围/步进映射、偏好重启/重建后的持久化、恢复默认值写回与 UI 更新、预览随控件实时更新、预览不修改 Runtime/活动集合、A 的自动锚点叠加用户偏移、无可靠几何时手动校准仍生效，以及尺寸/偏移不越过安全边界。运行相关单测、Compose UI/instrumentation、Lint、Debug 构建；未实际运行的测试不得写成通过。
  - **B 的实机基线验收：** 四项控件可操作、数值/步进与 §10.4 一致、拖动时预览即时更新、恢复默认值立即回到默认预览、退出重进设置或重启应用后偏好保留；检查状态环仍避让摄像头/系统图标且详情入口可用。
  - B 只能在 A 的接口与基线行为稳定后实施；若 A 的自动化或设备安全边界尚未通过，B 可以实现独立偏好层测试，但不得宣称整体状态环验收通过。


  - **B 执行结果（2026-10-26）：** 已实现四项外观偏好、Preferences DataStore 持久化、共享定位/绘制接口预览、恢复默认值；预览不修改 Runtime、canonical snapshot 或共享活动集合。105/105 单测通过，Lint、Debug APK、instrumentation 编译通过，SM-G986N / Android 13 真机 10/10 UI 测试通过。四项滑块在实机上均可操作；`1.15× / 4.5dp / 10dp / −6dp` 在强制停止并重启应用后保留，重置后回到 `1.00× / 3.0dp / 0dp / 0dp`。证据与命令日志见 `docs/superpowers/reports/2026-10-26-task-3-batch5b-verification.md` 和 `.superpowers/sdd/2026-10-25-task-3-native-app-shell/device-evidence/`。**完整校准验收仍阻塞：** 没有真实活动/MS 服务进行生产环视觉验收，也没有物理无可靠挖孔场景；回退路径目前由确定性单测覆盖，不宣称完整物理校准验收通过。

**分层验收定义（A/B 均适用）：**
1. **自动化门：** 纯逻辑单测、相关 Compose UI/instrumentation、Lint、Debug 构建与 `git diff --check`；按上面每批明确的覆盖点逐项报告，测试源编译不等于测试执行通过。
2. **实机基线门：** 在目标设备检查窗口/系统栏安全、默认定位、活动生命周期、控件交互与偏好持久化；记录设备型号/系统版本、测试步骤、实际结果及截图证据。无法执行时保留 BLOCKED，不得用模拟器或静态审查冒充真机结果。
3. **完整手动校准门：** 至少在 DisplayCutout 缺失/不可靠的设备或可控测试条件下验证回退路径；确认水平/垂直偏移能实际校准、尺寸/线宽设置生效、重启后设置保留、环不遮挡摄像头/系统图标。若当前设备无法构造缺失/不可靠几何场景，记录未覆盖的条件和理由，不能宣称完整校准验收通过。
4. **旧 Batch 5 隔离：** Batch 5 的 86 个单测、7 个 instrumentation 测试和旧 AppBar 状态环截图仅是旧方案基线；无论这些结果是否复用，都不能替代 A/B 的新增测试、edge-to-edge/挖孔定位/5 秒生命周期/偏好预览与持久化验收。

## Batch 6：全屏播放器占位目的地与综合导航回归

**目标：** 校验全屏目的地与根壳分离，不提前实现 Task 4。

**Files:**
- Create/Modify: FullPlayerPlaceholder route/screen
- Test: full-screen hides root nav/mini, back returns previous root, runtime identity unchanged

- [x] 先写目的地进退与根壳显隐测试。
- [x] 实现简洁明确的“播放器界面将在后续阶段接入”占位内容，不显示假的专辑/歌词/进度/队列。
- [x] 对四入口逐一覆盖 full-screen 进入/返回，运行完整验证并提交。

## Batch 7：Task 3 集成验证与验收记录

**目标：** 做合同矩阵审查、机器验证和 UI 人工验收记录，明确 Task 2 的遗留门槛。

**Files:**
- Review: 全部 Task 3 UI/主题/导航代码及其测试
- Modify: 本合同、本计划、`docs/superpowers/plans/2026-10-24-android-client-master-plan.md`、`docs/superpowers/README.md`
- Create/Update: `.superpowers/sdd/2026-10-25-task-3-native-app-shell/progress.md`

- [x] 执行 clean 单测/Lint/Debug 构建，并额外编译、运行 Android instrumentation：`./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 clean testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin connectedDebugAndroidTest`；105/105 单测、SM-G986N Android 13 instrumentation 10/10，退出码 0。日志见 `.superpowers/sdd/2026-10-25-task-3-native-app-shell/task-3-batch7-finalverify.log`。最终文档修改后的 `git diff --check` 在提交前再次执行。
- [x] 边界审查：app 源码/构建声明未发现 Room/SQLite、WebView、Media3/ExoPlayer；UI 源码未发现新增 runtime/store/controller/coordinator/socket owner、连接启动/origin 修改、mutation submit 或 retryUnknown；未修改 CI workflow。最终文档差异的 `git diff --check` 已于提交前通过（退出码 0）。
- [x] 更新主计划：Task 3 机器验证结果与未完成的 UI 人工验收单独记录；Task 2 的真实 mutation/readback 与 APK live-origin 继续保持 BLOCKED，不将其伪装为通过。
- [x] 记录已有用户真机反馈及边界：启动/默认主页、四入口切换、重复选择当前入口、返回原根页面和系统返回已由用户报告通过；用户当时明确“全屏播放器显示：未测”，后续 10/10 instrumentation 覆盖其导航行为但不冒充用户手动验收。主题模式全覆盖、真实活动状态环视觉/生命周期和无可靠挖孔时的物理校准没有完整用户验收反馈，故仍保持未完成。
- [x] 完成自审、验证报告与 Batch 7 本地提交；遵守用户边界，不调用其他编程代理、不合并 main、不 push、不开始 Task 4。自审不等同于独立审查。

**Batch 7 执行结果（2026-10-26）：** 全量 clean Gradle 命令 BUILD SUCCESSFUL，105/105 单测、SM-G986N / Android 13 真机 instrumentation 10/10 通过；Lint、Debug APK、Android-test 编译和边界审查通过。APK 为 12,040,311 bytes；完整日志与 SHA-256、warning、未完成人工验收项见 [Batch 7 集成验证报告](../reports/2026-10-26-task-3-batch7-verification.md)。**退出门区分：** Batch 7 机器验证完成；Task 3 整体仍待用户完整主题/布局验收及真实活动状态环视觉/物理校准验证；Task 2 的真实 mutation/readback 与 APK live-origin 继续 BLOCKED。

---

## 每个 Batch 的固定提交与验收

- 每 Batch 开始前记录当前 branch HEAD；不查询服务端上游。
- 先写/运行相关失败测试，再实现；不以只读代码审查替代能自动验证的行为。
- 每 Batch 只提交自身合同边界；记录命令、结果、测试数、APK 路径与任何 warning。
- 任何合同/范围发现要记录 ruling，不能用实现便利静默改变规格。
