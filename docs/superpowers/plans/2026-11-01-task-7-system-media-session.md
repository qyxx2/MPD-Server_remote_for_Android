# Task 7 Implementation Plan：系统媒体会话、通知与锁屏控制

> **For agentic workers:** 使用 Superpowers executing-plans / SDD 工作流。逐 Batch 测试先行、实现、独立验证、本地提交；不得调用其他编程代理。本文是实施计划，不代表已实施。

**Goal:** 在不创建本地 Player、不增加业务状态副本、不增加第二条网络连接的前提下，为 Android 系统提供 NAS 远端播放的 MediaSession、媒体通知和锁屏/通知栏控制。

**Architecture:** 单一 `MpdServerRemoteApplication` → 单一 `AppRuntime` → `SnapshotStore` / `RealtimeController` / `MutationCoordinator`。UI 与媒体服务共享一个 app-scoped player command gateway。MediaSession/Notification/Service 只投影 canonical playback state、metadata 和现有 action capability。

**Source contract:** [Task 7 合同矩阵](../specs/2026-11-01-task-7-contract-matrix.md)。权威上位合同见 Android 产品规格 §11–13、Web 行为合同 §§2–5、架构合同 §§2、4–6。主计划 Task 7 决定阶段依赖和退出门。

## 开始门与人工冻结判断

- 不需要新增人工产品决策。动作集合、元数据范围、唯一状态来源、stale/Stop、权限受限降级和进程重建语义均已由上位合同确定。
- **开始实现的前置门：** 用户于 2026-11-01 明确确认 Task 0–6 已完成并通过实机验收，主计划中未勾选的复选框只是记录未同步，不构成阻塞。本次按该确认直接开始 Batch 0；后续文档以本次授权事实为准，不再因旧 checkbox 暂停实现。
- Batch 0 采用平台原生 MediaSession + Notification.MediaStyle；API 33 真机探针已证明原生 Session/MediaStyle token 路径可行。用户于 2026-11-01 明确接受 Task 7 系统媒体功能的保证范围为 Android 13（API 33）及以上，故 API 26–32 runtime 覆盖不再阻塞 B1；应用 minSdk=26 不变。B1 不新增通知、Service、FGS 权限或依赖；通知权限与 Android 14+ 后台限制只在后续批次实际接入对应能力时验证。不得引入 Media3/ExoPlayer 播放器。
- 当前实机：SM-G986N / Android 13 / API 33；本地 SDK 含 android-36。API 36 相关前台服务与权限约束需在 API 36 环境单独覆盖。真机锁屏验收仍以 SM-G986N 实测为准。

## 跨 Batch 设计约束

1. **唯一状态源：** MediaSession metadata/state/actions 均投影自 `PlayerPresentation.from(runtime.snapshots.state.value)`；不读页面缓存、Queue 本地缓存或上一次通知作为 canonical state。
2. **唯一写入口：** 系统 callback 必须进入 app-scoped player command gateway，再复用 `runtime.mutations`。不得在 Service 中另写 HTTP 请求、target payload 或 Idempotency-Key 逻辑。
3. **动作集合：** Previous / Play-Pause / Next；不增加 seek、Stop、Queue、Shuffle、Repeat、音量或 DAC 操作。
4. **生命周期：** Application 拥有 Runtime；MediaSession 与 Service 不得在 Activity 重建时重复实例化。只有平台实测要求时才使用前台服务，并使用符合实际用途且经 targetSdk 验证的最窄 service type。
5. ** stale 与撤销：** 同 origin stale/disconnect 可保留最后确认的 metadata，但 actions=0；新 origin、无 current 或 explicit Stop generation 前进时撤销旧会话。actual STOPPED 和清空 pending Queue 不是显式 Stop 证据。
6. **资源身份：** artwork 通过 `runtime.reads.getArtwork()`，复用 AppRuntime ResourceMemoryCache；回写需校验 origin/generation/epoch/revision/song/occurrence 身份。
7. **持久化：** 不持久化 canonical playback、MediaSession metadata 或待确认 mutation；进程重建后先恢复 origin，再取得 fresh snapshot。
8. **权限降级：** app 启动不弹无上下文权限框；拒绝或系统限制时不反复提示，在现有「设置 → 状态」中说明限制与系统设置入口。
9. **安装与数据：** 只允许 `adb install -r` 覆盖安装；不卸载、不 clear data，保留当前 origin、主题和状态环参数。

## Batch 0 — Runtime player command gateway 与平台可行性冻结

**目标：** 将系统媒体动作与应用内播放动作收敛到一个可测试的 Runtime 级命令入口；完成 MediaSession/通知/Service 平台能力验证，先确定真实实现方式。

**候选文件：**
- Create `app/src/main/java/io/github/qyxx2/mpdserver/remote/runtime/PlayerAction.kt`：将动作枚举放到 Runtime，供应用内 UI 与后续系统 callback 共用；UI 包保留 typealias 兼容既有调用点。
- Create `app/src/main/java/io/github/qyxx2/mpdserver/remote/runtime/PlayerControlIdentity.kt`：UI 和 Runtime gateway 共用同一控制身份。
- Modify `AppRuntime`：origin transition 与 player command submission 共用同步 monitor，封闭跨 origin 的旧快照写入窗口。
- Create `app/src/main/java/io/github/qyxx2/mpdserver/remote/runtime/PlayerControlIdentity.kt`：共享控制目标/业务身份 scope，避免 UI 与 Gateway 各自解析一套 identity。
- Create `app/src/main/java/io/github/qyxx2/mpdserver/remote/runtime/PlayerCommandGateway.kt`：只负责从 canonical snapshot 重新判定 action capability、阻止重复 player intent，并将现有 `PlayerAction` 交给 `MutationCoordinator`。
- Modify `ui/player/PlayerActions.kt`：保留 seek draft、preview 等 UI 瞬态行为；普通播放命令委托给共享 gateway，不能产生第二套 target / payload / 写请求规则。
- Add `app/src/test/java/io/github/qyxx2/mpdserver/remote/runtime/PlayerCommandGatewayTest.kt`。
- Add platform spike/verification notes under `.superpowers/sdd/2026-11-01-task-7-system-media-session/`，记录 API 26/33/36 的 API 可用性、通知权限、service type、Manifest 声明和 real-device observations。

### Task 7.0.1 — Capture baseline and prove shared action path

- [x] 确认当前 HEAD/工作树；识别并保留已存在的 Task 7 规格/计划未提交变更，不覆盖无关工作。
- [x] 为 gateway 写先行测试：pause/resume/previous/next 分别映射到已有 PlayerActions 语义；capability 来自 `PlayerPresentation.controls`。RED：Gateway 类型尚不存在时编译失败；实现后转 GREEN。
- [x] 覆盖 RESUME target、stale/unknown、connection generation/origin scope 变化、current occurrence/control target 变化、无前/后曲目、PENDING/SYNCING/UNKNOWN 与重复 callback。
- [x] 验证两个调用方并发触发相同类型动作时只有一个 player intent；UNKNOWN 仅显式复用原 intent，自动重试不创建新 key。
- [x] 执行 gateway RED 与 `PlayerActionsTest` / `MutationCoordinatorTest` 回归；focused tests 最终通过。
- [x] 实现 AppRuntime app-scoped gateway；UI 的 pause/resume/previous/next/seek 命令经过同一入口，seek draft/preview 仍保留在 PlayerActions UI 瞬态层。
- [x] 运行 Gateway、PlayerActions、MutationCoordinator focused tests；共享锁、能力映射及 UNKNOWN retry 语义通过。

### Task 7.0.2 — Freeze platform integration path by evidence

- [x] 核查 Gradle catalog、Manifest、compileSdk/targetSdk 36 与现有 AndroidX 依赖；选择原生平台 `MediaSession` + `Notification.MediaStyle`，不新增依赖或 Manifest 权限。
- [x] 在 SM-G986N / API 33 实测原生 `MediaSession` token 与原生 `Notification.MediaStyle` 兼容：系统注册 Session、SystemUI 成为 controller，且构造后的 media notification 可发布。选择原生 Style 而非 Compat，避免混用不同 token 类型。
- [x] API 33 已实测通知权限 denied（`POST_NOTIFICATIONS=-1`、`areNotificationsEnabled=false`）时 media-style notification 仍进入 activeNotifications、Session 仍被 SystemUI 控制。
- [ ] API 33 通知 permission granted 与 user-disabled channel 两种组合尚未覆盖。
- [ ] API 26 minSdk runtime 上尚未实测原生 MediaSession/MediaStyle 最低版本行为；当前环境没有 API 26 runtime/AVD。
- [ ] API 36 目前只有本地 `android-36.jar` 静态 API 检查（MediaSession/MediaStyle/FGS 常量存在）；没有 API 36 runtime/AVD，尚未实测 FGS 启动限制。当前不声明 FGS 权限或 service type；NAS 播放不等于本 App 本地播放。
- [ ] AppRuntime 单一 Session owner 的实际创建/释放在 B1 实现；后台 Service 的创建/停止/sticky/recreate 与 force-stop 恢复属于 B3 生命周期实现，须在对应批次/API 级回归中验证。
- [x] 更新本计划与合同矩阵的平台决策记录：原生 MediaSession/MediaStyle、无新增依赖/权限/Service 声明；API 26/36 runtime 覆盖缺口单独列出。未改变产品范围。

**Batch 0 退出条件：** shared gateway 单测通过；已有 PlayerActions 行为无回归；API 33 原生 MediaSession/MediaStyle 路径有真机证据；系统媒体功能的最低保证版本为 Android 13。API 26–32 不纳入 Task 7 媒体功能验收，应用 minSdk 仍保持 26；API 34+ 的通知/后台服务限制在后续实际接入对应能力时验证。没有新增 Runtime、WebSocket 或 MutationCoordinator。

**B0 实际结果（2026-11-01）：** Runtime Gateway、UI 命令收敛、共享 control identity、origin transition 原子锁和对应回归测试已实现并通过 focused/full-unit/build/Lint 验证（80 suites / 445 tests）；API 33 原生 Session/MediaStyle 已在真机验证。用户接受 Android 13+ 作为 Task 7 媒体功能范围后，API 26–32 runtime 缺失不再阻塞 B1；API 34+ 通知/后台限制按后续批次实际引入的能力验证。没有声称未测版本已通过。详细 evidence 见 [Batch 0 verification report](../reports/2026-11-01-task-7-batch0-verification.md)。

**提交建议：** `refactor(playback): share player commands with system controls`

## Batch 1 — MediaSession 与 canonical playback projection

**目标：** 创建单一 app-scoped MediaSession owner，并把实时快照映射为媒体元数据、播放状态及准确的动作 flags；先不加入复杂 Service 恢复行为。

**候选文件：**
- Create `media/RemoteMediaSessionController.kt`：拥有 MediaSession、订阅 Runtime snapshot/activity state、调用 projection 并幂等更新/释放会话。
- Create `media/MediaSessionProjection.kt`：纯函数/模型，将 `SnapshotStoreState`、`PlayerPresentation` 与 last-known state 映射为 metadata、playback state、available actions。
- Create `media/MediaMetadataMapper.kt`：title/artist/album/duration/artwork reference 的规范化，不补造 Song/Queue identity。
- Modify `MpdServerRemoteApplication.kt`：创建或提供单例 controller，使用已有 Runtime。
- Add `app/src/test/.../media/MediaSessionProjectionTest.kt`、`MediaMetadataMapperTest.kt`。

### Task 7.1.1 — Prove projection rules first

- [x] 写 RED 测试覆盖 fresh PLAYING、fresh PAUSED、actual STOPPED、missing current、ambiguous current、observation stale、actual stale、sync unconfirmed、bound mismatch、target missing、origin/generation change。
- [x] 写 metadata fixture：title/artist/album 完整、部分缺失、空串、非法 DTO、重复歌曲不同 occurrence。
- [x] 写 actions fixture：只有 current、current 前后均有项、仅 previous、仅 next、pause disabled/resume disabled、player action pending。
- [x] 写 stale transition：已发布 session → 同 origin stale/disconnect 保留最后确认 metadata、actions 清零；新 session 首次 stale 不发布。
- [x] 写 Stop 对照：no current / explicit_stop_generation 前进撤销；仅 `playback.state=STOPPED` 或仅 Queue pending clear 不误撤销。

### Task 7.1.2 — Implement app-scoped MediaSession projection

- [x] 实现 `MediaSessionProjection`，只消费共享 `PlayerPresentation`/canonical snapshot，不引入业务规则副本。
- [x] 实现 `RemoteMediaSessionController`，保证创建/激活/更新/释放均幂等；Activity 重建和重复 snapshot 不产生第二个 Session。
- [x] 会话 metadata 至少包括 title、artist、album（若存在）、duration（若有效）；current song 不可用时不伪造身份。
- [x] PLAYING/PAUSED state 仅映射真实已确认 actual state；stale 保留最后确认 metadata 且动作清零，不制造新的确认状态。
- [x] available actions 仅发布 Previous、Play/Pause、Next，并逐项匹配 `PlayerPresentation.controls`。
- [x] 对 `server origin` 变化、无 current、explicit Stop generation 变化撤销会话。
- [x] 在 Application 中确保单一 owner，提供受控的 `close()`/release，不让 Activity 负责关闭进程级会话。

**Batch 1 退出条件：** projection 单测通过；active session 数量稳定为 0 或 1；state/actions 与 App Player 一致；stale/Stop/origin 语义正确；Application 持有唯一 owner；API 33 真机 session 发布通过；尚未接入后台 Service/MediaStyle 通知/回调时不声称锁屏控制端到端验收完成。

**提交建议：** `feat(media): publish authoritative playback session`

## Batch 2 — MediaStyle 通知、封面与通知权限降级

**目标：** 将 MediaSession 暴露为符合系统规则的媒体通知，复用 Runtime artwork cache；实现 API 级通知渠道与权限状态。

**候选文件：**
- Create `media/MediaNotificationController.kt` / `MediaNotificationFactory.kt`。
- Create `media/MediaControlAvailability.kt`：记录 notification permission/channel/platform restrictions 的展示状态，不保存业务播放事实。
- Modify `app/src/main/AndroidManifest.xml`、`app/build.gradle.kts` 和 `gradle/libs.versions.toml`（仅 Batch 0 冻结所需声明/依赖）。
- Modify `ui/settings/StatusSettingsScreen.kt` / `ActivityStatusContent.kt` 或相邻纯 UI 组件：解释系统媒体控制降级原因并提供系统设置入口；不新增一级导航。
- Add notification factory/controller unit/instrumentation tests。

### Task 7.2.1 — Test notification channel and metadata publication

- [x] 在 API 33 真机验证 NotificationChannel 创建、稳定 ID、低重要性、无声音/振动和重复创建幂等；不将 API 33 结果冒称 API 26 runtime 覆盖。
- [x] 验证 MediaStyle 与同一原生 MediaSession token 绑定；系统动作能力沿用该 Session 的 PlaybackState action flags，不额外创建第二套 Notification.Action。
- [x] 验证 notification tap 使用 PackageManager.getLaunchIntentForPackage() 的现有应用入口；不增加播放器 deeplink 或新导航 route。
- [x] 验证文本 metadata 先发布；封面异步补齐，未取得封面时使用应用图标资源。
- [x] 使用 runtime.reads.getArtwork(songId)；迟到回写校验 origin、connection/snapshot generation、epoch、library revision、explicit Stop generation、song、queue occurrence 与 artwork ID。
- [x] 复用 RuntimeReadApi / ResourceMemoryCache 对缺图、HTTP 错误、无效 MIME/bytes 和签名校验；identity 变化先清空旧 Bitmap，封面失败不阻塞文本 metadata。
- [x] 在 API 33 真机分别验证 POST_NOTIFICATIONS denied 与 granted 的平台状态读取；denied 时仍观测到 active MediaSession/SystemUI controller 和 NotificationManager active MediaStyle record。
- [x] 覆盖 channel importance=NONE 的状态投影；ensureChannel() 对已有 channel 原样返回、不会更换 ID 或重建绕过用户设置；设备测试验证 channel 重复初始化幂等。

### Task 7.2.2 — Implement MediaStyle notification and degradation

- [x] 仅增加 POST_NOTIFICATIONS 声明，不增加依赖、不声明 Service/FGS 权限或类型。
- [x] 创建稳定媒体通知渠道，低重要性、无声音/振动；已有用户 channel 配置不被覆盖。
- [x] MediaStyle 绑定当前原生 Session token；三类 action flags 继续由 B1 的 canonical Session PlaybackState 投影。
- [x] artwork 异步读取/解码，并在 Session/通知更新前重新验证完整媒体身份；文本 metadata 不等待封面。
- [x] 区分运行时通知权限、应用通知开关和 channel importance；限制状态不崩溃、不请求权限、不循环重试，并输出 MediaControlAvailability。
- [x] 在「设置 → 状态」增加系统通知权限/渠道状态及 Android channel/app notification settings 入口；页面说明不依赖跳转成功。
- [x] MediaSession projection 被撤销或 token 不可用时撤下媒体通知；通知受限时不伪造 Session/action 能力。

**Batch 2 退出条件：** 通知与 Session 使用同一个 metadata/action source；权限允许/拒绝/渠道关闭均有测试；封面迟到不会串曲；设置状态提示不新增导航入口。

**Batch 2 实际结果（2026-11-02）：** Application 级 MediaNotificationController 复用唯一 RemoteMediaSessionController、现有原生 Session token 与 AppRuntime.reads.getArtwork()；创建单一低重要性静音媒体 channel；以完整服务端媒体身份校验封面迟到结果；通知权限/渠道状态集中映射到 MediaControlAvailability，在现有「设置 → 状态」提供系统设置入口。未添加 Player、第二套网络/状态 owner、Service/FGS 或系统权限弹窗。
验证：clean unit/Lint/Debug APK/Android-test compile+assemble 全部成功；83 suites / 472 tests / 0 failures / 0 errors / 0 skipped；Lint 0 errors / 16 warnings / 4 hints。SM-G986N / API 33 覆盖安装与 5 项 connected instrumentation 通过；权限 denied 时仍观测到 active MediaSession、SystemUI controller 和 NotificationManager 中的 MediaStyle record，封面 largeIcon 为实际 Bitmap。firstInstallTime、DataStore SHA-256、签名证书保持。API 36 V2307A 的覆盖安装被系统安装器以 INSTALL_FAILED_ABORTED: User rejected permissions 中止，未更新其已安装版本/数据；因此 API 36 runtime 未覆盖。用户关闭 channel 的 IMPORTANCE_NONE 分支由状态单测覆盖，未在真机上改动用户的通知设置。详细记录见 [Batch 2 verification report](../reports/2026-11-02-task-7-batch2-verification.md)。B3 的回调/后台服务生命周期和 B4 的完整锁屏手工验收仍未实施。

**提交建议：** `feat(media): add media notification and permission fallback`

## Batch 3 — 后台 Service、恢复和生命周期协调

**目标：** 根据 Batch 0 平台冻结结果接入必要 Service，保证 Activity 退出前台、系统回收、Service 重建与进程重启时行为可预测。

**候选文件：**
- Create `media/RemotePlaybackService.kt`：仅持有系统媒体适配器、处理 action callback、生命周期和通知更新。
- Modify `MpdServerRemoteApplication.kt`：暴露 `awaitServerOriginReady()` 或等价 readiness API；不更改唯一 Runtime 初始化规则。
- Modify `app/src/main/AndroidManifest.xml`：仅采用 Batch 0 已验证的 service/permission/exported/foregroundServiceType 声明。
- Modify `media/RemoteMediaSessionController.kt`：支持 Service attach/detach、Runtime snapshot readiness 和进程内重建。
- Add Service/controller instrumentation tests。

### Task 7.3.1 — Prove lifecycle and callback behavior

- [x] 测试 onCreate/onStartCommand/onTaskRemoved/onDestroy 等生命周期重复触发时的幂等性；重复启动、Task 移除、显式停止与重新启动均有 API 33 instrumentation 覆盖。
- [x] 测试 Activity finish/recreate 不关闭后台 MediaSession；重复启动 Service 不产生第二个 Session，token 与 Runtime owner 保持一致。
- [x] 测试 media action callback 只映射四种合同动作；实际 dispatch 使用 app-scoped gateway。重复/锁定/过期语义继续由 B0 Gateway focused tests 负责。
- [x] Service 在 origin 恢复后才继续；未配置 origin 时停止，不把 DEFAULT placeholder 当成已配置服务器地址。
- [x] 测试进程重建路径：新进程不恢复持久化曲目事实；Session 由新鲜 Runtime projection 重建；callback 尚未附着时动作保持为零，前台重入后重试附着。
- [x] 新 epoch、origin/current 撤销沿用 canonical projection 的 WITHDRAWN/RETAINED/CONFIRMED 迁移，Application 根据投影幂等管理 Service；相关 projection 单测由 B1 覆盖。
- [x] 测试 Service 启动失败与通知权限状态分别记录；不增加不必要权限或 FGS。

### Task 7.3.2 — Implement lifecycle integration

- [x] 按 Batch 0 策略实现普通 started Service。API 33 已验证远程控制 MediaSession 不需要 FGS；未添加 FGS type 或权限。
- [x] Service 通过 Application 获取唯一 Runtime/controller；绝不调用 AppRuntime.create()。
- [x] 提供 origin readiness 等待能力；确认配置 origin 加载完成后才开始恢复媒体状态。
- [x] 由 Runtime snapshot flow 驱动 session/notification，而不是额外启动轮询或新的 WebSocket。
- [x] service start/stop 逻辑幂等；current 无效/显式 Stop 时撤销 notification/session，并按冻结策略停止不再需要的 Service。
- [x] 处理 OS 重新创建 Service：不读取持久化曲目作为当前事实，不自动提交历史 mutation；fresh snapshot 满足 T7-03 后恢复会话。
- [x] 明确 force-stop 与厂商后台管控限制；后台启动被拒绝时记录降级原因，并在 Activity 回到前台时重试。

**Batch 3 实际结果（2026-11-02）：** 新增内部 RemotePlaybackService、标准 transport callback adapter、origin/snapshot readiness gate、同一 MediaSession callback attach/detach 和 Application 级 Service 生命周期协调。普通 Service 返回 START_STICKY，不创建 Runtime/WS/Session，不添加 FGS 权限或 type。未附着 callback 时 Session actions 清零；attach 后重新使用 Gateway 能力投影。服务启动失败诊断与通知权限状态相互独立。

**验证：** 84 suites / 474 tests / 0 failures / 0 errors / 0 skipped；Android test 编译及 Debug APK/Android-test APK/Lint 均通过；Lint 0 errors / 16 warnings / 4 hints。SM-G986N / API 33 新增 3 项 Service/callback/Manifest connected instrumentation 3/3 通过；覆盖安装 firstInstallTime 与 DataStore SHA-256 不变。额外进程回收观察中，新进程先由 fresh Runtime projection 恢复 Session metadata，动作保持为 0，前台重入后 callback 成功附着并恢复 action flags。V2307A / API 36 的 adb 覆盖安装被系统以 INSTALL_FAILED_ABORTED: User rejected permissions 中止，未影响原安装与数据，故不报告 API 36 runtime 通过。完整 evidence 见 [Batch 3 verification report](../reports/2026-11-02-task-7-batch3-verification.md)。B4 锁屏手工验收尚未实施。

**提交建议：** feat(media): manage background session lifecycle

## Batch 4 — 集成回归、实机锁屏与覆盖安装验收

**目标：** 在目标设备真实验证锁屏控制与后台生命周期，并证明不破坏既有 Player、Queue、歌词、Home、Library、Search、Settings 和服务器数据。

**测试范围：**
- 新增/更新 `app/src/androidTest/java/.../media/RemoteMediaSessionDeviceTest.kt`、`RemotePlaybackServiceDeviceTest.kt`、`MediaNotificationDeviceTest.kt`（按实际测试边界命名）。
- 更新 `AppNavigationUiTest`：Activity/route recreation 不创建重复 session；Status page 的权限/后台限制说明正确。
- 更新 Task 4/5/6 既有 instrumentation 中必要的 regression fixture，不重写无关测试。

### Task 7.4.1 — Automated regression

- [ ] 运行：
```sh
./gradlew --offline --no-daemon clean testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin assembleDebugAndroidTest
```
- [ ] 检查全部 T7 projection/gateway/service/notification 测试，确认不依赖实际播放服务器的单元测试可重复运行。
- [ ] 运行 Task 4 Player/Queue/lyrics、Task 5 Home/Library/Search、Task 6 Settings/MPD status 的 connected regression instrumentation。
- [ ] 检查 Manifest merger report、权限、foreground service type、通知 channel 和 targetSdk 警告；`git diff --check` 与静态边界扫描通过。
- [ ] 验证进程内 Runtime/WS/MutationCoordinator owner 计数不变，没有新增业务 DB、网络轮询或后台状态缓存。

### Task 7.4.2 — Real device system UI acceptance

- [ ] 在 SM-G986N / Android 13 真机上覆盖安装测试 APK，先记录 applicationId、签名证书、保存的 MS origin、主题和状态环参数。
- [ ] 使用 `adb install -r app/build/outputs/apk/debug/app-debug.apk`；不卸载、不清数据、不换签名。安装后比对 origin、主题、状态环设置仍在。
- [ ] 在真实连接的 MS/MPD 播放状态下验证锁屏和通知栏出现系统媒体卡片，标题/艺术家/封面与 App 当前曲目一致。
- [ ] 分别验证 Pause/Resume/Previous/Next；确认 Queue occurrence、实际播放确认与 App 内 UI 一致，快速重复触发不会重复写操作。
- [ ] 验证断网后保留最后已知 metadata 且动作不可用；网络恢复后 fresh snapshot 到达才恢复动作。
- [ ] 验证无 current/显式 Stop 撤下媒体卡片；清空 pending Queue 不停止仍有效 current。
- [ ] 在通知权限允许、拒绝、渠道关闭三种条件下验证系统表现及 App 内降级说明。
- [ ] 分别检查退到后台、锁屏、Activity 重建、系统回收后恢复；`force-stop` 只验证重新手动打开后的恢复，不要求系统绕过 force-stop。
- [ ] 记录 API、设备、权限、后台策略、实际可见控件、日志及任何厂商差异；若 API 36 平台只在模拟器覆盖，明确标记真机未覆盖，不能写成全平台通过。

### Task 7.4.3 — Review, report, and commit

- [ ] 审查所有回调是否直接操作 Runtime/MutationCoordinator；确认没有第二个 `OkHttpClient`、RealtimeController、SnapshotStore 或 retry owner。
- [ ] 审查 Stop/no current/stale/origin change/unknown intent/duplicate callback 的分支，不得用 UI 状态冒充 canonical state。
- [ ] 创建 `docs/superpowers/reports/2026-11-01-task-7-verification.md`，逐项引用 T7-01..T7-14 的测试与实机证据，单列未覆盖 API/厂商行为。
- [ ] 更新本计划、合同矩阵、`docs/superpowers/README.md` 与主计划 Task 7 完成状态；不修改 Task 8 完成状态。
- [ ] 最终确认 `adb install -r` 保留用户数据、包名和统一签名不变。
- [ ] 本地提交 Batch 4 验收记录；除非用户另行要求，不推送远端、不合并其他分支。

**Batch 4 退出条件：** 全量 build/unit/Lint/instrumentation 通过；真实设备锁屏和系统回调通过；覆盖安装数据与签名保持；未覆盖平台限制清楚记录。

**提交建议：** `test(media): verify system playback controls end to end`

## Task 7 最终退出门

Task 7 仅在 Batch 0–4 的独立退出条件全部通过、Task 7 合同矩阵 T7-01..T7-14 全部有证据、Task 4/5/6 回归通过且真实设备验收完成后才算完成。Task 7 不负责 Task 8 的全项目交付验收，不得顺带开始 Task 8。


## OriginOS 6 / Android 16 媒体通知控件后续修正（2026-11-02）

Batch 3 完成后续 API 36 真机检查时发现：MediaSession active、metadata/token/action flags 均正常，但 OriginOS 6 的 NotificationRecord 没有显式 Notification.Action（numWithActions=0），故通知中心仅显示普通媒体通知。已在 `MediaNotificationFactory` 中将 canonical projection.actions 显式映射为 Previous / Play-or-Pause / Next notification actions，并设置 MediaStyle compact action indices；按钮通过内部 `RemoteMediaButtonReceiver` 将 KeyEvent 转发到同一个原生 MediaSession callback，仍复用唯一 PlayerCommandGateway。没有新增播放状态、网络、命令路径或 FGS 权限。

V2307A / OriginOS 6 / API 36 验证：新增设备测试 3/3 通过；系统 NotificationRecord 实际含 3 actions、MediaStyle token 和 compactActions=[0,1,2]；通知中心截图确认三个控件可见。Debug + Android-test APK 安装成功，DataStore 与 firstInstallTime 保持。未通过真实播放按钮执行 NAS 写操作，Task 7 Batch 4 其余锁屏和端到端回归仍未完成。详见 [OriginOS 6 media controls fix report](../reports/2026-11-02-originos6-media-controls-fix.md)。
