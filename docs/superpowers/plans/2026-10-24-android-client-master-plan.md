# MPD-Server Android Client Master Plan / Android 客户端主计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. The checkboxes track Task exit gates; do not start the next Task before the current gate passes.

**Goal:** 在不复制 MPD-Server 业务能力、不新增客户端业务权威的前提下，交付一个可重复构建、可安装、具备精致原生 UI 的 Kotlin + Jetpack Compose Android 遥控与状态展示客户端。

**Architecture:** MPD-Server 是唯一业务和状态权威。Android 通过既有 HTTP API 和 WebSocket 遥控、读取权威状态并展示。应用 UI、系统媒体会话和通知共享同一应用运行时、状态 Store 与操作协调器。

**Tech Stack:** Kotlin、标准 Gradle Wrapper / Kotlin DSL、Android Gradle Plugin、Jetpack Compose / Material 3、Coroutines / StateFlow、OkHttp、kotlinx.serialization、Preferences DataStore；具体版本在 Task 0 核验后冻结。

**Spec:** 本计划由以下四份权威规格共同约束：
- [Android 产品与工程总规格](../specs/2026-10-24-android-client-product-and-engineering-spec.md)
- [后端接口与实时协议规格](../specs/2026-10-23-android-backend-api-contract.md)
- [Web 行为继承规格](../specs/2026-10-23-android-web-behavior-contract.md)
- [客户端边界与架构规格](../specs/2026-10-23-android-client-boundary-and-architecture.md)

> **文档定位：主计划与阶段门控，不是逐 Batch 执行计划。** 本文固定 Task 顺序、每个 Task 必须核对的合同矩阵、机器/人工验证类型、前置阻塞及退出条件。进入某个 Task 时，根据已冻结的权威规格与已记录的服务端基线编写详细合同、冻结边界、验收标准，并拆分 Batch。只有用户明确要求同步上游或告知服务端协议变化时，才重新核对上游。

## Global Constraints / 全局约束

- 最低系统版本固定为 Android 8.0 / API 26；首期目标是手机竖屏，不做平板专属或横屏双栏信息架构。
- 使用原生 Compose，不使用 WebView；不使用本地音频引擎，不直接连接 MPD TCP 端口。
- MPD-Server 是唯一业务状态来源；不新增 Room/SQLite 业务数据库，不在客户端重做 Queue、AutoPlay、History、播放恢复、Collection、Playlist、Favorites 或 MPD reconciliation。
- 全应用仅有一个长期 realtime controller、一个共享状态 Store 和一个操作协调器。页面/Composable/ViewModel 生命周期不能重建它们或丢失未解决操作的 owner。
- 对 stale/unknown、旧 epoch/sequence、current target 变化、幂等和操作结果未知，严格遵守既有合同；不得以乐观 UI 或本地拼装伪造服务端确认。
- Android 默认服务端端口为 18000；客户端不监听服务端口。HTTP 与 WebSocket 均从同一个用户配置的 origin 派生。
- 根导航固定为“主页、搜索、音乐库、设置”。主页/搜索/音乐库显示迷你播放器，设置页不显示；完整播放器是独立全屏目的地，在其中切换“正在播放/Queue”。
- 已冻结采用方案 A：Android 的信息架构和布局按原生产品规格执行；Web 只作为 API、DTO、业务语义、状态同步和错误恢复行为的参考，不以复制 Web 页面布局为目标。
- 应用内状态环不使用跨应用悬浮窗权限。锁屏/通知媒体控制是远端控制适配层，不是本地 Player；耳机按键不是独立的验收目标。
- 签名密钥、密码和其他凭据不得提交到 Git 仓库；Task 0 已冻结统一签名策略与本地秘密管理。当前不启用 CI/远端构建。

## 每个 Task 的固定工作流程

1. **确认工作基线：** 记录本 Android 分支实际 HEAD。服务端使用已有权威规格记录的固定基线，不要求每个 Task/Batch 重复查询 `MPD-Server/main`；只有用户明确要求同步上游或告知服务端协议变化时，才重新核对受影响的路由、schema、Web 调用点与测试。
2. **建立合同矩阵：** 为本 Task 列出“需求/决策 → 权威规格章节 → 上游代码或测试证据 → 本 Task 新合同/已有合同 → 验证方式”。明确哪些内容已冻结、哪些需要本轮确认、哪些明确不做。
3. **冻结 Task 合同：** 在 `docs/superpowers/specs/` 建立或更新该 Task 的详细合同，写明范围、依赖、接口/状态边界、非目标、机器验证、人工验证和可判定的退出条件。涉及用户产品选择、环境限制或安全凭据的问题，先向用户确认；未决事项不得靠实施者猜测。
4. **拆分执行计划：** 在 `docs/superpowers/plans/` 创建该 Task 的分批计划。每个 Batch 只完成明确范围，测试先行，变更后运行对应验证并提交；不得顺带开始下一 Task。
5. **独立验证与审查：** 先拿到机器验证结果，再做规定的人工验证；处理审查发现的严重问题后才可通过退出门。
6. **更新进度：** 记录提交、验证结果、未完成项和下一 Task 的阻塞条件。未通过退出门不得启动下一个 Task。

### Task 合同矩阵的最低字段

| 字段 | 必须回答的问题 |
|---|---|
| 需求与来源 | 本 Task 要落实什么，分别由哪份权威规格及章节定义？ |
| 上游证据 | 对应的服务端路由/schema、Web 调用点或测试在哪里？若不适用，原因是什么？ |
| 本轮冻结项 | 哪些接口、状态语义、UI 行为、工程配置或产品选择需本轮冻结？ |
| 非目标与限制 | 哪些看似相关的功能明确不做，避免越界扩张？ |
| 机器验证 | 哪些自动化测试、构建、Lint、协议测试或真实服务探测可以证明目标？ |
| 人工验证 | 需要在哪种真实设备、系统界面或实际服务器上检查什么？ |
| 阻塞及退出门 | 缺少什么就不能开始？哪些结果必须全部通过才算完成？ |

## Task 路线

### Task 0：开发环境、统一签名策略与本地构建流程冻结

- [x] 合同、机器验证、用户确认与真机人工验证完成；退出门已通过。

**目标：** 在正式工程骨架之前冻结可复现工具链、正式应用身份、统一安装签名策略与本地构建流程。

**合同矩阵起点：**
- 产品与工程总规格 §§4、10、13.2；客户端边界与架构规格 §§2–4。
- [Task 0 环境、构建、签名与本地交付合同](../specs/2026-10-24-task-0-environment-build-signing-ci.md) 和 [Task 0 执行计划](2026-10-24-task-0-environment-build-signing-ci.md)：机器构建/Lint/签名验证有记录，用户确认了工作流和身份策略，真实设备已安装并运行 Probe。

**已冻结：**
- 正式 applicationId 与 namespace：io.github.qyxx2.mpdserver.remote；显示名：MPD-Server Remote。Task 1 必须实现并验证，不得另起包名。
- 所有正式 APK 使用同一用户控制的内部签名密钥，以支持不同构建位置/构建变体的直接覆盖安装。密钥不入库，由用户加密备份；Task 1 创建或安全接入密钥。
- 所有构建、测试、Lint 和 APK 产出只在指定 Ubuntu 本地工作区完成；远端 GitHub 仅同步代码分支，不配置 CI/远端构建。
- 只有在本地因 APK 体积/资源限制无法构建，或耗时过长时，才重新评估远端构建；必须经用户再次批准。

**机器验证：** 临时 Compose 探针的 build、Lint、APK 元数据与 Debug 签名验证有记录。正式应用的包名/统一签名与覆盖安装验证归 Task 1。

**人工验证：** 用户已确认开发方式和签名/发行策略，并在真机安装 Probe 后成功看到验证文本。

**退出门：已通过。** Task 0 未创建正式产品工程或签名密钥；不得将 Probe 包名或 Debug key 当作正式身份。Task 1 负责落实本合同。

### Task 1：原生工程骨架与本地构建基线

- [x] Task 1 合同、机器验证与真机安装/覆盖安装验收通过；已记录证据。

**Task 1 详细合同：** [原生工程骨架与本地构建基线](../specs/2026-10-24-task-1-native-project-baseline.md)；[实施计划](2026-10-24-task-1-native-project-baseline.md)。工程骨架、最终本地 clean build、单元测试、Lint、Debug/Release APK 身份与统一签名机器验证均已完成；用户已确认真实设备安装运行正常，并确认覆盖安装成功。签名密钥加密离线备份的操作由用户独立完成，不是 Task 1 工程实现或设备安装验收的阻塞项；请继续依 Task 0 安全约定妥善备份。

**目标：** 按 Task 0 冻结的工具链建立标准 Kotlin/Compose Gradle 工程，形成可重复的本地构建与测试基线。当前不建立 CI/远端构建。

**合同矩阵起点：** 产品与工程总规格 §§3–4、§13；Task 0 环境/统一签名/本地交付合同；客户端边界与架构规格 §4。

**主要交付：** Wrapper、依赖版本管理、冻结的 applicationId/namespace、Manifest/网络安全基线、统一签名配置、本地构建与测试命令、基本包结构；初始 UI 用占位页面证明原生工程可运行，不在此 Task 实现业务功能。不得新增 CI workflow。

**机器验证：** 在本地工作区执行冻结的 Wrapper 构建命令、基础测试和 Lint；验证 APK applicationId、版本元数据、统一签名证书，并验证重复构建产物可覆盖安装（证书一致性检查为强制项）。

**人工验证：** 将本地构建 APK 安装到目标 Android 设备并启动；确认应用可运行、主题系统正常、版本/包名可识别，并检查后续 APK 可直接覆盖安装且保留应用数据。

**退出门：已通过。** Task 0 合同已冻结；本地构建、测试、Lint、Debug/Release 签名与包名检查通过，用户确认真实设备安装及同签名覆盖安装无问题。只有用户重新批准后，才可新增远端构建/CI。不得在骨架阶段引入业务数据库、WebView 或本地播放引擎。

### Task 2：网络协议、共享状态 Store 与操作协调器

- [x] Batch 0–6 实施与机器验证已完成；2026-10-27 已补齐真机 runtime/coordinator 联调和真实 mutation/readback。详见 [Task 2 实机真实写验证报告](../reports/2026-10-27-task-2-live-write-verification.md)。未合并 main。
- [x] 真机通过 instrumentation 注入 `http://192.168.3.94:18000`，验证完整 fresh snapshot；经共享 MutationCoordinator 创建唯一命名用户歌单、权威 GET 读回、DELETE 成功回执、再次 GET 确认删除，1/1 instrumentation test 通过；测试后服务器返回 `{items:[],count:0}`。歌单删除路由的 204 语义另由现有 coordinator 单测覆盖；本次 live Activity 不暴露数值 HTTP 状态码。此测试不操作播放、Queue、曲库文件或收藏。

**Task 2 详细合同：** [网络协议、共享状态 Store 与操作协调器合同](../specs/2026-10-24-task-2-network-state-coordinator.md)；[分批实施计划](2026-10-24-task-2-network-state-coordinator.md)。服务端以 Task 2 合同中记录的固定基线为准；用户明确要求同步上游或告知服务端协议变化时才重新核对，不要求每个 Batch 重复检查。

**目标：** 建立后续页面唯一依赖的服务端连接、DTO 校验、完整状态快照、实时失效通知和 mutation 生命周期基础。

**合同矩阵起点：** 后端 API 合同 §§2–5；Web 行为合同 §§1–5、§9；架构合同 §§2、4、6、8；产品规格 §§10、12、13.1。

**主要交付：** 薄 API repositories、HTTP/WS 客户端、严格 DTO/wire 校验、共享状态 Store、单一 realtime controller、共享操作协调器、fresh/stale/unknown 和统一错误/活动模型。

**机器验证：** Batch 6 原验证为 15 suites / 66 tests；2026-10-27 重新完整验证 `clean testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin assembleDebugAndroidTest` → BUILD SUCCESSFUL，23 suites / 108 tests / 0 failures / 0 errors / 0 skipped；Lint、Debug APK 与 instrumentation APK 编译/组装均通过。完整日志：`/tmp/task2-live-write-build-verify.log`。

**真实服务验证：** `192.168.3.94:18000` 的 `/api/health`、`/api/state`、`/api/playlists` 返回 HTTP 200；读取 live playlist body 时确认 list wire shape 为 `{items, count}`，并修正 Android DTO/repository。SM-G986N / Android 13 的 APK instrumentation 成功用共享 MutationCoordinator 创建临时用户歌单、GET 读回、DELETE 成功回执、再次 GET 确认删除；测试后列表为空。歌单删除路由的 204 语义另由现有 coordinator 单测覆盖；本次 live Activity 不暴露数值 HTTP 状态码。此写验证不涉及播放/Queue/曲库/收藏。

**限制：** live origin 通过 instrumentation 参数注入到既有 AppRuntime，以验证 APK 内的网络/runtime/coordinator；这不等于交付用户可操作的服务器地址设置 UI。普通 app 的默认 origin 仍是 `http://localhost:18000` 占位值，用户配置 origin 的 UI 留给后续设置任务。

**退出门：Task 2 runtime/coordinator 联调与真实 mutation/readback 已通过。** Android 设备上的 instrumentation 对真实 origin、fresh snapshot、操作成功回执、写后独立读取和删除后再次读取均作了断言。Task 2 未合并 main。生产 app 的服务器地址设置 UI 仍属于后续设置任务；本次通过 instrumentation 参数注入 origin，不冒称该设置 UI 已交付。

### Task 3：原生应用壳、导航、主题与通用状态 UI

- [ ] 完成本 Task 合同、验证与退出门，并记录验证证据。

**目标：** 实现被冻结的四项导航、页面容器、迷你播放器、应用内状态环/活动详情和本地外观偏好，接入 Task 2 的共享状态。

**合同矩阵起点：** 产品规格 §§5–6、§8、§12、§13；架构合同 §§5–7；Web 行为合同 §9。

**主要交付：** 主页、搜索、音乐库、设置四个根入口的基本骨架；迷你播放器只展示封面、单行标题和播放/暂停（不显示艺术家）；设置页隐藏迷你播放器；全局状态环与统一活动详情；跟随系统/浅色/深色主题和本地 UI 偏好。精确视觉参数遵循 [Task 3 视觉设计规格](../specs/2026-10-25-task-3-visual-design-specification.md)。首页内容可暂用清晰的空/占位状态，禁止静态假业务数据伪装已实现能力。

**机器验证：** Compose UI 测试验证四入口导航、页面切换不重建 realtime owner、迷你播放器显示规则、全屏目的地进入/返回、主题状态及关键控件的真实禁用。

**人工验证：** 在目标设备检查浅色/深色、edge-to-edge 透明状态栏、应用内容延伸至状态栏下方、居中挖孔周围状态环的实际定位、活动结束后 5 秒隐藏、短屏/窄屏/大字体/键盘布局及独立活动详情入口。旧的 AppBar 右侧状态环截图不作为新规格验收证据。

**开始门与退出门分离（2026-10-25 冻结，2026-10-27 状态更新）：** Task 3 的 UI-only 工作原本允许在 Task 2 共享 runtime/store/controller/coordinator 实现存在且机器测试基线通过后开始。Task 2 剩余真实 mutation/readback 与 APK runtime live-origin 联调已于 2026-10-27 完成；生产服务器地址设置 UI 仍留给后续设置任务。详见 [Task 3 合同](../specs/2026-10-25-task-3-native-app-shell.md) 与 [Task 3 分批计划](2026-10-25-task-3-native-app-shell.md)。

**本 Task 的阻塞/退出门：** 根导航和共用组件规则冻结；UI 自动化测试、clean 构建/Lint、边界检查通过；目标设备的完整主题/导航/布局人工验收有记录。Task 3 不执行真实 mutation，不以占位 origin 代替联调。Task 2 的 runtime/coordinator 实机真实写/读回验收已于 2026-10-27 通过；Task 4/6 仍须在各自合同中为业务写操作定义适当场景与验证，不得将本次临时歌单验证视为播放/Queue 等业务操作已经验收。

**Batch 7 机器验证记录（2026-10-26）：** clean Gradle 回归 BUILD SUCCESSFUL；22 个单测报告共 105/105 通过，SM-G986N / Android 13 真机 instrumentation 10/10 通过，Lint、Debug APK 和 Android-test 编译通过，源码边界扫描通过。报告见 [Task 3 Batch 7 集成验证报告](../reports/2026-10-26-task-3-batch7-verification.md)。**Task 3 整体退出仍未完成：** 目前只有部分导航人工反馈；用户尚未确认完整主题/布局验收，真实活动状态环视觉/生命周期与物理手动校准也未完整验证。Task 2 另行遗留的 runtime/coordinator 真实 mutation/readback 与 APK live-origin 验收已于 2026-10-27 通过。随后用户单独授权的最小 MS 地址设置入口已实现，详见 [地址设置验证报告](../reports/2026-10-27-ms-server-address-settings-verification.md)。

### Task 4：全屏正在播放、歌词、进度与 Queue

- [ ] 完成本 Task 合同、验证与退出门，并记录验证证据。
- [x] 创建 `task4` 分支，并记录用户已确认的 B 大封面方向、干净顶部、底部三个无框入口、歌词替换主内容区、Queue 六点拖拽柄/三点菜单及连续历史展开合同。
- [x] 完成视觉规格、合同矩阵及逐 Batch 实施计划（[Task 4 Implementation Plan](2026-10-27-task-4-full-player-queue.md)）。
- [x] 完成 B0、B1、B2；B2 已通过单元测试、Lint、真机 UI instrumentation 与只读 MS 连通/权威快照 smoke。播放器交互写操作、歌词及 Queue/Played 主体仍由后续批次完成。

**目标：** 完成核心遥控体验，并完整继承服务端播放观察、target、幂等和 Queue occurrence 语义。

**合同矩阵起点：** 产品规格 §7.5、§9、§11–13；后端 API 合同 §3–5；Web 行为合同 §§3–6、§8–9；架构合同 §§5–6。

**主要交付：** 全屏播放器、封面/歌词切换、歌曲与音频元数据显示、共享展示时钟、seek、上一首/播放暂停/下一首、Queue 与 Played 展示、Play Now/Next/Queue、指定 occurrence 播放、排序/删除/清空待播/保存歌单。

**机器验证：** 测试实际播放确认条件、stale/unknown 时控件禁用、current/target 变化、seek 草稿取消、重复歌曲 occurrence、Queue clear 不等于 Stop、204 后重新读状态、LRC/普通文本/offset 和未知操作结果恢复。

**人工验证：** 对接真实服务和实际 Android 设备，检查播放/暂停/切歌/seek、重复曲目操作、歌词自动跟随与手动滚动、无封面/歌词错误、切歌时封面与歌词不串曲。

**阻塞/退出门：** Task 2 的状态和操作协调器验收通过；所有写操作经过共享协调器；不得以业务 `playback.state` 单独断言实际播放已确认。

### Task 5：主页、搜索、音乐库、歌单与收藏

- [ ] 完成本 Task 合同、验证与退出门，并记录验证证据。

**目标：** 完成发现与曲库浏览能力，严格使用现有服务端资源/API。

**合同矩阵起点：** 产品规格 §§7.1–7.3、§13；后端 API 合同 §4–5；Web 行为合同 §7；架构合同 §5。

**主要交付：** 主页可由服务端事实支撑的模块、搜索输入和分类结果、歌曲/专辑/艺术家/风格/年代/标签浏览、歌单和收藏管理，以及已有 API 支持的歌曲/集合操作。

**机器验证：** 覆盖正常/空/错误/stale 状态、搜索分类结果与详情映射、资源变化后刷新、歌单和收藏写入后重新读取、不可用歌曲与缺失元数据；验证不产生本地业务副本。

**人工验证：** 用实际曲库检查搜索、分类、歌曲详情、收藏、歌单操作和返回导航；确认“最近添加/最近播放”等内容来源准确，“猜你喜欢”没有服务端能力时不得显示伪造推荐结果。

**阻塞/退出门：** Task 2 API/DTO 语义稳定；各主页模块的数据来源有明确证据；任何需要新增后端业务/API 的要求必须另行分析和批准。

### Task 6：设置页与服务端系统配置

- [ ] 完成本 Task 合同、验证与退出门，并记录验证证据。

**目标：** 完成本地客户端偏好，以及现有服务端健康、MPD 连接目标和输出状态/操作的展示与可用操作。

**合同矩阵起点：** 产品规格 §§7.4、§8、§10、§12–13；后端 API 合同 §§2、4；架构合同 §§2–3、§5。

**主要交付：** 服务器 origin/端口、主题、状态环参数等本地偏好；服务端版本/统计/连接目标/输出状态等现有 API 页面。必须区分 Android 连接的 MPD-Server 地址与服务端保存的 MPD 连接目标。

**机器验证：** 测试偏好持久化、服务地址变更后旧连接世代作废、旧端点写请求不迁移、API 错误显示、输出能力与模式不匹配时不开放假操作；验证保存 MPD 连接目标不会被误判为活动连接立即切换。

**人工验证：** 在实际服务配置上检查可用/不可用输出状态、保存连接目标后的提示、应用重启及重新连接路径；验证本地偏好不会写到服务端。

**阻塞/退出门：** Task 2 已通过；所有设置项都能指向现有本地能力或上游端点；没有假成功提示或未经批准的新 endpoint。

### Task 7：Android 系统媒体会话、通知与锁屏控制

- [ ] 完成本 Task 合同、验证与退出门，并记录验证证据。

**目标：** 使用 Android 系统媒体控件远程控制服务端播放，使锁屏/通知栏的播放状态与 App 共享状态一致。

**合同矩阵起点：** 产品规格 §§4.1、11–13；Web 行为合同 §§2–5、9；架构合同 §§4–6。

**主要交付：** MediaSession/MediaStyle 通知及必要的 Android 服务生命周期；当前曲目元数据和可用的上一首、播放/暂停、下一首动作；所有命令复用共享状态及操作协调器，不建立第二条 WebSocket 或本地 Player。

**机器验证：** 测试媒体动作映射、当前歌曲及播放状态更新、stale/断线时动作能力、Stop/无 current 时通知撤下、进程重建后重新读取权威快照、同一动作不会重复提交。

**人工验证：** 在真实设备检查前后台切换、锁屏/通知控件、暂停与切歌、断网/恢复、系统权限/通知限制和进程被终止后的恢复。模拟器结果不能代替真实设备锁屏验收。

**阻塞/退出门：** Task 2 操作协调器稳定，Task 4 的 playback 状态和动作语义已验收；按实际 targetSdk 检验所需服务类型、权限及通知行为。

### Task 8：端到端回归、视觉打磨与交付验收

- [ ] 完成本 Task 合同、验证与退出门，并记录验证证据。

**目标：** 将所有功能作为一个应用验证，修复跨页面、实时状态、后台生命周期和设备适配问题，按 Task 0 的方案验证交付产物。

**合同矩阵起点：** 四份权威规格全部相关章节；Task 0 的环境/统一签名/本地交付合同；Task 1–7 已冻结且已通过的 Task 合同。

**机器验证：** 完整本地构建、单元/协议/UI 测试、Lint、APK 签名/包名检查；验证统一签名和重复构建的一致性。当前不要求 CI artifact 或远端发布构建。

**人工验证：** 在目标真实设备和必要的较窄屏幕/API 26 兼容环境上验证关键流程；覆盖浅/深主题、大字体、系统安全区、完整播放和 Queue、搜索/曲库/设置、锁屏媒体控制、断线恢复及应用重启。安装最终交付 APK，并记录版本、签名身份和结果。

**阻塞/退出门：** Task 0–7 的必需退出门均通过；无 Critical/Important 审查问题；本地构建、测试、Lint 和交付产物证据可追溯；遗留问题被记录、分类并经用户接受。不得将未完成的签名或真机验收标作通过。

## Review Focus / 五个重点回归风险

以下风险必须映射到所属 Task 的自动测试，不得只靠人工注意：

1. **状态过期或连接世代切换：** 旧 epoch/sequence、旧 WS 或迟到 GET 不得覆盖当前快照；归 Task 2。
2. **未知写入结果被重复执行：** 超时或进程重启后不得自动使用新幂等键重放；归 Task 2，并由 Task 4/6 做业务场景回归。
3. **重复歌曲身份混淆：** Queue 操作必须以 `queue_item_id` 标识 occurrence，而非 song id；归 Task 4。
4. **媒体显示与真实播放脱节：** 业务 current、实际播放观察、封面/歌词和系统媒体通知不能串用过期身份；归 Task 4 与 Task 7。
5. **环境或交付伪通过：** 不允许仅凭本机增量编译推断 APK 可用；以完整本地构建、签名/包名元数据和真机安装检查为准。当前不使用 CI 状态作为验收证据；归 Task 0、Task 1、Task 8。

## 全项目完成定义

只有同时满足以下条件，才可将 Android 首期标记为完成：

- 四份权威规格及各 Task 合同与当前实现一致；上游基线变动已按流程核对。
- 全部 Task 退出门通过，代码审查无未解决的 Critical/Important 问题。
- 指定 Ubuntu 工作区的完整 Gradle 构建、自动测试和 Lint 有真实输出证据；按当前用户决定不要求 CI artifact。
- 目标 Android 设备的关键 UI、播放、断线恢复和系统媒体控制人工验收完成。
- APK 的安装、冻结包名、版本和统一签名身份均经过验证；签名密钥未进入仓库。当前只有在本地构建资源/耗时满足用户重新批准的触发条件后，才重新评估远端构建。
- 未支持能力、环境限制和剩余缺陷有明确记录，不以假数据或假成功掩盖。

主计划只控制依赖与阶段边界，不替代任何 Task 的详细合同、分批计划和验收报告。
