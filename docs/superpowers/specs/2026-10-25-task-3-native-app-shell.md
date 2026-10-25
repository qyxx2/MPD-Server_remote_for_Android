# Task 3：原生应用壳、导航、主题与通用状态 UI 合同

> 状态：**Task 3 实施前冻结合同草案，待用户审阅本轮交付结果**。本 Task 仅定义/实现原生 UI 壳与其对 Task 2 共享运行时的投影，不替代 Task 2 的未完成实机验收。
>
> Android 仓库实施基线：Task 2 分支最终证据提交 `6ced024`；对应本地分支 `feature/task-2-network-state-coordinator`。
>
> 固定服务端规格基线：`qyxx2/MPD-Server/main` commit `2ad84454ceb70817bd996c92b590cfa2d9e9b128`。按现行项目决定，不逐 Batch 重新查询上游 HEAD；只有用户要求同步或告知服务端协议变化时才核对受影响合同。

## 1. 目标

用 Jetpack Compose 建立可运行、可测试的原生 App Shell：主页、搜索、音乐库、设置四个固定根入口；设置页之外共享同一个迷你播放器；独立全屏播放器占位目的地；应用内全局状态环与统一活动详情；跟随系统/浅色/深色主题和本地非业务外观偏好。所有实时连接、canonical snapshot 和 mutation owner 均复用 Task 2 的单一应用级 `AppRuntime`。

本 Task 是 UI 结构和通用展示层，不实现完整播放器、曲库/搜索数据流程、歌单收藏、完整服务设置表单或 Android 系统媒体会话。

## 2. 前置判断：Task 2 的残余验收如何处理

Task 2 已具备可编译的网络 transport、严格 DTO、snapshot store、realtime controller、mutation coordinator 与 app-scoped runtime。当前未完成项是：
- 对真实部署进行安全可逆的 mutation/readback；
- 将实际 origin 配置给 Android APK 并完成 APK 级 live-origin 联调。

以上均是 Task 2 **最终集成退出门**，并非 Task 3 UI 壳的硬性技术前置。Task 3 可基于已存在的类型/接口和测试替身推进，不触发真实写操作，不依赖 localhost 占位地址能够连通，也不把 fake/empty 内容表现为真实业务数据。Task 3 首轮根导航、主题、空状态及状态 UI 必须能在无可用服务端时运行。

为避免绕过验收，设置以下门控：
1. Task 3 开始门要求 Task 2 的代码接口、应用级 owner 与机器测试基线存在；此条件当前满足。
2. Task 3 的 UI-only Batch 可按本合同继续完成。不得借机实现真实播放操作、配置服务地址流程、完整业务页面或媒体会话。
3. Task 3 最终退出门要求 Compose/UI 自动测试、构建/Lint、静态边界检查、目标设备的 UI 人工验收完成；Task 2 仍需单独保留 BLOCKED 状态。
4. 任何 UI 动作一旦需要执行写 API，必须留待对应业务 Task；不能因 App Shell 有播放/暂停图标就发起真实 mutation。
5. 完成 Task 3 后，Task 4/6 依赖真实 API 的工作仍以 Task 2 未完成的 live-origin/mutation readback 为风险/集成门。到首次真实写场景之前必须确定安全、可逆并有读回证据的测试动作；不可选择破坏曲库或造成意外播放的操作。

## 3. 权威合同矩阵

| 需求 / 不变量 | 权威来源 | 实现 Batch | 自动化测试与通过条件 | 人工验收 |
|---|---|---|---|---|
| 四个根入口、固定顺序与选中态 | 产品总规格 §§5、7；架构合同 §5 | Batch 2、6 | AppNavigationUiTest：主页→搜索→音乐库→设置的标签/顺序/selected state 正确；重复选择当前根入口不清空 back stack 或重建 runtime | 真机逐一点击四入口，核对顺序、选中态和系统返回行为 |
| App Shell 层级、安全区与 Mini 显隐 | 产品总规格 §§3、5.2、6；架构合同 §5 | Batch 3、4 | AppShellUiTest：AppBar、正文和底部导航同时存在；Mini 作为独立布局节点不覆盖正文；主页/搜索/音乐库显示 Mini，设置隐藏 Mini 但保留导航和状态入口 | 真机检查状态栏/挖孔 inset、键盘、大字体、短屏和窄屏；确认正文不被底部区域遮住 |
| Mini 的曲目投影与禁用动作 | 产品总规格 §6；Web 行为合同 §§3–5 | Batch 4 | MiniPlayerPresentationTest：snapshot 缺失/无 current 时使用明确占位；只从当前 canonical snapshot 映射 title/artist/artwork；stale/unknown 被明确标记；Task 3 的播放/暂停始终 disabled，点击不能提交 mutation；不得生成推荐/假曲目 | 真机检查无服务端时无假曲目、无假成功，播放按钮明确不可用 |
| 独立全屏播放器目的地 | 产品总规格 §5.2、§7.5 | Batch 2、6 | AppNavigationUiTest：从每个根入口进入全屏占位目的地时隐藏 Mini/根导航；返回恢复原根入口；占位页不显示伪造封面、歌词、进度或 Queue | 真机用返回按钮和系统返回手势进退，确认返回原页面 |
| 跟随系统/浅色/深色主题与本地偏好 | 产品总规格 §8；架构合同 §§2–3 | Batch 1 | ThemePreferenceTest：默认 Follow System；系统主题改变时跟随；显式 Light/Dark 不随系统覆盖；偏好重建后恢复；验证偏好仅本地存储 | 真机检查跟随系统、浅色、深色、状态栏图标与文本对比度，并重启应用检查主题保持 |
| 状态环与统一活动详情 | 产品总规格 §5.3；Web 行为合同 §9；架构合同 §7 | Batch 5 | ActivityPresentationTest：MutationStatus 与连接/freshness 的映射无伪成功；ActivityStatusSheetUiTest：详情开合、空状态、pending/syncing/unknown/failed/success/info 显示；导航不丢失共享活动 owner | 真机确认信息不仅依靠颜色、文字可读、详情能关闭；占位 origin 不显示连接成功 |
| Application runtime 唯一所有权 | Task 2 合同 §§2、5.2、5.3、9；架构合同 §§4、6 | Batch 2、5、6、7 | 复用现有 RuntimeCompositionTest 的 owner identity 断言，并增加 UI 导航循环回归；对 ui/** 做边界检查，禁止创建 AppRuntime/SnapshotStore/RealtimeController/MutationCoordinator/RealtimeConnection | 反复切换根页面和播放器目的地，确认共享状态/活动不会因为页面重建而清空 |
| 非业务偏好与禁止创建第二套权威 | 产品总规格 §§4.1、7.4、8；架构合同 §§2–3、6 | Batch 1、7 | UiPreferencesRepositoryTest 与依赖/源码扫描：偏好不走 server API；禁止 Room/SQLite、WebView、Media3/ExoPlayer、业务 snapshot 持久化与假业务数据 | 重启后只保留主题等本地偏好；canonical server snapshot 不被还原成 fresh |
| 可访问性与尺寸适配 | 产品总规格 §§3、8.4 | Batch 3–6 | Compose UI tests 检查导航/按钮语义、状态描述、触控目标至少 48dp、正文可滚动并尊重系统字体缩放 | 目标真机检查大字体、短屏、窄屏、键盘出现时的布局与点击目标 |
| Task 3 不触发真实网络写入或连接 | Task 3 合同 §§2、5–6；Task 2 合同 §§5.2–5.3、9 | 所有 Batch；集中在 Batch 4、5、7 | UI 测试断言 Task 3 预留的播放控件 disabled；静态边界审查确认 UI 不调用 AppRuntime.start()、MutationCoordinator.submit()、retryUnknown() 或自行构造传输/owner | 用默认占位 origin 启动/浏览 UI，不把 DISCONNECTED/UNKNOWN 显示成服务可用，不执行真实 mutation |

## 4. 文件与模块边界

### 4.1 冻结的共享 Runtime 只读接口

Task 3 的 UI 只能消费 Task 2 已实现的公开状态流；此处冻结的是读取边界，不新增网络能力或领域状态。

- **唯一 owner 获取方式：** `MainActivity` 从 `(application as MpdServerRemoteApplication).runtime` 取得唯一的 `AppRuntime`，再通过参数传给应用 UI 根节点/需要观察状态的组件。生产 UI 不调用 `AppRuntime.create()`，不使用页面级单例或新的 `CompositionLocal` owner。
- **Canonical snapshot 与连接状态：** 只观察 `runtime.snapshots.state: StateFlow<SnapshotStoreState>`。读取 `snapshot`、`connectionStatus`、`freshness`、`readError`、`controlsEligible`、`actualPlaybackConfirmed`；状态枚举使用 `RuntimeConnectionStatus`（DISCONNECTED/CONNECTING/CONNECTED）和 `SnapshotFreshness`（UNKNOWN/FRESH/STALE）。不另建 UI 版连接/freshness 权威。
- **歌曲与观察事实：** 可展示的业务字段从 `SnapshotStoreState.snapshot` 指向的 `FullStateSnapshot.wire` 派生。当前 DTO 的 `currentSong`、`playbackObservation`、`outputObservation` 等部分字段仍是 `JsonElement`；各 presentation mapper 必须显式处理缺失/类型错误，不将缺失字段补成真实业务数据，也不能只依据 `playback.state` 推断 actual playback。
- **共享活动：** 只观察 `runtime.mutations.activities: StateFlow<List<OperationActivity>>`，将既有 `MutationStatus`（PENDING/SUCCESS/FAILED/UNKNOWN/SYNCING/INFO）映射为文案与语义。不得在页面内创建 activity/retry owner，也不得修改或复制协调器活动列表。
- **生命周期与连接：** Compose 对 StateFlow 的订阅使用 lifecycle-aware collection（`collectAsStateWithLifecycle`）；页面/ViewModel 可被重建，但 owner 仍来自 Application。当前 `MpdServerRemoteApplication.onCreate` 只创建 runtime，不调用 `runtime.start()`，默认 origin 是未配置的 `http://localhost:18000`。Task 3 不得调用 `start()` 或 `changeOrigin()`，所以无已配置 origin 时应显示 DISCONNECTED/UNKNOWN，而非“已连接”或真实服务状态。
- **写入边界：** Task 3 的播放/暂停 UI 只是 disabled 占位，不调用 `runtime.mutations.submit()`、`retryUnknown()` 或任何 repository 写 API。状态详情只展示已有活动与读取错误；不重试写入、不发起真实 mutation。
- **测试 seam：** 复用已有 `AppRuntime.createForTesting()`、`SnapshotStore` 与 `RuntimeCompositionTest` 的 owner/state 测试；UI 根节点应允许测试注入同一个构造好的 runtime，但不得在 Composable/页面中构造 owner。StateFlow 的读取与 presentation mapping 应保持可在本地单测/Compose UI test 中确定性验证。

### 4.2 文件路径与模块边界

预计新增/修改均落在 `app/src/main/java/io/github/qyxx2/mpdserver/remote` 下，沿用单一 `app` 模块：
- `MainActivity.kt`：应用 UI 启动入口，取得 Application 提供的 runtime，不负责构造 network owner。
- `ui/navigation/`：根 routes、导航宿主、全屏目的地和路由恢复。
- `ui/shell/`：共享 Scaffold/AppBar、迷你播放器、底部导航与全局状态入口。
- `ui/screens/`：主页/搜索/音乐库/设置空状态和全屏播放器占位页面。非本 Task 功能必须明确为占位/未连接，不展示模拟的歌曲或服务端结果。
- `ui/components/`：活动详情、状态环、加载/空/错误语义组件。
- `ui/theme/`：颜色语义、主题映射、Theme composable。
- `ui/preferences/`（或同等最小封装）：仅保存非业务 UI 偏好，使用 Batch 0 冻结并在 Batch 1 验证解析的 Preferences DataStore `1.1.7`；禁止扩展为业务数据持久层。
- `app/src/test`：JUnit 单元测试，覆盖 theme preference、snapshot→presentation mapping、活动状态映射与边界规则。
- `app/src/androidTest`：Compose UI instrumentation tests，覆盖四入口导航、Shell/Mini 显隐、全屏目的地进退、活动详情开合、语义节点与布局边界；需配置 AndroidJUnitRunner。UI test artifacts 由现有 Compose BOM 管理；目标设备/模拟器不可用时只能记录为未运行，不能用单元测试冒充实机/UI 验收。

Batch 0 已冻结这些职责路径与共享 Runtime 只读接口；后续 Batch 可细化文件名和测试名称，但不能弱化上表的行为覆盖。不得为追求抽象提前拆 Gradle 模块。

## 5. 非目标与硬约束

- 不实现 Task 4 完整播放器/歌词/进度/Queue，不实现 Task 5 主页真实数据、搜索/曲库/歌单/收藏业务，不实现 Task 6 完整服务器设置表单/配置 origin 流程，不实现 Task 7 MediaSession/后台控制。
- 不创建假的曲目、封面、播放队列、历史、推荐结果或伪成功提示。未实现页面使用清晰的空/占位说明。
- 本 Task mini 播放/暂停按钮不可调用真实 mutation；若快照表明状态可操作，可明确禁用并使用“播放控制将在播放器阶段接入”的语义说明。不得制造会成功的假按钮。
- 不直接在 Compose 页面中发 HTTP/WS；不添加新的 socket、轮询 owner 或页面级 store；不把 Task 2 实机未验证写成 Task 3 已验证。
- 不新增业务数据库、WebView、本地播放引擎、服务端接口、CI workflow 或远端构建。
- 不改动服务端仓库，不重新核对固定上游 HEAD。

## 6. 关键 UI/状态规则

- 根入口选中态与内容目的地一致，重新选择当前入口不得创建新的 runtime。
- 全屏播放目的地与根导航分开：进入后隐藏 mini 与底部导航；返回时恢复原根入口/本地滚动状态（本 Task 测试只需验证导航 back stack 不被重置）。
- 设置页隐藏 mini，但全局状态入口仍可使用。
- mini 区域固定参与 layout；不得覆盖正文或 bottom navigation。AppBar 紧凑，不通过过高头部挤压正文。
- 主题默认 Follow System；浅色采用柔和近白底、强调色 `#DE7622`；深色使用深色中性表面和 `#BE7E4A` 或经对比验证的暗化橙色。禁止仅靠颜色表达 error/stale/disabled。
- 没有已接纳的 snapshot、快照 stale/unknown 或当前播放观察未确认时，展示状态必须如实表达，不可从业务 `playback.state` 单独推断真实播放。
- 任何待实现入口/按钮都应是明确占位且不可误触提交，或者在 UI 中隐藏；不得伪装为已交付能力。
- 本地偏好不得覆盖服务端事实，也不得在新进程恢复旧快照作为 canonical state。

## 7. Batch 计划及依赖

- Batch 0：合同/源码映射与 UI 导航/主题/状态投影接口冻结（不写产品功能代码）。
- Batch 1：主题 token、主题入口和本地主题偏好/测试。
- Batch 2：四入口路由、导航宿主与返回栈/状态保存。
- Batch 3：共享 App Shell（AppBar、根正文布局、底部导航、安全区）。
- Batch 4：迷你播放器纯展示投影、空/stale 状态、不可执行的预留控制与测试。
- Batch 5：全局状态环、统一活动详情与 runtime observation 集成。
- Batch 6：全屏播放器占位目的地、壳集成与导航恢复回归。
- Batch 7：完整机器回归、代码边界审查、目标设备人工 UI 验收记录。

每个 Batch 必须独立测试、写入 Task 专用进度账本、形成单独提交；本轮只冻结计划，不开始实施 UI 功能。

## 8. 验证与退出门

### 机器验证
- 使用冻结 Gradle Wrapper 本地执行 `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`；需要时最终 clean build。
- Compose UI 测试覆盖四项入口、设置页 mini 显隐、全屏目的地进入/返回、主题切换、状态详情打开/关闭。
- 通过 runtime identity/DI 测试与边界扫描证明导航不重新构建 `AppRuntime`、WebSocket、Store 或 MutationCoordinator。
- 主题偏好只写本地非业务存储；没有服务端连接时 UI 启动仍成功。
- `git diff --check` 通过；无 Room/SQLite、WebView、Media3/ExoPlayer、第二个 realtime controller、服务端写入行为、伪造业务数据或 CI workflow。

### 人工/实机验证
- 在目标 Android 真机上安装并启动 Task 3 APK。
- 检查四项根入口的视觉顺序/选择态、mini 显隐、播放器占位目的地进退、系统返回、浅色/深色/跟随系统。
- 检查安全区/状态栏、常见短屏和窄屏、大字体、48dp 触摸目标、键盘出现时布局、状态环和活动详情可读性。
- 只验收 UI 与导航，不以缺少真实 MPD-Server 联调判失败，也不把 UI 展示正常当作 Task 2 API/mutation 通过证据。

### Task 3 退出门
- [ ] 合同矩阵均映射到 Batch、测试和验收证据。
- [ ] 自动化测试、Lint、构建、边界扫描和 diff review 通过。
- [ ] 用户报告真实设备 UI/主题/导航人工验收通过，结果写入文档。
- [ ] Task 2 仍以单独状态记录：真实 mutation/readback 与 APK live-origin 未完成时保持 BLOCKED；不把此项重命名为 Task 3 的 UI 失败，也不据此声称整个客户端可真实遥控服务端。
- [ ] Task 4/6 开始实际服务集成前，完成 Task 2 遗留真实联调，或在该业务 task 合同中明确安全可逆的补验操作及退出门；不得无记录地跳过。

## 9. 执行约定

- 工作分支：`feature/task-3-native-app-shell`。
- 唯一实施工作区：`/home/Gold/WorkSpace/MS_remote_for_Android/.worktrees/task-3-native-app-shell`；主工作区留在 Task 2 分支且不作 Task 3 代码更改。
- 每个 Batch 开始时记录本 Android task 分支 HEAD；遵守固定上游基线规则，不重复查询上游。
- Batch 只做计划中指定范围；测试先行；每个 Batch 独立提交；记录机器验证命令和结果。不得执行下一个 Batch 的 UI 实现，直至用户另行明确要求。
