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

- [ ] 测试根入口顺序/label/selected state 和根入口互切。
- [ ] 测试从任一根入口进入全屏播放器再返回，返回到原根入口，runtime owner identity 保持不变。
- [ ] 实现导航图；全屏 route 为独立目的地，不做第五个根入口。
- [ ] 运行导航测试并确认未创建第二个 runtime/controller/store/coordinator。
- [ ] 本 Batch 后运行完整单测、Lint、Debug 构建，diff-check 并提交。

## Batch 3：共享 App Shell 与根页面布局

**目标：** 实现紧凑 AppBar、全局状态入口 placeholder、正文容器、底部导航和系统安全区布局。

**Files:**
- Create: `ui/shell/AppShell.kt`、`AppBar.kt`、`RootNavigationBar.kt`（按 Batch 0 映射调整）
- Create/Modify: 四个根页面空状态布局
- Test: 页面层级、按钮语义、正文不被迷你播放器/导航覆盖

- [ ] 先写 Compose UI tests：根页面有 AppBar 和根导航；正文在可布局范围；无迷你播放器时无额外空白。
- [ ] 实现紧凑 app bar、根 navigation、正文 slot，确保 mini 由后续 Batch 作为正常布局节点插入。
- [ ] 设置/搜索键盘预期行为按规格保留空间并让正文滚动，不改变系统字体缩放。
- [ ] 验证导航 label、语义、48dp 触控目标基线和安全区；运行完整单测/Lint/Debug build，diff-check 并提交。

## Batch 4：迷你播放器展示投影

**目标：** 在主页/搜索/音乐库页展示唯一共享状态的迷你播放器；设置页隐藏。仅展示，不写播放 API。

**Files:**
- Create: `ui/shell/MiniPlayer.kt`、共享状态到 UI presentation mapper
- Test: no snapshot、no current song、fresh/paused/playing、stale/unknown、settings hidden、button disabled

- [ ] 先为空/current/stale/unknown 及设置显隐写投影/UI 测试。
- [ ] 实现封面/内置占位、单行曲名（不显示艺术家）、单一播放/暂停视觉位置；不显示进度/上一首/下一首。尺寸遵循 Task 3 视觉设计规格 §6。
- [ ] 由于 Task 4 尚未接入播放动作，此阶段所有播放/暂停动作保持不可执行，并显示简洁的暂未接入提示或将其作为明确 disabled 控件。
- [ ] 验证状态只由 app-scoped runtime 提供；运行完整单测/Lint/Debug build，diff-check 并提交。

## Batch 5：状态环、活动详情与共享 runtime 投影

**目标：** 用统一 UI 呈现连接状态、snapshot freshness 与共享活动概览。

**Files:**
- Create: `ui/components/PlaybackStatusRing.kt`、`ActivityStatusSheet.kt`（名称可按实现调整）
- Test: empty/error/unknown activity mapping、open/close sheet、screen navigation owner stability

- [ ] 先写模型映射和 Compose 详情面板开关测试。
- [ ] 让单一全局状态入口读取 runtime 的连接/快照/活动事实；活动详情呈现 pending/syncing/unknown/failed/retired/success/info 的已存在状态，不复制活动 owner。
- [ ] 仅提供详情/重新读取入口；本 Task 不实现未知写操作的 retry，也不提交真实 mutation。
- [ ] 测试无连接与 stale 状态，不以绿色/成功视觉暗示服务端已联通；运行完整验证并提交。

## Batch 6：全屏播放器占位目的地与综合导航回归

**目标：** 校验全屏目的地与根壳分离，不提前实现 Task 4。

**Files:**
- Create/Modify: FullPlayerPlaceholder route/screen
- Test: full-screen hides root nav/mini, back returns previous root, runtime identity unchanged

- [ ] 先写目的地进退与根壳显隐测试。
- [ ] 实现简洁明确的“播放器界面将在后续阶段接入”占位内容，不显示假的专辑/歌词/进度/队列。
- [ ] 对四入口逐一覆盖 full-screen 进入/返回，运行完整验证并提交。

## Batch 7：Task 3 集成验证与验收记录

**目标：** 做合同矩阵审查、机器验证和 UI 人工验收记录，明确 Task 2 的遗留门槛。

**Files:**
- Review: 全部 Task 3 UI/主题/导航代码及其测试
- Modify: 本合同、本计划、`docs/superpowers/plans/2026-10-24-android-client-master-plan.md`、`docs/superpowers/README.md`
- Create/Update: `.superpowers/sdd/2026-10-25-task-3-native-app-shell/progress.md`

- [ ] 运行 `./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug` 和 `git diff --check`，保留日志/退出码。
- [ ] 审查无 Room/SQLite、WebView、本地播放引擎、第二个 runtime/socket、真实 mutation、假业务数据或 CI 修改。
- [ ] 更新主计划：Task 3 UI 退出门与 Task 2 真实集成状态分开表述，不伪造 Task 2 通过。
- [ ] 按用户真机反馈记录 UI/导航/主题人工验收；没有用户验收反馈则保留未完成，不宣称通过。
- [ ] 完成代码审查与提交；Task 3 不合并 main，不开始 Task 4。

---

## 每个 Batch 的固定提交与验收

- 每 Batch 开始前记录当前 branch HEAD；不查询服务端上游。
- 先写/运行相关失败测试，再实现；不以只读代码审查替代能自动验证的行为。
- 每 Batch 只提交自身合同边界；记录命令、结果、测试数、APK 路径与任何 warning。
- 任何合同/范围发现要记录 ruling，不能用实现便利静默改变规格。
