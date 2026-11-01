# Task 7：Android 系统媒体会话、通知与锁屏控制 — 合同矩阵

> 状态：Batch 0 与 Batch 1 已实施并完成当前批次验证；Batch 2–4 尚未实施。系统媒体功能验收范围按用户确认设为 Android 13（API 33）及以上；API 26–32 不属于 Task 7 媒体功能保证范围，API 34+ 专项限制在后续接入相应能力时验证。
> Android 基线：`task7` 分支，起点 `main` / `1b01447`。本矩阵不改变 MPD-Server 上游基线。

## 1. 合同冻结判断

**结论：不需要额外的人工产品决策或用户确认。** Task 7 的产品边界已经由 Android 客户端产品与工程总规格 §11、§12、§13.2、Web 行为继承规格 §§2–5、架构规格 §§2、4–6，以及主计划 Task 7 明确冻结。核心行为不存在需要用户二选一的产品分歧：系统媒体会话只适配远端播放；元数据来自当前已确认歌曲；系统动作只有上一首、播放/暂停、下一首；所有写操作复用现有操作协调器；不创建本地播放器、第二条 WebSocket 或第二份播放状态。

需要在开始编码前完成的是**平台实现合同验证**，不属于额外的人工产品冻结：当前 targetSdk 为 36、minSdk 为 26，必须实测平台 MediaSession/MediaStyle 组合、通知权限、后台服务与服务类型，不能根据旧 Android 版本经验猜测。该验证归入 Batch 0，并形成可审查的实现决策记录。若某条平台路径不满足 remote-only 架构或目标 SDK 要求，应在 Batch 0 内选择符合既有产品合同的替代适配方式，不得引入本地音频引擎。

本轮采用以下保守默认值，不另行增加用户交互范围：
- 应用启动时不主动弹出通知权限请求；只在首次确实需要发布系统媒体通知时处理权限。
- 用户拒绝通知权限或系统限制后台能力时，不反复弹窗；按平台实际允许的能力降级，并在应用内明确说明状态及前往系统设置的入口。
- 媒体通知渠道使用低重要性、无声音/振动的媒体通知语义；锁屏具体展示形式和隐私遮挡由 Android/厂商系统设置决定，不承诺所有设备像素级一致。
- 不增加自定义通知点击深链或新的播放器导航入口；通知内容点击沿用应用现有启动入口，系统媒体动作直接调用媒体会话回调。

## 2. 基线事实与现有可复用能力

| 项目 | 当前事实 | Task 7 约束 |
|---|---|---|
| 工程 | `applicationId` 固定；`minSdk=26`、`targetSdk=36`、`compileSdk=36` | 不改变包名、最低版本、签名和现有构建方式 |
| 应用级所有者 | `MpdServerRemoteApplication` 已持有唯一 `AppRuntime` | MediaSession/Service 必须复用该 Runtime，不创建新 Runtime |
| 权威状态 | `AppRuntime.snapshots` / `SnapshotStore` | 只能订阅 canonical snapshot，不另存 current/playback 状态 |
| 实时连接 | `AppRuntime.realtimeController` 是唯一 WebSocket owner | 后台会话不得再建立连接或独立轮询 |
| 写操作 | `AppRuntime.mutations` 是唯一 `MutationCoordinator` | 系统动作沿用现有 Player action 语义并进入同一协调器 |
| 播放事实 | `PlayerFacts`、`PlayerPresentation` 已校验 fresh、actual freshness、matches_current、bound occurrence、sync status 和实际 PLAYING/PAUSED | MediaSession 不得自行从业务 `playback.state` 推断播放状态 |
| 控件能力 | `PlayerPresentation.controls` 已按服务端观察、Queue current 及 target 推导 pause/resume/previous/next/seek | 系统可用动作以同一能力投影为准，不复制判断规则 |
| 写操作入口 | Runtime `PlayerAction`、`PlayerControlIdentity`、`PlayerCommandGateway` 已由 AppRuntime 单例持有；PlayerActions 保留 typealias 并委托同一入口。Gateway 与 AppRuntime.changeOrigin 共用 monitor，切换窗口拒绝命令 | B1/B3 系统 callback 继续复用同一 gateway；不得由 Service 重写 URL、payload 或 target 规则 |
| 封面资源 | `RuntimeReadApi.getArtwork()` 和 `ResourceMemoryCache` 已提供有身份约束的原图 bytes | 后台媒体元数据复用同一资源入口；不可依赖 Compose 页面存活或另发无身份约束请求 |
| 现有缺口 | 当前无 MediaSession、MediaStyle 通知或媒体 Service；`PlayerActions` 实例由 `AppNavHost` 的 Composition `remember` 持有 | 新适配层必须具备应用进程级生命周期，不得把 Activity/Composable 当后台 owner |
| 实机基线 | 已连接 SM-G986N，Android 13 / API 33；原生 MediaSession/MediaStyle 探针已通过 | Task 7 系统媒体功能验收基线为 Android 13+；API 26–32 不作保证。后续若引入后台服务/通知专项能力，再验证对应 Android 14+ 平台限制。 |

## 3. 合同矩阵

| ID | 需求 / 冻结合同 | 权威来源与现有证据 | 机器验证 | 人工验证 |
|---|---|---|---|---|
| T7-01 | 系统媒体会话是远端状态适配器。它和 Compose UI 共用一个 AppRuntime、SnapshotStore、RealtimeController、MutationCoordinator；不拥有业务状态、网络连接或重试日志。 | 产品规格 §§11.1、12；架构规格 §§2、4–6；`MpdServerRemoteApplication`、`AppRuntime` | 架构/依赖检查；确保只创建一个 session owner 和一个 Runtime；Activity 重建不重复创建 session | 前后台切换时仍显示同一播放事实；页面切换不产生第二条 WS |
| T7-02 | MediaSession 元数据只映射 `PlayerPresentation` 已确认的 current occurrence。歌曲必须匹配当前 `queue_item_id` / `song_id` 绑定；提供可用的标题、艺术家、专辑和封面。字段缺失不伪造曲目或封面。 | 产品规格 §11.1；Web 行为合同 §3；`PlayerFacts`、`PlayerPresentation` | 覆盖有效元数据、缺字段、无封面、song/occurrence 不匹配、重复歌曲 occurrence | 系统控件显示的曲目、封面与 App 当前曲目一致，快速切歌不串图 |
| T7-03 | 新建/恢复会话的发布条件：有唯一 current occurrence；Snapshot fresh、连接 CONNECTED、snapshot generation 当前；playback 与 actual observation 均 fresh；`matches_current=true`、bound occurrence 匹配、sync status 为 `CONFIRMED` 或 `NO_CANDIDATES`；actual state 为 PLAYING 或 PAUSED。 | 产品规格 §11.2；Web 行为合同 §2–3；`PlayerPresentation.from()` | 对每项条件逐一构造失败 fixture，断言未确认状态不创建可操作媒体会话 | 服务端状态不确定时，不出现伪装为正在播放的系统控件 |
| T7-04 | 可用系统动作仅为上一首、播放/暂停、下一首。动作能力映射 `PlayerPresentation.controls`：上一首/下一首按 Queue 中 current 两侧 occurrence 是否存在；播放中只开放 Pause，暂停中只开放 Resume；Resume 保留服务端 `control_target`。 | 产品规格 §11.1；Web 行为合同 §5；`PlayerActions`、`PlayerPresentation` | 每种状态与 Queue 边界测试；断言 action flags 与 App 控件能力一致；Resume payload 包含原 target | 锁屏控件动作和 App 控件对同一曲目/队列产生一致结果 |
| T7-05 | 系统动作沿用统一命令入口与 MutationCoordinator。任何 player action 在 PENDING/SYNCING/UNKNOWN 锁定期间不得再次创建新 intent；重复系统回调不得导致重复 POST。不得使用新 key 自动重放 unknown。 | Web 行为合同 §4；架构规格 §6；`PlayerActions.isLocked()`、`MutationCoordinator.submit()` | 并发/重复 callback 测试；同一动作被连续触发只允许一个 intent；UNKNOWN 不能自动换 key 重发 | 快速连续点击系统控件只产生一次操作；操作未知时不误报成功 |
| T7-06 | 已发布会话在同 origin 断线或 snapshot stale/unknown 时，可保留最后一次确认的曲目元数据；明确进入不可操作状态，动作 flags 清零。不得将最后已知播放状态包装成新的确认状态。新安装/新进程在获得 fresh snapshot 前不得从持久化元数据恢复为当前曲目。 | 产品规格 §11.2；Web 行为合同 §§2–3 | fresh→stale、disconnect、unknown、重连等状态迁移测试；新进程 stale 初态不发布会话 | 断网期间元数据可以保留，控制不能触发写请求；恢复后以新 snapshot 更新 |
| T7-07 | 当前无唯一 current occurrence，或 canonical snapshot 的 `explicit_stop_generation` 前进并确认 Stop 后，撤下会话与媒体通知。不能仅因 `playback.state=STOPPED`、actual STOPPED 或清空 pending Queue 就推断用户执行了 Stop。 | 产品规格 §11.2、§12；Web 行为合同 §5；`FullStateSnapshot.explicitStopGeneration` | current 缺失、explicit stop generation 增长、仅 STOPPED、仅清空 pending Queue 四类对照测试 | Stop/无 current 后媒体卡片撤下；清空待播不错误撤下仍在播放的 current |
| T7-08 | 后台服务只承载系统媒体会话、媒体通知和命令回调。服务生命周期必须与 AppRuntime 协调；不因 Activity 销毁而失效，不启动第二个 Runtime。只有平台确实要求时才使用前台服务，并声明经 targetSdk 验证的最窄正确 service type/权限。 | 产品规格 §§4.1、11.1、13.2；Android targetSdk 36 | Manifest 合同检查、Service lifecycle instrumentation、API 26/33/36 覆盖；验证 start/stop 幂等及权限错误 | Home/锁屏/后台切换、屏幕关闭、Service 重建与系统回收行为 |
| T7-09 | 通知渠道与权限遵守 API 26+、API 33+ 的系统规则。拒绝/关闭通知或后台能力受限时，不能伪称完整可用；应用内解释降级原因。不要在应用启动时无上下文弹出权限请求。 | 产品规格 §11.2、§13.2 | 通知许可 granted/denied、渠道关闭、平台不允许前台服务等状态测试 | 真机分别测试允许、拒绝、系统设置关闭后的系统控件和 App 内状态 |
| T7-10 | 封面使用 `RuntimeReadApi.getArtwork()` 和 `ResourceMemoryCache`，以 origin/generation/epoch/library revision/song/artwork 身份保护迟到结果。文本元数据先发布，封面可异步补齐；缺图/失败使用应用图标，不沿用上一首封面。 | Web 行为合同 §§3、7；架构规格 §6.1；`RuntimeReadApi`、`ResourceMemoryCache` | 切歌、origin 变化、epoch/revision 变化、请求迟到、缺图及非法图片测试 | 快速切歌时锁屏图片不闪回前一首；无封面时显示稳定占位 |
| T7-11 | 进程死亡/Service 重建后，先恢复已配置 origin，再启动/复用唯一 Runtime 并取得新完整权威 snapshot；只在新 snapshot 满足 T7-03 后恢复会话。不得恢复持久化播放快照或自动重放上次未确认操作。 | 产品规格 §11.1、§12；Web 行为合同 §2；Application DataStore origin 恢复 | 进程/Service 重建测试；无快照/旧 epoch/旧 origin 下不恢复 action；fresh snapshot 到达后更新 session | 系统回收后重入、应用重新启动、网络恢复后的会话恢复；不清数据、不卸载 |
| T7-12 | 系统媒体卡片不增加 seek、Stop、Queue、Shuffle、Repeat、音量、DAC 或本地播放功能。耳机/硬件按键不是独立验收目标；系统通过 MediaSession 标准回调支持的行为仍须落到既有三类动作。 | 产品规格 §§4.3、11.1；主计划 Task 7 | 检查 published action flags 和 notification actions，不出现越界动作 | 检查系统界面不展示未经授权的控制 |
| T7-13 | session、notification、service 均为单例且可幂等更新/撤销；Activity/Service 生命周期交错、重复 onStartCommand、重复 snapshot emission 不得堆积通知或创建多个 MediaSession。 | 产品规格 §§11–12；架构规格 §4 | 多次启动/停止、Activity 重建、重复 snapshot 的 instrumentation；检查 active session / notification 数量 | 多次进入退出 App、切后台和重开后仅保留一个系统媒体卡片 |
| T7-14 | 新增系统媒体能力不得改变现有播放、Queue、歌词、Home/Library/Search、设置、地址持久化、统一签名和覆盖安装行为。 | 主计划 Task 4/5/6；Task 0 签名合同；Task 7 整合回归 | 全量 unit/UI/instrumentation、Lint、clean build；APK 包名/签名；Task 4/5/6 回归 | `adb install -r` 覆盖安装，保留 MS origin、主题和状态环参数；再次验证服务连接 |

## 4. 关键语义细化

### 4.1 播放状态与通知状态

- 系统会话只观察 `PlayerPresentation`，不可直接以 `playback.state`、Queue position 或上一次点击的动作作为实际播放事实。
- 首次会话发布必须满足 T7-03。`PlayerPresentation` 已确认 PLAYING/PAUSED 时才发布对应状态。
- 已有会话在同 origin stale/disconnect 时按 T7-06 保留最后已知元数据，但所有系统动作不可用；状态必须明确标记为 stale/unknown。不可通过把状态改为刚刚确认的 PLAYING/PAUSED 来规避系统 UI 生命周期问题。
- current 缺失或明确 Stop generation 前进时按 T7-07 立即撤销媒体会话和通知。实际状态 STOPPED 本身不是用户 Stop 意图。
- server origin 改变时，旧 origin 元数据不可显示成新 server 的当前曲目；先撤销旧媒体身份，等待新 origin 的完整 fresh snapshot。

### 4.2 系统动作映射

| 系统动作 | 触发条件 | 既有命令 | 额外约束 |
|---|---|---|---|
| Previous | `PlayerPresentation.controls.previous=true` | `PlayerAction.PREVIOUS` / `POST /api/playback/previous` | 不可按 song id 自行寻找上一首 |
| Play/Pause | 已确认 PLAYING 且 pause=true，或已确认 PAUSED 且 resume=true | `PlayerAction.PAUSE` 或 `PlayerAction.RESUME` | Resume 必须使用当前 `control_target`；不得只看业务播放状态 |
| Next | `PlayerPresentation.controls.next=true` | `PlayerAction.NEXT` / `POST /api/playback/next` | 不可在客户端重排 Queue 或创建新 occurrence |

MediaSession 只发布当前真实可用的 action flags。stale、unknown、未绑定、target 缺失、无 current、写操作锁定或 origin 切换时不得执行命令。服务端仍会重新校验 target；客户端能力检查不是服务端安全保证。

### 4.3 进程与服务生命周期

- `MpdServerRemoteApplication` 是 AppRuntime 的唯一创建者。MediaSession adapter 和服务必须获得 Application 中的同一 Runtime 引用。
- 现有 `PlayerActions` 的实例由 `AppNavHost` Composition 创建，不能直接让后台 Service 依赖该 Composition 实例。实施时应抽取 app-scoped player command gateway，或以等价方式保证 UI 与系统动作共享同一能力判定、同一 intent 锁和同一 MutationCoordinator。不得为了复用而把 seek 草稿等 UI 局部状态提升成第二份业务状态。
- 服务在 Activity 离开前台后继续持有系统媒体适配能力；Activity 重建不创建第二个 session。Service 重建可以重新绑定 Application Runtime，但不创建网络 owner。
- 平台若要求前台服务，必须在 Batch 0 证明其必要性并验证 targetSdk 36 对 service type/permission 的要求。不得为了让通知常驻而声明不符合远端控制实际用途的类型。若系统媒体会话可在无 FGS 下满足已冻结行为，应优先不添加不必要的前台服务。
- 系统回收导致进程重建后，重新读取 DataStore 中 origin，等待 origin 恢复与 fresh snapshot；没有新快照前不得激活媒体会话。强制停止应用不保证系统重启服务，不能承诺绕过 Android 的 force-stop 语义。

### 4.4 通知、权限与降级

- API 26+ 必须创建稳定 ID 的 NotificationChannel；媒体通知无声音/振动，不与应用内活动通知混为一类。
- API 33+ 的通知权限和 targetSdk 36 的后台服务限制须在真实平台版本上验证。运行时权限拒绝不应导致应用崩溃、循环弹窗或伪造通知成功。
- 权限/渠道受限时，记录明确的 `MediaControlAvailability` 展示状态，并在现有「设置 → 状态」页面说明原因及打开系统通知设置的入口；不得新增第五个一级导航。
- Notification 是否可见与 MediaSession 是否能被系统识别需分别检测，不能用 `notify()` 未抛异常推断锁屏控件已显示。
- 厂商锁屏布局、图标色彩、是否显示完整封面受系统设置影响；验收行为与动作正确性，不要求跨厂商截图像素一致。

### 4.5 封面

- 以 confirmed current 的 SongDto/artwork reference 为唯一来源；加载时记录 origin、generation、epoch、library revision、songId、artworkId、current occurrence 身份。
- 调用现有 `runtime.reads.getArtwork(songId)`，复用 `ResourceMemoryCache` 中的缓存、single-flight、容量和身份失效规则；不得新增第二个封面 HTTP client。
- 图片解码在后台线程执行；每次回写前再次验证请求身份仍与当前媒体身份一致。封面读取失败不阻塞标题/艺术家更新，使用 app icon，不保留上一首图像。
- 不将 Bitmap、曲目或播放快照写入 DataStore/文件作为重启恢复事实。

## 5. 非目标与边界

- 不实现本地音频播放、解码、音频焦点接管、Media3/ExoPlayer Player、MPD TCP 连接或第二个播放引擎。
- 不新增 API、WebSocket、HTTP repository、Room/SQLite、跨进程 mutation journal 或后台状态轮询。
- 不为系统通知增加 seek、Stop、Queue 浏览/编辑、Shuffle、Repeat、音量、DAC 输出配置或其他 App 页面没有的业务能力。
- 不把普通通知权限等同于系统 MediaSession 是否存在；两者按平台实际行为分别测试。
- 不把实际 STOPPED 等价于显式 Stop，不把清空待播队列等价于停止当前播放。
- 不声称应用可以阻止 Android 杀进程、绕过用户 force-stop 或忽略厂商后台限制。

## 6. 退出门

仅在下列条件全部满足后，主计划 Task 7 才可勾选完成：

1. T7-01 至 T7-14 的合同均有自动化测试或明确的设备验收证据；不得以构建通过代替行为验收。
2. MediaSession 元数据、播放状态和动作能力始终来自同一 canonical snapshot 与 `PlayerPresentation`；所有命令经过唯一 Runtime mutation owner。
3. 停止、无 current、stale、断线、origin 切换、进程重建和重复 callback 场景均符合合同。
4. 通知权限、通知渠道、服务类型和 targetSdk 36 行为已有 API 级验证记录；没有不必要或不匹配的 foreground service 声明。
5. clean build、unit tests、Lint、connected instrumentation 通过；Task 4/5/6 回归无新增失败。
6. 在真实 Android 设备验证后台、锁屏、通知栏、暂停/恢复、上一首/下一首、断网恢复、通知权限允许/拒绝和服务/进程重建。
7. 使用同 package 与统一签名执行 `adb install -r` 覆盖安装；保存的 MS origin、主题、状态环参数均保留。不得卸载、清除数据或更换签名。
8. 记录 API 版本、设备型号、通知权限状态、服务策略、安装前后数据核对和未覆盖限制。模拟器不能代替真实锁屏验收。

## 7. 当前阻塞与后续阶段关系

- Task 2 的 Runtime/MutationCoordinator 是必要前置；本分支起点已有这些基础，但 Task 7 开始实现前仍须按主计划核对其退出门状态。
- 用户于 2026-11-01 确认 Task 3–6 已完成并通过实际设备验收，主计划对应 checkbox 未同步更新；本次按用户确认继续执行 Task 7，不以这些过期 checkbox 阻塞 B0。
- Task 5/6 的页面变更不是新增媒体会话权威；Task 7 应以当前 main 起点实现，并在集成批次验证这些页面无回归。


## 8. Batch 0 平台决策与实测记录（2026-11-01）

| 项目 | 当前决策 / evidence | 限制 |
|---|---|---|
| MediaSession | 使用平台 `android.media.session.MediaSession`；API 33 真机注册 active session，`dumpsys media_session` 显示 SystemUI controller 与 canonical test metadata。 | API 26/36 runtime 尚未覆盖。 |
| MediaStyle | 使用平台 `android.app.Notification.MediaStyle` 并绑定同一平台 `MediaSession.Token`；API 33 真机 instrumentation 验证 token 附加与 Notification 发布。此路径不使用 `NotificationCompat.MediaStyle`，不混用 Compat token。 | 厂商锁屏卡片视觉与媒体动作 UI 留在后续批次验收。 |
| POST_NOTIFICATIONS | API 33 / targetSdk 36 / 权限 denied（`checkSelfPermission=-1`，`areNotificationsEnabled=false`）时，MediaSession 仍被 SystemUI 注册，media-style notification 仍出现在 NotificationManager active list。 | granted、用户关闭媒体 channel 的组合尚未实测；不能推广成所有厂商视觉行为一致。 |
| Channel | 探针 channel 使用 LOW importance（2）、无 sound、无 vibration，结束时删除探针 channel 和通知。 | 正式稳定 channel ID 在 B2 冻结。 |
| Foreground Service | B0 不新增 Service、FGS permission 或 service type。当前 App 是 NAS 远端遥控器，不执行本地解码/音频输出；API 33 证明 Session/MediaStyle 可在无 FGS 下创建和发布。 | 进程回收/长期后台恢复由 B3 决策；API 36 FGS 规则未有 runtime 证据，不声明不匹配的 `mediaPlayback` 或未经证明的 `connectedDevice`。 |
| SDK 环境 | App `minSdk=26`、`targetSdk=36`；API 36 android.jar 静态确认 MediaSession、Notification.MediaStyle 与 FGS constants。 | 设备仅有 SM-G986N / API 33；无 API 26 或 API 36 AVD/runtime。 |

B0 已实现 Runtime command gateway；API 26/36 runtime matrix 是明确的未覆盖项。按用户接受的 Android 13+ 范围，API 26–32 不属 Task 7 媒体功能验收目标，API 34+ 专项后台限制留到对应后续批次验证。不得将 API 33 的单机结果描述为 API 26–36 全平台通过。详见 [Batch 0 verification report](../reports/2026-11-01-task-7-batch0-verification.md)。


### 8.1 Batch 0 Runtime implementation status

- 新增 Runtime canonical `PlayerAction` 和共享 `PlayerControlIdentity`，应用内 UI 与后续系统媒体 callback 使用同一动作语义和控制目标身份。
- `AppRuntime.playerCommands` 是 app-scoped lazy Gateway，复用现有 SnapshotStore、MutationCoordinator、RealtimeController；不创建网络 owner。
- `PlayerActions` 普通播放/seek 命令统一委托 Gateway，seek draft/preview 保留 UI 局部状态。
- AppRuntime origin transition 与 Gateway 使用同一个 monitor 原子化；origin 更新期间拒绝 dispatch / UNKNOWN retry。
- Gateway tests cover pause/resume/previous/next/seek eligibility、opaque Resume target、stale/UNKNOWN、PENDING/SYNCING、same intent retry、identity/generation changes、queue boundaries、two callers shared lock、origin-transition critical section and concurrency.
- 最终机器验证：80 suites / 445 tests / 0 failures / 0 errors / 0 skipped；Lint 16 warnings / 4 hints / 0 errors；Debug + instrumentation APK assembled.
- API 33 SM-G986N 真机原生 MediaSession/MediaStyle probe passed；API 26/36 runtime 未执行的事实保留记录。按用户接受的 Android 13+ 系统媒体验收范围，API 26–32 不作为 Task 7 媒体功能退出门；API 34+ 通知/后台限制留到相应后续批次验证。
- 最终 Debug APK 已 `adb install -r` 覆盖安装，DataStore preference SHA-256 与 firstInstallTime 保持不变。


## 9. 用户确认的平台验收范围（2026-11-01）

用户明确接受 Task 7 系统媒体功能以 Android 13（API 33）及以上为可用范围，API 33 真机作为最低验收基线。由此调整阶段门：

- Batch 0 的 API 26 runtime 覆盖不再阻塞 Batch 1；Android 12 及以下不纳入 Task 7 系统媒体功能保证范围。
- Batch 1 使用的原生 MediaSession/PlaybackState API 已在 API 33 真机探针验证；该批不增加通知、Service、FGS permission 或新的平台依赖。
- API 34+ 没有在本设备逐版本实测。涉及通知权限和后台执行限制的能力，只在对应后续 Batch 实施时按当时真实使用的 API/Service 类型验证；不得把 API 33 结果描述成所有厂商界面和后台策略完全一致。
- 应用现有 minSdk=26 保持不变；本次只调整 Task 7 系统媒体功能的验收承诺，不缩小其他应用功能的安装兼容范围。
- API 33 已验证的通知权限 denied 探针结果保留；granted/channel-disabled 组合属于 Batch 2，不影响本批 MediaSession projection。


Batch 1 验证记录见 [Task 7 Batch 1 verification report](../reports/2026-11-01-task-7-batch1-verification.md)。
