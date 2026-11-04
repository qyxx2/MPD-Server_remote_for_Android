# Task 9 Batch 3 — PUT mutation 与硬件音量命令串行化验证

- 日期：2026-11-04
- Android 仓库：`/home/Gold/WorkSpace/MS_remote_for_Android`
- Android 分支：`feature/task-9-hardware-volume-control`
- Batch 开始 HEAD：`c996a32`（`test(task9): verify hardware volume realtime sync`）
- 上游 wire contract：Task 9 B0 锁定的 MPD-Server `feature/hardware-volume-control` HEAD `b2f318bc69e828d4b17ecbfa1acffa3381cf9dae`；NAS 部署代码祖先 `7194191b27ad117b593d1cd302cf78bb11792457`。
- 本批范围：只实现共用 PUT mutation gateway 与输入仲裁；不实现 DAC 弹窗、Slider UI、Activity 音量键监听或浮窗（分别留给 B4–B6）。

## 1. 结论

Batch 3 完成。Android 现在有一个 AppRuntime 级 `HardwareVolumeCommandGateway`，为后续 DAC Slider 和前台音量键提供同一命令入口。它只持有临时输入队列、活动意图及 UNKNOWN 重试身份；所有确认音量仍来自 `SnapshotStore` 接受的服务端完整状态快照。

命令通过现有唯一 `MutationCoordinator` 发送 `PUT /api/system/hardware-volume`，body 使用严格整数 `{"volumePercent": n}`。幂等 key 由 MutationCoordinator 为每个新 intent 生成；AppRuntime 既有 send adapter 继续将其写入 `Idempotency-Key` header。PUT 成功后复用既有完整 `GET /api/state` post-write synchronizer，未从 PUT body、slider 目标或按键 delta patch SnapshotStore。

`MutationCoordinator.kt`、`SystemRepository.kt` 和 `HttpTransport.kt` 均无需生产代码修改；没有建立第二个 HTTP client、状态 Store、WebSocket 或持久化音量数据。

## 2. 实现内容

### AppRuntime 单一入口

- 新增 lazy application/runtime-scoped `hardwareVolumeCommands`，与既有 `mutations`、`snapshots` 和 `realtimeController` 生命周期一致。
- gateway 直接复用 `mutations.submit` / `mutations.retryUnknown`，并与 origin 切换使用同一临界区，避免旧 origin 快照通过 eligibility 检查后被提交到新 origin。
- origin 切换时先清除未发送的旧 endpoint 输入；gateway 关闭时先于共享 Runtime scope 清理其 collector/worker。

### 输入 FIFO 与顺序

- `setAbsolute(0..100)` 表示 Slider release 的一个绝对目标；`step(-1|+1)` 表示一次有效按键 DOWN/repeat 的一个百分点 delta。无效范围和无效方向 fail-closed。
- 输入按到达顺序进入短暂 FIFO；仅相邻、同方向的 key delta 可合并，绝不跨越一个绝对 Slider 目标合并。相反方向不会折叠。
- 同一时刻最多有一个硬件音量 mutation 处于 pending/syncing。前一个 mutation 必须完成完整状态 readback，且其新值进入 SnapshotStore 后，下一项才从最新 fresh server value 计算目标。
- 边界值钳制 0–100；若最新 server value 已等于目标，则跳过写入。
- 音量能力由 B1 `HardwareVolumeCapabilityPolicy` 判断，与当前歌曲、播放/暂停状态和 Queue 是否为空无关。

### 成功、失败和恢复

- 普通新 intent 使用新 ID/key；gateway 只有在 MutationCoordinator 活动状态成为 SUCCESS、同步无错误、当前 epoch/generation/origin 仍有效，并且 canonical readback 匹配目标后，才继续发送 FIFO 下一项。
- 明确 4xx 进入 FAILED，清空其后未发送输入；用户后续新输入可产生新 intent/key。
- Transport、5xx、protocol/decode 等不确定结果遵守 MutationCoordinator UNKNOWN 语义。gateway 丢弃队列并保留原 immutable intent 身份；显式 `retryUnknown()` 只重试原 method/path/payload/origin/key，不根据当前 UI 值重建请求。
- 旧 origin UNKNOWN intent 不允许转投新 endpoint。连接 generation、epoch 或 freshness 失效会清空未发送输入；重连后的新鲜快照不会自动恢复旧队列。
- 成功 HTTP 响应但 canonical readback 未确认目标时停止 FIFO，不以目标值伪装成功，也不自动重发。

## 3. 自动化验证

### 新增/扩展测试

`HardwareVolumeCommandGatewayTest`（8 tests）：

- `serializesKeyAndAbsoluteInputsAgainstConfirmedReadback`：验证 key delta/absolute/反向队列顺序、相邻 key delta 合并、请求串行、每个新 intent 新 key、payload/path/method 正确，以及 readback 前 SnapshotStore 不发生乐观 patch。
- `unknownOutcomeDropsQueuedInputsAndExplicitRetryReusesOriginalRequest`：验证 UNKNOWN 清空队列，显式恢复复用原 method/path/payload/key，未发送 key delta 不会在恢复后补发。
- `oppositeKeyStepsAreNotCollapsedAcrossAClampedBoundary`：验证不同方向 delta 保持顺序，目标按最新确认值计算并钳制边界。
- `explicitBusinessFailureDropsQueuedInputAndAllowsANewIntent`：验证明确 4xx 不触发 readback、丢弃后续项，之后的新意图可使用新 key。
- `freshnessLossOrGenerationChangeDiscardsQueuedInputsPermanently`：验证 generation/freshness 切换后排队输入不跨代补发。
- `explicitHttpFailureStopsTheInputFifoWithoutSendingTheNextKeyStep`：验证 5xx 作为 ambiguous UNKNOWN 保留原 intent 供显式恢复。
- `successfulHttpResponseWithMismatchedCanonicalReadbackStopsQueue`：验证写入成功响应不能代替服务端完整快照确认。
- `originChangeDiscardsQueuedInputsAndCannotRetryUnknownOnNewOrigin`：验证旧 endpoint UNKNOWN 不可在新 origin 重试。

`RuntimeCompositionTest` 新增 1 项 `hardwareVolumeGatewayIsApplicationScopedAndReusesRuntimeMutationOwner`，验证 gateway 是 AppRuntime 单例，并绑定同一个 mutations owner；本文件共 12 tests。`MutationCoordinatorTest` 的 18 项原有回归继续验证 key 复用、transport/5xx UNKNOWN、4xx FAILED、SYNCING/readback、origin change 与并发重复拦截。

### 命令结果

| 命令 | 结果 |
|---|---|
| `./gradlew --offline testDebugUnitTest --tests '*HardwareVolumeCommandGatewayTest' --tests '*RuntimeCompositionTest' --tests '*MutationCoordinatorTest' --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false` | exit 0；38 tests，0 failures/errors/skips |
| `./gradlew --offline testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false` | exit 0；全量 527 tests，0 failures/errors/skips；Lint 0 errors、18 warnings、2 hints；`assembleDebug` 成功 |
| `git diff --check` | exit 0 |

构建产物：`app/build/outputs/apk/debug/app-debug.apk`，13 MB；`applicationId=io.github.qyxx2.mpdserver.remote`，`versionName=0.1.0`，`compileSdk=36`。`apksigner verify --verbose --print-certs` 通过，沿用既有 internal signer，证书 SHA-256：`36c589d3e947dee7f36204d1d19f4e96275159a6a7ff9cd058e5cdd448a2c89f`。

## 4. 权威边界与本批未覆盖项

- SnapshotStore 仍是唯一 canonical volume owner；gateway 状态只暴露 transient phase、活动 intent、队列长度和同 intent UNKNOWN retry identity。
- 唯一 HTTP 写 owner 仍是 MutationCoordinator；唯一 realtime owner 仍是 RealtimeController。gateway 不建立额外 GET/WS/poller。
- 不写 DataStore、Room、SQLite 或文件；不接触本机 `AudioManager`、MPD TCP、ALSA/USB mixer，也不触发 playback、Queue、History mutation。
- 本批验证是本地 fake transport/coordinator/runtime 自动化，不是 NAS 实际 PUT 或 Android 实体音量键验收。没有对 NAS、MPD、DAC 或已安装 App 数据做写操作/清理，也没有覆盖安装 APK。
- DAC 紧凑弹窗、Slider 手势/preview/readback UI、Activity 前台 Volume key adapter 和顶部浮窗均未在本批实现；留在 B4、B5、B6。NAS/Android 真实写入、恢复原音量及播放/Queue 前后对比留在 B7。

## 5. Batch 3 退出门

- [x] 新 intent 使用 PUT `/api/system/hardware-volume`、`volumePercent`，并复用现有 MutationCoordinator 的 Idempotency-Key 创建与 HTTP header adapter。
- [x] mutation SUCCESS 后等待既有完整 `/api/state` readback；不从 PUT body 或 intent patch SnapshotStore。
- [x] UNKNOWN 保留同一 immutable intent，显式 retry 复用原 key/payload/origin；5xx/transport ambiguous 不创建新 intent 自动补发。
- [x] Slider absolute 与 key delta 进入同一 FIFO；相邻同方向 key delta 合并，顺序不穿越 absolute target。
- [x] 每个下一目标基于前一次服务端确认快照；最多一个硬件音量 mutation pending/syncing。
- [x] FAILED/UNKNOWN、freshness 失效、epoch/generation/origin 变化时清空未发送意图；旧 origin 不迁移。
- [x] 没有增加第二个状态/网络/realtime owner；无本地音量持久化；不调用 playback/queue mutation。
- [x] focused 38 tests、full 527 tests、Lint、APK build 与 `git diff --check` 通过。
- [ ] NAS/Android 真机硬件音量写入/readback 与 playback/Queue 前后状态审计：留待 B7。

**结论：** Batch 3 的代码与自动化退出门满足。未 commit、未 push、未 merge；工作区保留 B0–B3 尚未提交变更。Batch 4–7 未执行。
