# Task 4：全屏播放器、歌词、进度与 Queue Implementation Plan

> **For agentic workers:** 按 Superpowers `executing-plans` 的顺序逐 Batch 执行；所有行为改动遵守 TDD RED→GREEN，每个 Batch 独立验证并单独提交。用户禁止使用或查询其它编程代理；后续实施由当前执行者直接在指定工作区完成，不派发代理。勾选项用于记录执行状态。

**Goal：** 在不创建第二套播放/Queue 权威的前提下，完成原生全屏播放器、歌词、进度控制、Queue/Played、现有队列操作与历史同列表原生滚动，并逐项满足 Task 4 合同矩阵。

**Architecture：** Android 只读取 MPD-Server 的 canonical snapshot 与现有资源 API；所有播放/Queue 写操作只经 Application 级同一 AppRuntime 的 MutationCoordinator，成功后以服务端回执及权威读回确定结果。页面内临时状态仅包含布局、seek 草稿、拖拽草稿和历史 reveal 高度，不成为业务状态或持久化副本。

**Tech Stack：** 沿用当前 Kotlin/Jetpack Compose、现有 Gradle Wrapper/AGP/Kotlin、OkHttp、kotlinx.serialization、Coroutines/StateFlow 与现有 Compose BOM。除非本计划中的现有 API 无法完成目标并形成明确证据，不新增依赖、Gradle 模块、服务器 endpoint、数据库、WebView 或本地播放引擎。

**Batch 划分：** 本计划按依赖与独立验收边界拆为 B0–B9；这只是实施计划，不表示任一 Batch 已实现。

**Spec：** [Task 4 合同矩阵](../specs/2026-10-27-task-4-contract-matrix.md)；[Task 4 视觉/交互规格](../specs/2026-10-27-task-4-full-player-queue-visual-design.md)；[产品与工程总规格](../specs/2026-10-24-android-client-product-and-engineering-spec.md)；[后端 API 合同](../specs/2026-10-23-android-backend-api-contract.md)；[Web 行为继承合同](../specs/2026-10-23-android-web-behavior-contract.md)；[客户端边界与架构合同](../specs/2026-10-23-android-client-boundary-and-architecture.md)；[Task 2 网络/状态/协调器合同](../specs/2026-10-24-task-2-network-state-coordinator.md)；[Task 3 应用壳合同](../specs/2026-10-25-task-3-native-app-shell.md) 与 [Task 3 视觉规格](../specs/2026-10-25-task-3-visual-design-specification.md)。本计划只细化实现次序，不覆盖上述合同。

## 基线和实施边界

- 本规划建立时的工作区为 `/home/Gold/WorkSpace/MS_remote_for_Android`，分支为 `task4`，观察到的 HEAD 为 `0a68ef65bfad040f700d74562e09ef96e57ff640`。该值是规划时基线；每个后续 Batch 开始时必须记录实际当前 HEAD，不得假设期间分支没有变化。
- Android 工程仍只有一个 `app` 模块；Application 中已有共享 `AppRuntime`、`SnapshotStore`、`RealtimeController` 与 `MutationCoordinator`。全屏 UI 目前是 Task 3 的占位目的地。
- 服务端来源继续使用已冻结合同记载的 `qyxx2/MPD-Server` 基线 commit `2ad84454ceb70817bd996c92b590cfa2d9e9b128`。不逐 Batch 查询上游 HEAD，不改服务端/Web 仓库。
- `StateSnapshotDto` 的 current song、queue、playback observation、output 和 output observation 当前主要是 `JsonElement`；现有 `SongDto` 不是服务端 `SongResponse` 的完整 wire shape。Batch 0 先解决这些数据边界，再实现展示。
- `GET /api/playback/queue` 返回整个 Queue occurrence 列表；`GET /api/history/played` 返回整个当前会话 Played 列表。两个端点都没有分页参数。历史“有限窗口”仅限制客户端渐进显示，不代表有限网络响应或 server-side pagination。
- Task 4 不实现 Task 5 的搜索、专辑/艺术家列表浏览，也不实现 Task 6 的完整服务器系统设置。用户额外授权的 Task 4 Batch 1 前置事项，仅增加本地 MS server-origin 地址输入、校验、持久化与应用入口；具体设计/验收见 [MS 服务器地址设置实施计划](2026-10-27-ms-server-address-settings.md)。这项前置任务不扩展播放器/Queue 合同、不新增后端接口或网络 owner。播放器点击艺术家/专辑只发送带有实体类型、ID 与显示名的导航请求，由现有应用导航边界接收；列表数据页仍归 Task 5。
- 该计划制定时的交付仅为 Batch 拆分计划与文档映射；另行授权的 MS server-origin 设置前置任务不属于 B0–B9 的播放器/Queue 实现。当前任务不触发真实播放、seek、Queue 写入，不提交、不合并、不推送。

## Global Constraints

- 服务端是播放、实际播放观察、current occurrence、Queue/Played、revision、输出状态与所有写操作结果的唯一权威。不得以 `playback.state` 单独判定真实播放。
- 可确认 current 的最低条件必须同时满足合同要求：snapshot/观察状态 fresh、`actual_freshness == fresh`、`matches_current == true`、`bound_queue_item_id` 等于 position 0 的当前 occurrence、sync status 为允许值，且实际状态是 playing 或 paused。任何 stale/unknown/UNBOUND/EXTERNAL_DRIFT/SYNC_FAILED/UNCONFIRMED_STOP 不得绘制成已确认播放。
- 全应用仍只有 Application 持有的一个 AppRuntime、一个 canonical SnapshotStore、一个 realtime owner 和一个 MutationCoordinator。Screen、Composable、ViewModel 不创建 HttpTransport/OkHttp owner、WebSocket、Store、Coordinator 或新 Runtime；读取资源走 Runtime 提供的当前 origin 查询接口。
- 所有写请求使用现有 API 与 MutationCoordinator；未知结果不能自动换新 key 重发。seek 与排序在本地只是草稿；发生 epoch、连接 generation、current occurrence、control target/token、Queue revision 或目标基线变化时，相关草稿必须撤销。
- Queue 行和拖拽使用 `queue_item_id` occurrence 身份，不使用 `song_id`、URI 或标题去推断 Queue 身份。current 和 Played 不可拖动；只有 position > 0 的待播 occurrence 可拖动。
- Source file 编码/位深/采样率与 DAC/output observation 分开投影，不互相推导。Output ACTIVE 不等于硬件已发声或 bit-perfect。
- 封面/歌词模式只替换主内容区域，进度、时间、源文件参数、主控制和底部三个无框入口的布局锚点保持稳定。歌词视口填充可用主内容高度，每条 cue 最多两行；超长文本第二行水平滚动显示余下内容，不以极小字号硬塞固定行数。
- 播放器顶部没有返回/收起按钮或“正在播放/队列”切换按钮。全屏/Queue 退出只由 WindowInsets 几何确定的应用内容顶部手势带触发；真实系统状态栏内部手势必须留给 Android。Queue 正文顶部下拉用于连续 reveal Played，与退出手势互斥。
- 历史 reveal 使用浮点可见高度/测量行高；pointer movement 不发网络请求。ACTION_UP/ACTION_CANCEL 冻结精确的 reveal 高度与列表偏移，不吸附、不 settle、不回弹、不二次跳变；下一次手势从冻结状态继续。
- 不添加本地数据库、持久化曲目/Queue/Played/歌词业务副本、WebView、Media3/ExoPlayer、本地播放引擎、新服务器 endpoint 或伪造演示数据。
- 每个实现 Batch 都要在本工作区先写并见证对应 RED 测试，再实现最小改动并 GREEN；运行该 Batch 规定的完整验证、`git diff --check`、边界审查，记录日志/结果后单独提交。未授权真实服务写操作时只做自动化/只读验证，并明确记录 NOT COVERED/BLOCKED。

## Review Focus

1. **Current/actual playback 串用：** queue position 0 不代表设备正在播放；测试 fresh/stale、bound ID、target/token、EXTERNAL_DRIFT、SYNC_FAILED、NO_CANDIDATES 与停止/断线恢复（Batch 1、3、9）。
2. **迟到资源把旧曲目带回 UI：** current occurrence、target、epoch、library revision/generation 改变后，旧曲目/封面/歌词/seek 回包不能覆盖新曲目（Batch 0、1、4、5）。
3. **写请求重复、目标漂移和结果未知：** 同一 intent 保留 idempotency key 与 payload；UNKNOWN 不自动按新 key 重发；成功回执、204 和读回失败分开表达（Batch 3、6、9）。
4. **Queue occurrence 误认与 revision 竞态：** 重复歌曲仍是不同 occurrence；Played/current 不可排序；baseline 改变时丢弃拖拽草稿；clear 绝不等于 Stop（Batch 5–8）。
5. **触摸/连续布局与数据窗口竞态：** 顶部退出、seek、歌词滚动、历史 reveal 和行拖拽互不抢手势；慢数据、长标题、大字体和 ACTION_CANCEL 不引发布局二次跳变或假数据（Batch 2、4、5、7、8）。

## 文件与接口映射

下列是后续实现目标与职责，不代表本轮创建这些产品源文件。表中生产源文件路径均相对于 `app/src/main/java/io/github/qyxx2/mpdserver/remote/`；测试文件使用 `app/src/test/java/io/github/qyxx2/mpdserver/remote/` 或 `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/` 根路径。实现前以实际工作区再次核对同路径是否已有文件；若必须调整路径或签名，应在 Task 4 ledger 记录 ruling 后继续，不得顺带改无关模块。

| 职责 | 计划目标文件 / 现有边界 | 责任 Batch |
|---|---|---|
| 强类型歌曲/Queue/观察资源 | 修改 `protocol/dto/LibraryDto.kt`；创建 `protocol/dto/QueueDto.kt`、`PlaybackObservationDto.kt`、`OutputObservationDto.kt`；复用 `StateSnapshotDto.kt`、`FullStateSnapshot.kt` | B0 |
| Runtime-owned 资源读取 | 创建 `runtime/RuntimeReadApi.kt`（或相邻 data 层的同等单一入口）；修改 `AppRuntime.kt` 注入同一 OkHttpClient 与当前 origin provider；只经现有 `HttpTransport`/HTTP endpoint 读取 Song、Played 和 artwork bytes | B0 |
| 事实/展示投影 | 创建 `ui/player/PlayerFacts.kt`、`PlayerPresentation.kt` 与纯单测；从共享 SnapshotStoreState 映射，不新建状态 owner | B1 |
| 全屏封面与布局 | 创建/扩展 `ui/player/FullScreenPlayerScreen.kt`、`ArtworkStage.kt`；改 `ui/navigation/AppDestination.kt`、`ui/navigation/AppNavHost.kt` 与路由集成；Queue 新增为非 root 的全屏二级 destination | B2 |
| 播放时钟与操作 | 创建 `ui/player/PlayerClock.kt`、`PlayerActions.kt`；绑定 AppRuntime 的快照与 MutationCoordinator | B3 |
| 歌词解析/同步 | 创建 `ui/player/lyrics/LyricsParser.kt`、`ui/player/LyricsStage.kt`、必要的歌词 presentation/state；调用 B3 定义的 seek action interface | B4 |
| Queue 显示/资源投影 | 创建 `ui/queue/QueuePresentation.kt`、`QueueScreen.kt`、`QueueOccurrenceRow.kt`、内存资源缓存/有限并发预取器 | B5 |
| Queue 菜单与写操作 | 创建/扩展 `ui/queue/QueueActions.kt`、共享操作活动投影与行菜单 | B6 |
| Queue 拖拽草稿 | 创建 `ui/queue/QueueReorderDraft.kt` 或同等纯状态类型，与 QueueScreen/row 集成 | B7 |
| Played 连续 reveal | 创建 `ui/queue/PlayedRevealState.kt` 及专门的 pointer/layout 测试 | B8 |
| 退出门、报告与边界检查 | 更新 Task 4 专用 SDD ledger、验证报告、Task 4 合同矩阵与主计划；必要 UI 测试归原 Batch | B9 |

## 合同矩阵到 Batch 的闭环映射

| 合同 ID | 责任 Batch | 必须覆盖的自动测试面 | 人工设备验收面 |
|---|---|---|---|
| T4-01 实际播放事实 | B1、B3、B9 | 所有 freshness/bound/sync/actual-state 分支与控件能力 | 真实 MS 切歌、外部漂移、断线恢复；若不可用则 BLOCKED |
| T4-02 曲目/封面/歌词一致性 | B0、B1、B4、B5、B9 | occurrence/target/generation 变化、迟到资源丢弃、资源缓存失效 | 快速切歌时封面/歌词不串曲 |
| T4-03 全屏进退/Insets/手势 | B2、B8、B9 | Insets/Cutout、顶部下滑识别、tap-vs-drag、退出与 reveal/seek/drag 冲突 | 真机通知栏手势、顶部退出、返回栈恢复 |
| T4-04 B 封面布局 | B2、B9 | 可用高度、长标题、大字体、窄屏、无封面、底部锚点 | 真机长屏截图与布局检查 |
| T4-05 进度与 controls | B3、B9 | 共享 clock lease、seek draft 撤销、target 绑定、幂等/unknown/readback | 播放、暂停、上一首、下一首、seek |
| T4-06 源文件参数 | B0、B1、B2 | 缺失/未知元数据和切曲更新；不读 output 参数 | 有/无源参数曲目及切歌 |
| T4-07 DAC/output 观察 | B0、B1、B2 | fresh/stale/unknown/ACTIVE/INACTIVE/UNAVAILABLE 投影 | 可用 DAC 时检查；没有硬件证据时记录 NOT COVERED |
| T4-08 底部三个入口 | B2、B4、B5、B9 | icon 选择态、不同 glyph、48dp 热区、TalkBack label | 图标意义、对比度、触控与避让 |
| T4-09 歌词模式 | B0、B3、B4、B9 | LRC/fraction/offset/plain/missing/read_error、两行上限/当前行、手动滚动与 seek | 歌词往返、同步、切歌、无歌词/失败 |
| T4-10 Queue 定位/重入 | B5、B8、B9 | current 未确认/空/重复 occurrence、初始窗口、退出重进 reset | Queue 进出和当前项定位 |
| T4-11 连续 Played reveal | B0、B5、B8、B9 | snapshot freshness/最多一次全量读取、fractional drag/release/CANCEL、无逐帧请求、迟到响应 | 慢拖/快拖/停顿/松手/取消/继续拖 |
| T4-12 Queue 拖拽 | B5、B7、B9 | position 限制、revision/current/epoch/generation 改变、重复 song_id、取消不写 | 长按拖动/取消/提交/权威读回 |
| T4-13 行菜单/指定 occurrence 播放 | B0、B5、B6、B9 | route/payload/queue_item_id/幂等/菜单禁用与错误恢复 | 同名或重复曲目菜单与点击播放 |
| T4-14 清空待播边界 | B6、B9 | clear path 与 Stop 分离、204、autoplay/refill 权威读回、unknown | 用户授权后真实清空并核对 current 未被伪造 |
| T4-15 艺术家/专辑跳转 | B2、B9（列表本体归 Task 5） | 类型/ID/displayName 路由载荷、返回播放器状态 | 真机跳转目标身份/返回；Task 5 列表本体未就绪时记录依赖 |
| T4-16 资源缺失/异常 | B0、B1、B4、B5、B9 | 缺封面/无歌词/普通文本/read error/stale unknown 单独状态 | 实际资源样例与失败路径 |
| T4-17 活动/错误反馈 | B3、B6、B9 | shared activity pending/success/failed/unknown/syncing、重复提交与同 key 恢复 | 断网/超时/重连；不可安全触发时记录 NOT COVERED |

---

## Batch 0：强类型资源合同与 Runtime 共享只读入口

**目标：** 将歌曲、歌词、Queue occurrence、playback/output observations 与二进制 artwork 读取契约整理为可测试的类型和 Runtime-owned 读接口；不添加任何 UI，不新增写 API。

**Files：**
- Modify：`protocol/dto/LibraryDto.kt`、`data/LibraryRepository.kt`（若只需要类型映射则保留现有 repository 路由）。
- Create：`protocol/dto/QueueDto.kt`（`QueueItemDto`、`QueueListDto`、`QueueSnapshotDto`）、`protocol/dto/PlaybackObservationDto.kt`、`protocol/dto/OutputDto.kt`（`OutputStateDto`、`OutputSnapshotDto`）、`protocol/dto/OutputObservationDto.kt`。
- Create：`runtime/RuntimeReadApi.kt`；Modify：`runtime/AppRuntime.kt` 增加该只读入口。
- Test：`app/src/test/java/io/github/qyxx2/mpdserver/remote/SongDtoTest.kt`、`app/src/test/java/io/github/qyxx2/mpdserver/remote/QueueDtoTest.kt`、`app/src/test/java/io/github/qyxx2/mpdserver/remote/QueueSnapshotDtoTest.kt`、`app/src/test/java/io/github/qyxx2/mpdserver/remote/PlaybackObservationDtoTest.kt`、`app/src/test/java/io/github/qyxx2/mpdserver/remote/OutputDtoTest.kt`、`app/src/test/java/io/github/qyxx2/mpdserver/remote/OutputObservationDtoTest.kt`、`app/src/test/java/io/github/qyxx2/mpdserver/remote/runtime/RuntimeReadApiTest.kt`、`app/src/test/java/io/github/qyxx2/mpdserver/remote/RepositoryRoutesTest.kt`（测试包遵循现有项目约定）。
- Fixtures：`app/src/test/resources/fixtures/task4/song-response.json`、`queue-list.json`、`queue-snapshot.json`、`playback-observation.json`、`output-snapshot.json`、`output-observation.json`；最小样例来自已冻结 API schema 字段，不放用户曲库媒体或凭据。

**Interfaces：**
- `SongDto` 必须准确匹配服务端 `SongResponse` 字段，而非当前错误的 `artist` 简化字段；至少覆盖 song_id/title/file_uri/artists/album_id/album/album_artists/track/disc/year/date/genres/tag_names/duration/lyrics/lyrics_format/lyrics_source/lyrics_status/bit_depth/sample_rate_hz/channel_count/codec/metadata_status/last_scanned_at/file_size/file_mtime_ns/content_hash/availability_status/last_seen_at/artwork 等全部服务端字段。字段缺失、显式 null 和默认值按服务端 schema 明确区分。
- `QueueItemDto` 保留 `queue_item_id`、`song_id`、`position`、`source`、`playback_context_id`；不在客户端重建 occurrence。
- `QueueSnapshotDto` 对应 snapshot 中的 `queue: { revision, items }`；API `QueueListDto` 对应 `{ items, count }`。二者不可混用或丢失 revision。
- `OutputSnapshotDto` 对应 `output: { states, last_request }`；`OutputStateDto` 保留 mode/status/format/sample_rate/bit_depth/channels/error/stale 等现有字段，`OutputObservationDto` 单独表达 observed_at/freshness/error。
- `RuntimeReadApi` 只读接口建议固定为：`suspend fun getSong(songId: String): ApiResult<SongDto>`、`suspend fun getPlayed(): ApiResult<QueueListDto>`、`suspend fun getArtwork(songId: String): HttpResult`。以当前 origin provider 在每次请求时创建 `HttpTransport`，复用 AppRuntime 同一 OkHttpClient，在 IO dispatcher 执行；注入 transport/执行 seam 供单测替身使用。
- Snapshot 内的 current song、queue、playback observation 和 output observation 仍来自 snapshot JSON；使用显式 decoder 将这些元素投影为 DTO。不得把独立 GET 当成超越 snapshot freshness 的播放权威。
- artwork 使用现有 `GET /api/library/songs/{song_id}/artwork` 与 response MIME/bytes；404、非图像、坏 bytes 必须能表达为资源缺失/读取失败，不添加图片网络库。

**Steps：**
- [x] 先新增真实 schema fixture 和测试：完整 SongResponse 解码、未知/缺失必填字段失败、可空字段的 missing/null 规则、Queue occurrence identity、observation target/token 和枚举约束。
- [x] 运行目标测试；测试 fixture 使用冻结服务端 schema。初次运行发现 DTO 名称与既有 `SystemDto.kt` 的 `OutputRequestDto` 冲突，已按实际类型边界重命名为 `OutputLastRequestDto`。
- [x] 实现强类型 DTO/显式 snapshot resource decoder 与 RuntimeReadApi；对 id 做 path-segment 校验/编码，二进制与 JSON 错误结果分开，不创建页面级 transport/client。
- [x] 对应 DTO/read API 测试通过：Fake executor 核验 GET 方法、端点路径、每次请求重新解析 origin、重复 Queue occurrence 身份、严格 enum、原始 artwork bytes、404 与非图像响应区分。
- [x] 执行离线等价验证 `./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug` 与 `git diff --check`；无依赖声明变更。结果写入 Task 4 ledger；B0 代码与测试单独提交。

**退出门：** API schema 与 DTO 一一对应；所有资源通过唯一 Runtime-owned 查询入口；可空/缺失语义与 binary error 被明确区分；无产品 UI、写操作、第二个 client/runtime 或新 endpoint。

## Batch 1：canonical 播放事实与纯展示投影

**目标：** 建立可复用、无副作用的 current/actual playback、歌曲信息、source format、DAC/output 状态 presentation，先用单元测试固定服务端事实与 UI 能力边界。

**Files：**
- Create：`ui/player/PlayerFacts.kt`、`ui/player/PlayerPresentation.kt`。
- Create：`app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/player/PlayerFactsTest.kt`、`app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/player/PlayerPresentationTest.kt`（在实际包路径创建）。
- Reuse：`SnapshotStoreState.actualPlaybackConfirmed`、`SnapshotStoreState.controlsEligible`、Batch 0 DTO；不要复制 SnapshotStore owner 或在 mapper 写 state。

**Interfaces：**
- 纯函数输入为一个 `SnapshotStoreState` 与相应 current/Queue DTO projection；输出包括 confirmed/unknown diagnostics、current occurrence id、曲目元数据、source format、output observation status 与 pause/resume/previous/next/seek 的可用性。
- 首屏 current Song 与 position 0 occurrence 必须用 `bound_queue_item_id == queue_item_id` 绑定；同名歌/同 song_id 的重复 occurrence 不能混淆。
- source format 只用 SongDto 的 codec/bit depth/sample_rate_hz/channel_count；DAC 字段只用 output 与 output_observation。

**Steps：**
- [ ] 为 freshness、actual_freshness、matches_current、bound ID、sync status、actual_state 的组合写纯单测；特别断言业务 state 为 PLAYING 但 observation UNBOUND/stale 时不显示 confirmed，也不开放依赖 fresh target 的控制。
- [ ] 运行目标测试确认 RED，然后用纯 mapper 实现最小 presentation 和明确 diagnostic；不增加网络请求、UI 控件、时钟或业务状态存储。
- [ ] 测试源文件音频参数与 DAC 输出参数独立；Output ACTIVE 不显示“bit-perfect/硬件发声”；缺失/unknown/stale/unavailable 不能用假数字代替。
- [ ] 测试 current/song metadata 缺失、坏 JSON、无封面引用、未知 lyrics_status 时使用明确的缺失/错误状态，不能保留上一首数据冒充新曲。
- [ ] 运行纯单测及 `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`、`git diff --check`；记录并单独提交 Batch 1。

**退出门：** T4-01、T4-06、T4-07 所需的纯事实投影测试通过；状态层与展示层边界明确；无 UI 写操作。

## Batch 2：全屏封面模式、App 导航和安全手势

**目标：** 将 Task 3 的全屏占位页面替换为 B 方向封面布局，完成响应式布局、底部三个无框入口、应用内容顶部下滑退出，以及艺术家/专辑导航请求边界；播放操作在 Batch 3 接入前可为真实禁用状态。

**Files：**
- Create：`ui/player/FullScreenPlayerScreen.kt`、`ArtworkStage.kt`、`PlayerEntityNavigationRequest.kt`（或导航模块中的等效类型）。
- Modify：`ui/navigation/AppDestination.kt`、`ui/navigation/AppNavHost.kt`、现有导航/全屏 instrumentation tests。
- Test：`app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenPlayerLayoutTest.kt`、`app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenPlayerGestureTest.kt`；instrumentation：`app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/navigation/AppNavigationUiTest.kt` 或新增 `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenPlayerNavigationUiTest.kt`。

**Interfaces：**
- FullScreenPlayerScreen receives the same AppRuntime/read-only state and typed presentation through parameters; it never constructs an owner.
- 在 `AppDestination` 中新增 `QUEUE` 全屏二级 destination，不能加入 `rootDestinations`；播放器与 Queue 共用底部入口和同一 Runtime。底部中间入口从 Queue 返回正在播放目的地，右侧入口进入 Queue，左侧入口表示 DAC/output details。中间图标必须是与播放/暂停不同的波形/均衡器式图形；三个图标无底框且只有当前入口使用强调色，触控区域至少 48dp。
- 艺术家/专辑点击产生明确的 `PlayerEntityNavigationRequest(type, id, displayName)`；只将路由请求传给应用导航边界。不得在 Task 4 复制 Task 5 的列表或集合浏览。
- Player 的 dismiss pointer region 覆盖 WindowInsets/DisplayCutout 计算后的安全内容顶部带和专辑封面图像本身；不覆盖曲目信息文本/未来歌词区域、进度条、播放控制或底部导航。仅主要向下 vertical drag 关闭，普通 tap 不触发关闭，歌词 tap 行为仍归 Batch 4。全屏 player layer 整体随手指位移，低于阈值/ACTION_CANCEL 回到原位，超过阈值动画离屏；两个上边角随拖动增加到最大 32dp，底层根页面模糊程度随位移逐渐减小。进度条/控制/底部入口不属于 gesture region。Queue 仍仅使用安全内容顶部手势带，正文下拉/seek/Queue 行控件优先级不得被抢夺。

**Steps：**
- [x] 先新增 Compose 布局/语义测试，断言 B 方向层级、标题/艺术家/专辑顺序、底部三个图标的独立语义、48dp 热区、无顶栏切换/收起按钮、无假封面数据、原四个 rootDestinations 顺序不变、QUEUE 不成为 root tab，并新增顶部手势区域判定测试。
- [x] 运行目标 UI/逻辑测试，验证新 screen/gesture contract 不存在时 RED；不能只验证测试 tag 存在，要检查实际显示和点击/拖动结果。
- [x] 实现全屏布局：根据可用宽度与扣除 Insets、元信息、进度、控制和底部锚点后的可用高度计算封面尺寸；不硬编码截图像素/状态栏高度；没有 artwork 时展示真实空占位。AppNavHost 的全屏显示判定同时覆盖 FULL_SCREEN_PLAYER 与 QUEUE 两个二级 destination；两者共用同一 Runtime，均不显示根导航栏/迷你播放器。
- [x] 实现底部无框入口、由 WindowInsets 定义的内容顶部退出手势和系统 Back 返回入口；对普通点击 vs 垂直 drag 使用 tap slop/方向判定，顶部退出不得消费 Queue 正文或 seek pointer。
- [x] 测试 artist/album 导航事件携带正确类型与 ID，返回时 full-screen back stack 与 playback state owner 不变。
- [x] 运行 `./gradlew --offline --no-daemon testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug`；运行真实设备 instrumentation，记录结果，并执行 `git diff --check`。
- [x] 按用户要求，将 Batch 2 与先前未提交的 MS 服务器地址设置及相关合同/验证文档一并提交；二者共用 `AppNavHost`、`MpdServerRemoteApplication`，避免拆分造成不完整提交。提交后确认工作区干净。

**退出门：** 封面模式布局与导航符合视觉合同；没有假媒体数据或额外顶部控件；顶部退出与 Android 系统手势区隔开；artist/album 请求边界完成。

### Batch 2 corrective follow-up — player dismiss and mini-player expansion (2026-10-27)

**范围只修正 B2 已有的全屏页面导航/手势，不开启 B3/B4 或 Queue 主体实现。**

- Player dismiss gesture 覆盖安全内容顶部带和专辑封面图像本身，不覆盖曲目信息文本/未来 B4 歌词区域、进度条、播放控制和底部导航；普通 tap 不触发关闭，预留未来 B4 封面点击切歌词的 tap 语义但不实现歌词。
- 全屏页面图层整体垂直跟手。release 距离达到 `max(120dp, viewportHeight × 18%)` 才离屏关闭；未达到或 ACTION_CANCEL 则用无回弹 spring 恢复。下滑中顶部圆角最多 32dp，底层当前根页面从 18dp blur 随拖动逐渐恢复清晰；关闭完成时底层为正常清晰状态。
- 根页面从同一 AppShell/RootNavigationBar/MiniPlayerPresentation 与共享 Runtime 快照显示为仅视觉 backdrop，屏蔽交互语义；不创建额外 runtime/store/network/mutation owner。点击 mini-player 时，全屏图层约 250ms 从迷你条所在的下方区域展开，不做整页 alpha fade。Queue 仍沿用顶部手势带语义。
- [x] **TDD / 单测：** 先新增 `FullScreenPlayerGestureTest` 的拖动进度、blur、圆角、阈值和进场缩放 policy 测试；RED 记录在 `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b2-motion-correction-red.log`，GREEN 在 `b2-motion-correction-focused.log`。
- [x] **设备 instrumentation：** SM-G986N / Android 13 的 Player/Queue navigation 与 presentation 共 7 项通过；覆盖专辑封面起手长下滑关闭、短拖与普通 tap 不关闭、Player 与 Queue 原顶部关闭、共享 Runtime/根 chrome 边界。结果在 `b2-motion-correction-device-tests-final2.log`。
- [x] **构建、Lint 与安装：** `./gradlew --offline --no-daemon testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug`；以及 `adb install -r` 后检查 DataStore。不得写真实播放/Queue 状态。

### Batch 2 corrective follow-up — full-screen blurred artwork background (2026-10-27)

**原 B2 漏项，现在补齐，不留给后续批次。** 产品工程规格 §9 已要求整个全屏 Player 使用当前已确认专辑图的高斯模糊背景与主题蒙版。原 B2 实现只有下滑关闭时的“根页面背景解模糊”层，播放器自身缺少封面模糊背景；这属于漏实现，而非计划留到后续批次。

- [x] 增加 `PlayerArtworkBackdrop`，作为全屏播放器最底层视觉背景覆盖整个 Player 内容区域，不参与测量/布局，也不接收触摸；清晰封面仍作为前景保持原样。
- [x] 背景图片只能从同一 fresh/confirmed PlayerPresentation 的当前 song/artwork 资源加载结果生成；解码后在后台线程生成低分辨率高斯模糊图。前景与背景按同一曲目 identity 原子更新，切歌/资源身份变化先清除旧图，迟到/取消响应不得把旧封面设为新背景。
- [x] 使用纯软件的下采样 + separable Gaussian kernel，保证 minSdk 26 可用，不使用 RenderScript、新依赖或 API 31-only `Modifier.blur` 作为唯一实现。
- [x] 浅/深主题分别叠加主题背景色蒙版，以压低图像对比同时保留柔和色彩。没有可用封面时使用 app 内置的低细节渐变占位背景，再叠加同一主题蒙版；不拿 Queue 中其他歌曲/最后一次封面充当背景。
- [x] Player destination 的外层 Surface 改为透明，避免原本不透明的主题背景遮住整页模糊图片；这不改变 Queue 的表面颜色和其背景规则。
- [x] 单测覆盖背景下采样尺寸与主题蒙版选择；实机 Compose 测试覆盖背景图层、缺图占位、蒙版与整页层级，同时保留原手势测试。
- [x] 验证 `testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug`、全屏播放器相关真机 instrumentation、overlay install 与 DataStore 原样保留。记录见 `b2-artwork-backdrop-focused.log` 及后续最终日志。

### Batch 2 corrective follow-up — full-window edge-to-edge player motion (2026-10-27)

**根因确认：** `MainActivity` 原本已通过 `WindowCompat.setDecorFitsSystemWindows(window, false)` 启用 edge-to-edge，且状态栏为透明。问题出在 `AppNavHost` 把完整 Player motion layer 放在 `.padding(safeContentPadding)` 内，导致真机测得 layer 起点为 `y=73px`（状态栏 inset），所以顶部透明系统栏下面的画布与手势层没有随 Player 一起移动。这不是缺少 edge-to-edge 配置，而是动画层级放错。

- [x] 将 Player 路由运动层提升到全窗口尺寸，覆盖窗口物理顶部到底部；根页面 backdrop 与输入遮罩仍留在 safe-content 容器内，避免拦截实际状态栏/挖孔区触摸。
- [x] 将实际 `safeContentPadding` 下移到全屏 Player 的前景 Column，保证艺术家/封面信息/进度/控制/底部入口及顶部退出带仍避开真实系统栏 Insets。背景与圆角/位移共用同一个 full-window graphics layer。
- [x] 增加真实 `MainActivity` instrumentation：断言动画层从 y=0 延伸到窗口底部、封面背景从 y=0 开始、手势带起点等于 Android 当前安全 top inset；手势带内短拖时检查完整 motion layer 的 top 同步向下移动，松手后回到 y=0。
- [x] TDD RED 真实设备日志 `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b2-edge-to-edge-red.log` 证实旧层范围 `Rect.fromLTRB(0.0, 73.0, 1080.0, 2400.0)`，状态栏下面那段被排除；第一次布局 GREEN 为 `b2-edge-to-edge-green.log`，含短拖与恢复断言的最终设备测试为 `b2-edge-to-edge-gesture-final.log`（SM-G986N / Android 13，2 tests passed）。
- [x] 完成全量 unit/Lint/AndroidTest 编译、既有 Player/Queue 手势回归、debug APK 安装与 Insets preference 保留检查；结果已写入 B2 report 和 progress ledger。设备回归 `b2-edge-to-edge-device-regression.log`（10/10 通过）；完整本地验证 `b2-edge-to-edge-finalverify-rerun.log`（163 单测通过，Lint/AndroidTest 编译通过）。Android 系统图标/通知栏手势仍归系统管理；移动的是透明系统栏下方的完整应用画布。

### Batch 2 corrective follow-up — eager in-memory current artwork (2026-10-27)

**范围仅修复 mini-player 展开时封面与模糊背景迟到的问题；不启动新 Batch，不改变播放/API/Web/Queue 合同。**

- [x] 保留现有 `AppRuntime.reads.getArtwork()` 作为唯一图片读取入口，新增 AppNavHost 生命周期内的单项内存缓存；仅在共享 snapshot 投影出 fresh/confirmed 的当前 Song、有效 artwork 引用及当前连接 generation 时预加载。未确认/缺失封面不产生缓存键。
- [x] 前景封面与高斯模糊背景一起在后台解码/生成，再作为同一个资源身份的 `Ready` 状态发布。缓存键绑定 server-owned `song_id`、文件资源身份、server epoch、library revision 与 connection generation；切歌/重连/库版本变化时取消或废弃旧请求，并对迟到结果使用 request token 隔离，不能把上一曲图像展示为当前曲目。
- [x] mini-player 点击仍同步进入 NavController，避免异步导航破坏 back-stack lifecycle；全屏画布先保持在 mini-player 的折叠尺寸，route effect 等当前 artwork 进入 `Ready`/`Failed` 后才启动现有 250ms 展开动画。成功时展开帧之前已同时有清晰封面与模糊背景。读取失败在一次预热后回落到既有缺图占位；同一资源身份失败后保持缺图占位，不在点击路径重复阻塞请求；曲目/库版本/连接身份改变后才会重新预载。
- [x] 增加设备 instrumentation：覆盖应用导航前预加载、前景与模糊位图共同 ready、同身份复用与同身份失败去重、未确认/缺封面不预载、旧曲迟到结果不能覆盖新曲，以及全屏读取预热缓存。TDD RED 见 `.superpowers/sdd/2026-10-27-task-4-full-player-queue/artwork-preload-red.log`；最终本地与设备结果记录于 `.superpowers/sdd/2026-10-27-task-4-full-player-queue/artwork-preload-finalverify.log` 和 `artwork-preload-device-tests.log`。
- [x] 全程只读现有 artwork endpoint，不创建第二个 Runtime、HTTP client、WebSocket、SnapshotStore 或 mutation owner；没有新增依赖、持久化业务图像或服务端行为。

## Batch 3：共享播放时钟、进度草稿和播放操作

**目标：** 在 Batch 2 的播放器布局中接入真实播放 controls、服务器观察驱动的显示时钟、seek 草稿、共享 MutationCoordinator 状态与未知结果恢复。

**Files：**
- Create：`ui/player/PlayerClock.kt`、`PlayerActions.kt`、必要的纯 action-state 类型。
- Modify：`ui/player/FullScreenPlayerScreen.kt`。
- Test：`app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/player/PlayerClockTest.kt`、`app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/player/PlayerActionsTest.kt`；instrumentation：`app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/player/PlayerControlsUiTest.kt`（播放控件可访问性/禁用条件/seek 手势）。

**Interfaces：**
- `PlayerClock` 只产生展示位置，不写 canonical snapshot；基于 fresh `playback_observation.position_seconds`、现有 `StateReadCoordinator` 产生的 `SnapshotReadTiming` 与单调时钟，在同一个 observation/target/current identity 下最多外推 6 秒。sample age 与 Web 对齐为服务端 `captured_at - observed_at + HTTP RTT`，phone/server wall clock 不互相比较；没有可信 read timing 时只展示服务端 base，不外推。相同样本的后续读数可更新 base，但不可延长 lease。切换到后台、观察 stale 或身份改变时不继续外推。
- `PlayerActions` 接收唯一 Runtime 与当前 presentation；调用现有 `runtime.mutations.submit(MutationIntent(...))` 并观察 `runtime.mutations.activities`。bodyless POST 按 HttpTransport 合同显式使用 `{}`；RESUME 传入 observation 中原样 target/token，SEEK 使用 `{seconds, target}`。所有请求和控制 enabled 条件在 dispatch 前重新检查。
- Seek 拖动时仅维护本地 draft/preview；release 时身份仍匹配、控件仍可用才发一个 POST。任何目标 identity、current occurrence、snapshot epoch、generation 或 freshness 改变均取消 draft，不提交旧 seconds。
- UNKNOWN 只能显式重试原意图，复用协调器保存的 key/origin/payload；不生成新 key 静默重发。回执成功后权威读回失败时仍保留“写入成功、同步失败”语义。

**Steps：**
- [x] 新增 clock/action 测试：6 秒 lease 到期与 sample age/RTT 扣减、重复观察样本不能续期、pause 不外推、无可信 read timing 不外推、stale/disconnect/identity change 取消 clock 和 seek draft。
- [x] 新增 action 测试：playing 显示 pause、paused 且 target fresh 时显示 resume；target 缺失/stale/unknown 时禁用；seek release 仅发一次；current/target/revision 变化前后释放都不能误写。
- [x] 运行测试见证 RED；实现 injected monotonic clock 的纯 PlayerClock 与通过 MutationCoordinator 的 action dispatch，不做页面级请求/重试 owner。
- [x] 覆盖 POST 路径/方法/payload、resume target/token 原样保留、seek seconds/target、连续点击去重、明确 HTTP error vs UNKNOWN、成功回执但读回失败、明确的用户重试复用同 key。
- [x] 将上一首/播放暂停/下一首与 seek slider 接入 UI；不增加不在已确认布局中的额外按钮。进度视觉位置仅用 local draft preview，权威回读前不写本地 snapshot。
- [x] 运行 `./gradlew --offline --no-daemon testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug`、`git diff --check` 和 mutation-owner 静态扫描。真实播放写入未执行，已在 [Batch 3 验证报告](../reports/2026-10-27-task-4-batch3-verification.md) 记录 NOT COVERED，以避免未授权地改变用户当前播放。

**退出门：** T4-01/T4-05/T4-17 的播放器操作契约在自动化层通过；所有写请求共用 Runtime coordinator；未知结果不冒充失败/成功、不自动换 key 重发。

## Batch 4：Web 等价歌词解析、手势、点击 seek 与自动回位

**目标：** 原生 Compose 歌词行为逐项继承固定 Web 实现：带时间轴的 LRC/内嵌歌词可按当前播放时钟高亮与自动跟随、可点具体歌词行 seek；用户手动滚动后暂停跟随，停止交互 3 秒后回到当前正在播放的行。无时间轴的普通文本可以自由上下滚动，不创建伪造 cue、不支持按歌词行 seek，也不触发定时自动回位。

**Files：**
- Create：`ui/player/lyrics/LyricsParser.kt`、`ui/player/LyricsStage.kt` 与最少量歌词交互 state。
- Modify：`ui/player/FullScreenPlayerScreen.kt`。
- Test：`app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/player/lyrics/LyricsParserTest.kt`、`app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/player/lyrics/LyricsPresentationTest.kt`；Compose `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/player/LyricsStageUiTest.kt`。
- Reference implementation：固定基线仓库 MPD-Server 的 `web/src/components/player/lyrics.ts`、`LyricsStage.vue`、`playerSession.ts` 与服务端 `server/app/services/media_metadata.py`。Android 按其可观察语义实现，不复制 Vue/DOM。

**Interfaces：**
- 解析器输入是服务端返回的 `lyrics` 原文和 `lyrics_format`；保留原文、cue、note、offsetMs、synchronized 状态。客户端不自行读取音频文件/ID3/FLAC tag，也不扫描 sidecar；嵌入歌词提取由服务端负责，UI 按 SongDto 的 `lyrics_source`、`lyrics_status`、`lyrics_format` 展示真实来源/状态。当前固定服务端扫描器从 FLAC comment 的 lyrics/LYRICS/unsyncedlyrics 字段或 MP3 ID3 USLT 帧提取嵌入文本；若服务端返回的嵌入文本含有效时间戳，Android 必须照同一 parser 处理，不能因它来自 embedded 或旧 format=text 而降级成不可同步的纯文本。
- 格式兼容规则严格对齐 Web：仅当 format 为 `lrc` 或 `text`（大小写不敏感）时解析 cue；即使旧资源把带时间轴的歌词标为 `text`，也仍识别有效时间戳。其它格式不做同步解析。支持 `[mm:ss]`、`[mm:ss.f]`、`[mm:ss:fff]`（小数位 1–3 位，分隔符 `.` 或 `:`）、同一文本行多个时间戳、`[offset:+/-整数毫秒]`、多行/重复时间戳、无时间轴的普通文本及 Web 已识别的元数据行。分钟不限两位，秒必须为 `00..59`；cue 按秒稳定排序，同一时间戳的源顺序保持不变。
- 小数解释严格兼容现有 Web `Number("0." + digits)` 规则，不擅自按 LRC 习惯把 1 位小数重新解释为十分秒以外的单位；例如 `.5` 为 0.5 秒、`.05` 为 0.05 秒、`.005` 为 0.005 秒。offset 参与 active cue：`adjustedPosition = freshPlaybackPosition + offsetMs / 1000`；点击 cue 的 seek 目标反向抵消 offset：`targetSeconds = cueSeconds - offsetMs / 1000`。只有目标在 `[0, duration]` 范围内且当前 seek capability/target 有效时，该 cue 才可点击。
- 同步 LRC 模式初始为 following。当前行仅由同一当前 Song/occurrence、fresh 的播放观察与 B3 展示时钟计算；位置未确认/unknown 时不高亮伪造的当前行，也不跳回任意行。active cue 选取小于等于 adjusted position 的最大时间戳；在第一条 cue 之前没有当前行，同一最新时间戳的 cue 可共同标为 active。
- 对带时间轴歌词，用户上下拖动/滚动时立即暂停自动跟随；从最后一次手势结束/有效滚动起 3 秒内没有继续操作，且没有仍在进行的手势或文本选择时，回到当前 active playing cue 并恢复跟随。继续滚动会重置 3 秒计时；返回位置必须根据计时触发时的最新 playback clock 计算，不能回到开始手势时缓存的旧行。用户点某条有效 cue 会提交一个 seek 到该 cue 的 offset-corrected time，并恢复跟随；收到回执后仍以服务端观察/readback 的实际播放位置决定 active cue，不以点击行冒充 seek 已完成。
- 普通无时间轴文本允许自由上下滚动查看；不得按拖动/静止 3 秒自动回到顶部或其它位置，因为它不存在 active playback cue。plain text、LRC 中不含可读 cue 的 metadata/notes、有效 cue 但播放位置 unknown 是不同状态。点击纯文本不发送 seek。文本选择不得触发 seek。
- 手指点击与滚动严格分开：有效 cue tap 才 seek；垂直 drag/scroll 不可因系统随后派发 click 而 seek；文本选择、取消/中断手势与长按选择不能意外 seek。手势/焦点范围仅在歌词正文，不能抢占顶部退出手势带、歌词回封面按钮或底部 progress/control。
- 曲目/歌词原文/format/当前 occurrence/epoch/generation/resource identity 改变时取消旧的滚动计时、手势草稿与选中 cue，重新按新资源初始 following 处理；迟到的旧 song 数据不得覆盖当前歌词。歌词视口填充顶部按钮区域下方至进度条前安全间距之间的主内容区；每条 cue 字号尽可能大、最多两行，超长文本仅第二行滚动显示剩余内容，且不得插入额外 cue；当前行变大/品牌色不得造成底部 progress、controls、source metadata、footer 的锚点跳动。
- 缺失歌词、`lyrics_status=missing`、`read_error`、无时间轴普通文本、未知 format、同步歌词但时钟不可用必须分别表达。服务端可同时返回可显示的 embedded fallback 文本与 `read_error`；即使能显示文本也要保留读取错误提示，不得只因歌词非空就判定读取成功。

**Steps：**
- [x] 先为解析器写测试：`lrc` 与 `text` 格式、大小写、`text` 中有效时间戳、unsupported format、分钟/秒范围、1/2/3 位小数的 Web 精确语义、`.`/`:` 分隔符、多 timestamp per line、稳定排序/相同时间戳、正负 offset、元数据 note 过滤与原文保留。
- [x] 写 RED→GREEN 的纯逻辑测试，比较关键 fixture 的 cues/offset/notes/synchronized 与固定 Web 行为；覆盖首条 cue 之前、两个 cue 之间、同时间戳及末条 cue 之后 activeCue 的选择规则。
- [x] 为歌词来源/status 写测试：sidecar 与 embedded 都只使用服务端最终返回值；embedded LRC 被标 `lrc` 时同步解析；旧数据 `lyrics_format=text` 但含时间戳仍解析；plain embedded text 可读但不同步；`missing`、`read_error`（含可显示 fallback text）和 unsupported format 不能混为一类。Android 不自行抽取/覆盖服务器的 sidecar 优先级。
- [x] 为手势 reducer 写 RED 测试：同步歌词开始时 following；手动上下滚动立即暂停；最后一次滚动/手势结束后 3 秒回到最新 active cue；不足 3 秒继续动作会重置计时；timeout 时仍有 gesture 或文本选择则不强制跳转，取消/完成选择后再按 3 秒规则处理。
- [x] 为 cue 点击/拖动分离写 Compose/UI 测试：有效 cue tap 的 offset-corrected target、不可用 capability/越界目标、drag/long-press 不 seek 均有自动化覆盖；写入路径复用 B3 MutationCoordinator。真实 pointer-cancel 与实际文字选区交互未在设备上单独覆盖，见验证报告。
- [x] 为 plain text 写 UI 测试：已验证长文本处于独立自由滚动模式，不生成 timed cue、不发 seek。实现只在 timed mode 启动跟随/回位 effect，因此 plain mode 不会触发该计时器；设备上静置超过 3 秒后的滚动偏移未单独量测，见验证报告。
- [x] 为当前行和歌词区域锚点写测试：fresh 位置驱动 active cue、unknown clock 不伪高亮；歌词模式切换不移动 progress/controls/footer 锚点。长句/系统字体缩放/窄屏截图与实际歌词切换的设备覆盖未执行，切曲身份重置主要由纯逻辑测试覆盖。
- [x] 实现纯 parser 与歌词跟随状态后，再接 Compose 手势和 B3 `PlayerActions`；禁止在 Composable 中直接发送 HTTP/创建新的 mutation owner。只在歌词正文中处理拖动和 cue tap，回封面按钮继续只切主内容模式。
- [x] 运行本计划的本地测试、lint、APK 构建、AndroidTest 编译与目标设备测试；`git diff --check` 通过。实际内嵌/普通歌词样本及真实服务端 seek 写入标记为 NOT COVERED。经依赖与共享文件审查，Batch 3 status-flap/handoff 修复与 Batch 4 一并本地提交：B4 使用 B3 的播放时钟/`PlayerActions`，两者共享 `FullScreenPlayerScreen.kt`，且 B3 展示投影由 `AppNavHost.kt` 接入；未拆出单独的 B4-only 提交。见 Batch 3/4 验证报告。

**退出门：** T4-02/T4-09/T4-16 的歌词合同测试通过；LRC/内嵌时间轴、offset、cue tap seek、滚动暂停与 3 秒回位、plain text 自由滚动均与冻结 Web 行为一致；无 WebView、假歌词、伪时间同步或 seek 目标漂移。

## Batch 5：Queue/Played 读取、初始定位与行呈现

**目标：** 读取 canonical Queue 与必要 Played resource，完成 Queue 当前项定位、pending 次序、行样式、稳定 occurrence key、有限元数据读取和重入重置；本 Batch 不实现写动作和拖拽提交。

**Files：**
- Create：`ui/queue/QueuePresentation.kt`、`QueueScreen.kt`、`QueueOccurrenceRow.kt`、`QueueSongCache.kt`（名称可在现有文件检查后细化）。
- Modify：`ui/navigation/AppNavHost.kt` 以及 full-screen/footer navigation tests。
- Test：`app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueuePresentationTest.kt`、`app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueSongCacheTest.kt`、Compose `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueScreenUiTest.kt`。

**Interfaces：**
- Queue current 是 position 0 的 occurrence；只有 B1 confirmed 规则满足时才以 playing/paused 标识为真实播放。Pending 是 position > 0、按 position 排序；next 保持 Web 规则：存在 MANUAL pending 时仅由其首项代表 next，否则只有当前播放/AutoPlay enabled 时最多呈现首个 AUTOPLAY 候选。Played 是 position < 0，保持后端 occurrence identity 与约定顺序。
- 进入 Queue 时 UI reveal 状态为 0 并定位 current；current 无法确认时显示“当前业务条目/实际播放未确认”，不能造一个 confirmed current。离开 Queue 后再次进入重新初始化 reveal/scroll，不改服务端数据。
- row key 始终是 `queue_item_id`；metadata 通过 Batch 0 RuntimeReadApi 按 `song_id` 读取，仅用于显示标题/艺术家/专辑/封面参数，不能替代 occurrence ID。
- 有限 metadata prefetch 用稳定缓存 key 和明确的并发上限；迟到数据必须核对 current epoch/library revision/generation 与仍可见 ID。仅在原行内 placeholder 里替换内容，不因加载而改变 row height 的测量策略或挪动 Queue order。缓存只在内存使用，不持久化为曲库副本。

**Steps：**
- [x] 先写并运行 deriveQueue RED 测试，再覆盖 position 0/current 确认/暂停、MANUAL 与 AUTOPLAY next 候选、Played position<0 次序、重复 song_id 的不同 queue_item_id、空/坏 Queue snapshot。
- [x] 为 Queue 初始 current 可见、稳定 occurrence key、stale 状态不误报播放、当前行信息与进入/离开/重进路由写 Compose/UI 测试；重进由导航回归实测覆盖。
- [x] 运行测试确认 RED；实现纯 QueuePresentation 与只读 QueueScreen/Row；Batch 5 内未加入菜单写操作、拖拽提交或连续 reveal 手势。
- [x] 为 song metadata/artwork cache 覆盖并发请求合并、并发上限、404/坏 MIME/无效图像签名、epoch/library revision/connection generation 变更与迟到读取丢弃测试；资源读取复用同一 RuntimeReadApi。
- [x] 验证 fresh canonical Played 不额外请求；snapshot 对 Played 不 fresh 时至多发起一次现有 `GET /api/history/played` 读取，并覆盖 loading/failure 与过期结果保护。
- [x] 完成 `./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin`、目标设备 `connectedDebugAndroidTest`、`git diff --check` 和单一 owner/source scan；报告记录测试计数、DataStore 保留与未覆盖的实时视觉检查，Batch 5 单独提交。

**退出门：** T4-02/T4-10 与 T4-13 的 occurrence/列表投影基础通过；Queue 只读展示不产生 mutation；没有假历史/假 next/按 song_id 去重。

## Batch 6：Queue 行菜单、待播操作与统一操作反馈

**目标：** 将 Queue/Played 视图连接到已有 Web/API 操作：指定 occurrence 播放、移动到下一首/队尾、删除待播项、保存 Queue 为歌单与清空待播；所有写入受共享 MutationCoordinator 与权威 snapshot readback 管理。

**Files：**
- Create：`ui/queue/QueueActions.kt`、`QueueRowMenu.kt`；复用共享活动 presentation 或为 Queue feedback 做纯 mapper。
- Modify：`ui/queue/QueueScreen.kt`、`QueueOccurrenceRow.kt`、`ui/player/FullScreenPlayerScreen.kt`（仅需要共享活动反馈时）。
- Test：`app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueActionsTest.kt`、`app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueMenuPresentationTest.kt`；MutationCoordinator regression：`app/src/test/java/io/github/qyxx2/mpdserver/remote/runtime/MutationCoordinatorTest.kt`；instrumentation：新增 `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueActionsUiTest.kt`。

**Interfaces：**
- Queue row play 固定走 `POST /api/playback/queue/items/{queue_item_id}/play`，使用被点击 occurrence ID。
- 对已在 Queue 中的待播 occurrence 移动/排序走 `PUT /api/playback/queue/items/{queue_item_id}` 和 `before_queue_item_id`；三点菜单按实际 Web 实现对每个可见行保留入口，Next/Add 仅对 `position > 0` 显示，Remove 按 Web 对所有可见行显示。提交 Remove 前必须确认 occurrence 仍在 canonical snapshot 中；最终是否允许删除当前项由服务端校验，不额外实现 song/collection algorithm。
- 保存 Queue 使用 `POST /api/playback/queue/save-as-playlist`；清空使用 `DELETE /api/playback/queue`。Queue clear 不调用 `/api/playback/stop`，也不能本地删除 current/Played 来制造清空成功。
- 所有 mutation 使用确定且不冲突的 intent ID 和既有 coordinator；POST/PUT 若 transport 合同要求 body，则使用契约明确的 `{}` 或精确 payload。HTTP 204 视为写入回执，后续 snapshot/readback 仍单独处理；读回失败不得把成功回执翻成“写入失败”。
- 未知操作只允许在用户明确点击重试时使用同一个 intent/key/origin/payload；target/epoch/generation/revision 改变需阻止过期 intent 的 UI 重发。

**Steps：**
- [x] 为每一项写操作先写测试并断言确切 HTTP method/path/payload、Web `encodeURIComponent` 兼容的 queue_item_id escaping、请求幂等语义和允许状态；覆盖重复标题但不同 occurrence ID。
- [x] 按真实 Web 源码建立菜单可用性契约：current/Played 仅显示 Remove，pending 显示 Next/Add/Remove；snapshot 不 fresh 或存在未解决 mutation 时所有写入口禁用。
- [x] 运行目标测试见证 RED，再实现 QueueActions 将 intent 交给唯一 Runtime MutationCoordinator；不得直接在 Composable 调 HTTP。
- [x] 覆盖成功回执后权威 readback、DELETE 204、写成功但读回失败、明确业务失败与 UNKNOWN 区分、显式 retry 复用同 key；在 readback 之前不手动修改 canonical Queue。
- [x] 对 clear 添加强断言：只提交 DELETE /api/playback/queue，不调用 POST /api/playback/stop，不清除 current/Played；处理后是否有 AutoPlay refill 以服务端快照为准。
- [x] 运行 `./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin`、`git diff --check` 与 UI 无 owner/直接网络调用扫描；真实 Queue 写入因未授权未执行并记录 NOT COVERED；独立 instrumentation 最终重跑被设备锁屏阻断（此前同一组 UI 测试 12/12 通过，见 Batch 6 报告）；Batch 6 单独本地提交。

**退出门：** T4-13/T4-14/T4-17 写操作由唯一 coordinator 管理；Queue clear 语义与 Stop 严格隔离；receipt/readback/UNKNOWN 不混淆。

## Batch 7：Queue 待播拖拽排序

**目标：** 增加 only-pending occurrence 的长按拖拽排序，基于不可变 baseline 的本地草稿提交，不改变 current/Played。

**Files：**
- Create：`ui/queue/QueueReorderDraft.kt` 与对应 reducer/state tests。
- Modify：`ui/queue/QueueScreen.kt`、`QueueOccurrenceRow.kt`。
- Test：`app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueReorderDraftTest.kt`、Compose `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueReorderUiTest.kt`。

**Interfaces：**
- 六点柄列与所有 Queue 行对齐，但 only `position > 0` 行接收 pointer/long-press；current/Played 的柄是视觉对齐提示并禁用手势。
- draft baseline 至少包含 snapshot epoch、connection generation/Runtime generation、Queue revision、current occurrence 与 pending occurrence 顺序/positions/source。任何 baseline 变化或 stale/controls unavailable 均取消 draft。
- drop 时生成以 `queue_item_id` 为主体的 PUT reorder intent；目标用 `before_queue_item_id`，不得用 song_id 去匹配。服务端读回的顺序才是最终顺序。

**Steps：**
- [x] 先为 reducer 写 RED 测试：pending 开始/目标插入、current/Played 拒绝、自己作为目标拒绝、拖动取消不提交、重复歌曲时 ID 身份仍正确；另为滚动视口中的 off-screen 插入目标建立回归测试并见证断言 RED。
- [x] 为 epoch/revision/generation/current 与 pending 成员身份/顺序/position/source 变化、stale/controls unavailable 和 pointer cancellation 写确定性测试；所有 baseline 漂移均阻断旧 baseline PUT。拖动过程中导航离开页面没有单独的 instrumentation 场景；草稿仅为 Queue composable 本地状态，路由销毁会丢弃它，此路径另记 NOT COVERED。
- [x] 运行测试见证 RED；实现纯 draft/reducer，再将长按/拖拽柄 pointer 与稳定 LazyColumn occurrence keys 集成到 Queue 行。
- [x] 测试完成 drop 后仅提交一次正确 `before_queue_item_id`；服务端快照未更新前不改 canonical 顺序，成功回执/UNKNOWN 均不以本地乐观排序冒充权威 readback。
- [x] 运行 `./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.queue.QueueReorderUiTest`、`git diff --check`；SM-G986N/Android 13 完成两项手势 instrumentation。真实 MS Queue 写入及服务端读回未执行，作为 NOT COVERED 记录。独立提交 Batch 7。

**退出门：** T4-12 的 reducer/UI 和 revision guards 通过；只有 pending 项可拖，服务端结果是最终顺序。

## Batch 8：Played 历史同列表展示、原生惯性滚动与资源预热

**目标：** 将 position<0 的真实 Played occurrence 从手势触发的零高度 reveal 区移入当前 Queue 的同一个 LazyColumn。初始视口仍锚定 current(position 0)，列表数据按 oldest→newest→current→pending 排列；用户从 current 向下滚动即可进入历史，并保留原生 fling 惯性。最近历史元数据在触摸前开始预热；滚动时只按可见范围扩展有界缓存，不为每次下拉/滚动发出 history GET。

**Files：**
- Modify：ui/queue/QueueScreen.kt、PlayedHistoryOrder.kt、Queue 行复用与缓存预取逻辑。
- Remove：不再需要的 PlayedRevealState.kt、PlayedRevealGesture.kt 与 PlayedRevealRegion.kt（以及专属 reducer/gesture 测试）。
- Test：app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/queue/PlayedHistoryOrderTest.kt、Compose app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/queue/PlayedRevealUiTest.kt。

**Interfaces：**
- Runtime/QueuePresentation 仍是唯一服务端状态入口。fresh snapshot 直接复用 position<0；数据不 fresh 时沿用现有一次性 GET /api/history/played fallback 和 request-generation guard；不添加数据 owner、API 或服务端分页。
- Played 与 current/pending 是同一 LazyColumn 的真实行。Played 使用稳定 occurrence key（历史语义前缀 + queue_item_id），先按 position 从旧到新排列，再接 current/next。首次进入与重进时将 current 定位到首个可见位置；列表滚动不修改 canonical Queue。
- 移除拦截 history reveal 的自定义 pointer/nested-scroll reducer，不消费 pointer-up 的 fling。滚动与惯性全部由 LazyColumn 提供，Played/current/pending 共用一套滚动状态。
- 在 Queue entry 时预热最近 20 个 Played song ID 的元数据，并按 viewport 两侧有限邻域扩展预取。缓存并发/容量仍由既有 QueueSongCache 约束；封面与元数据替换不得重置滚动位置；pointer movement 不触发请求。
- current 与 pending 行不足以填满视口时，在列表尾部添加透明的纯布局 scroll buffer，使 current 可锚定顶部并保留向前滚动范围。该 spacer 不是 Queue occurrence，不参与 canonical 状态或操作。
- Played 行继续使用当前的超薄白色蒙版（alpha 0.045）、分隔线与现有行操作；current/pending 视觉、排序与 mutation owner 不变。

**Steps：**
- [x] 先写显示顺序测试，将 Web 的 newest-first canonical Played 变成列表 oldest→newest；写 instrumentation 断言没有单独的 queue-played-reveal-region。
- [x] 验证 RED：历史排序期望与旧 reveal 交互测试先失败；随后用同列表排序与渲染替换旧实现。
- [x] 真机 Compose instrumentation 覆盖 current 初始锚点、原生轻扫惯性、position -1 紧邻 current、fresh snapshot 无 Played GET、元数据在手势前预热且滚动时不重复加载、历史滚动不提交 mutation。
- [x] 验证预热在 Queue entry 即启动、资源缓存按 viewport 邻域扩展；保留的 PlayedReadGeneration 延迟响应保护单测随完整 unit suite 通过。既有非 fresh fallback 未改动。
- [x] 执行 ./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin 和 PlayedRevealUiTest connected instrumentation，均通过；git diff --check 通过。未增加服务端/Web/API、依赖或权威状态 owner。
- [x] 在 SM-G986N / Android 13 执行 adb install -r，安装成功且未清除数据。firstInstallTime 始终为 2026-10-24 18:47:26；DataStore 文件安装前后 SHA-256 均为 86f0fc14d00b146e90ec67736c0103fbeffe9a57ef5309fb2f44aa1460e1666c。未执行真实播放或 Queue 写入；UI 验收使用合成历史快照，真实服务端历史外观未人工验收。

**退出门：** Played 作为真实 occurrence 与 Queue 共用列表和滚动惯性；初始 current 锚点、Web 排序、触摸前资源预热、读取代际与无额外写入都有测试证据；未覆盖的真实历史数据场景如实记录。

## Batch 9：集成回归、合同审计与 Task 4 退出门

**目标：** 跨播放器、歌词、Queue 与 Runtime 做完整验证和边界审查；真实设备/服务验收逐项记录，未覆盖项不得假标为通过。

**Files：**
- Update：`docs/superpowers/specs/2026-10-27-task-4-contract-matrix.md`（实际验证状态/证据）。
- Update：`docs/superpowers/specs/2026-10-27-task-4-full-player-queue-visual-design.md`（仅修正文档与最终已验收信息，不能重开已冻结设计）。
- Update：`docs/superpowers/plans/2026-10-24-android-client-master-plan.md`、`docs/superpowers/README.md`（Task4 状态与证据索引）。
- Create/Update：`.superpowers/sdd/2026-10-27-task-4-full-player-queue/progress.md`、`docs/superpowers/reports/2026-10-27-task-4-final-acceptance.md`；必要时保存本次设备 evidence。
- Test：全量现有 unit/UI/instrumentation test 与静态边界检查；不加与真实用户媒体相关的默认 fixture。

**Steps：**
- [ ] 建立端到端 Compose/runtime 测试：根入口 → full player → lyrics ↔ artwork → Queue → reorder/menu → Back；共享 AppRuntime identity 保持，current/lyrics/artwork/Queue occurrence 不串身份。
- [ ] 完整验证 stale/unknown、断线/重连、迟到资源、重复 song/occurrence、missing artwork/lyrics/read error、长标题/大字号/窄屏、seek/target 变化、UNKNOWN/retry 与 204/readback 语义。
- [ ] 执行 clean 全验证：`./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin`；设备与 ADB 环境可用时另执行 `connectedDebugAndroidTest`。保存完整日志到 Task 4 的 `.superpowers/sdd/...` 目录，并阅读最终输出和 test result XML。
- [ ] 扫描 app source/build 文件，确认未引入 Room/SQLite、WebView、Media3/ExoPlayer、额外 runtime/socket/client/coordinator owner、页面直接 HTTP/WS、持久化业务快照、伪数据、分页 endpoint 或 CI/服务器代码变更；运行 `git diff --check`。
- [ ] 在真实设备上逐项检查封面模式、歌词模式、progress/control/footer 锚点、浅/深主题、Insets/Cutout、顶部退出和系统通知栏手势、Queue 定位、连续 Played 展开、六点柄、溢出菜单、拖拽、204/读回/断线错误反馈。
- [ ] 与真实 MS 的播放/seek/Queue 变更只有在用户授权、状态可逆且风险可控时进行；不以“验收需要”为由播放、seek、清空或改动用户 Queue。未执行的真实操作、硬件 DAC 语义和缺失 Cutout 条件逐项记为 BLOCKED/NOT COVERED。
- [ ] 逐项复核 T4-01 至 T4-17 在合同矩阵中是否都有 Batch、自动验证结果、人工验证结果与退出标准。标注剩余风险；仅在所有必需 gate 满足后更新 Task4 退出状态并单独提交 Batch 9。

**退出门：** 无 Critical/Important 合同/代码问题；clean 构建、单测、Lint 和可运行的 instrumentation 有真实结果；共享 Runtime/状态/操作边界无违规；人工设备项及所有 BLOCKED/NOT COVERED 明确记录。自动化通过不代表未执行的真实服务/硬件验收通过。

## Batch 执行约定

- 每个 Batch 开始先读取本计划、Task 4 合同矩阵、视觉规格与 Task 4 ledger，并记录当前 HEAD、工作区 status、明确的 in-scope 文件与既有 user changes。除当前 Batch 外，不启动后续 Batch。
- 保持本仓库唯一工作区，不新建 worktree；不调用或检查其它编程代理/代理进程，不重新查询固定上游 HEAD。
- 每个 Batch 的提交只包含该 Batch 实际改动和必要进度/合同记录，不夹带开始前已存在的无关文件变化。真实服务写操作必须逐次得到用户授权；本计划本身不构成写操作授权。
- 只要验证命令未运行、测试未实际执行、真机/MS/DAC 场景不可用，必须如实标注 BLOCKED/NOT COVERED，不能用源码编译、单元测试或静态预览替代。
- 未来执行时遵循 Superpowers 测试先行、单 Batch、单独验证与提交流程。当前文档拆分结束即停止，不开始 Batch 0 的实现。
