# Task 9 Batch 2 — WebSocket hardware-volume invalidate 与完整快照同步验证

- 日期：2026-11-04
- Android 仓库：`/home/Gold/WorkSpace/MS_remote_for_Android`
- Android 分支：`feature/task-9-hardware-volume-control`
- 批次开始 HEAD：`c705366`（`feat(android): add hardware volume snapshot projection`）
- 批次结束 HEAD：`c705366`（本批未提交）
- 本批目的：仅验证/接通服务端 `hardware_volume` WebSocket invalidate 到唯一 canonical snapshot 的实时同步，不涉及 PUT、DAC UI、Slider、音量键或浮窗。
- 上游 wire contract：Task 9 B0 锁定的 MPD-Server `feature/hardware-volume-control` HEAD `b2f318bc69e828d4b17ecbfa1acffa3381cf9dae`；精确帧结构及服务端证据见 [Batch 0 报告](2026-11-04-task-9-batch0-verification.md) §5。

## 1. 结论

Batch 2 完成。`hardware_volume` domain 由现有 protocol-v1 invalidate decoder 正常解码；现有 RealtimeController 无条件将 invalidate watermark 转交 StateReadCoordinator，后者通过单一、合并的 `GET /api/state` 获取完整快照，再由 SnapshotStore 执行 canonical acceptance。硬件音量投影因此随完整状态原子更新，不从 invalidate 帧或本地输入推断数值。

代码审查确认本批不需要生产代码修改：decoder 没有领域 allowlist，不会拒绝 `hardware_volume`；RealtimeController/StateReadCoordinator 已具备统一失效路由、GET 单飞/合并、水位推进重读、connection generation 防护和读取失败 stale 标记。新增测试把 B0 已核实的现有行为固定为硬件音量回归合同。

## 2. 测试变更

新增/扩展测试文件：

- `app/src/test/java/io/github/qyxx2/mpdserver/remote/protocol/RealtimeFrameDecoderTest.kt`
  - `decodesHardwareVolumeInvalidateAsDomainOnlyNotice`：校验精确的 protocol-v1 `domains=["hardware_volume"]` frame；只读取 epoch、sequence、domain、revisions，不包含/解码任何 volume payload。
- `app/src/test/java/io/github/qyxx2/mpdserver/remote/runtime/RuntimeCompositionTest.kt`
  - `hardwareVolumeInvalidationsUseAuthoritativeReadbackAndIgnoreCoveredNotices`：通过唯一实时连接发送 seq 2/3/4 invalidate；后续通知在第一条完整状态 GET 在途期间由现有事件流缓冲；首个响应后继续通过共享协调器进行完整快照读取，最终接受 seq 4 并由 B1 capability policy 从 canonical snapshot 投影服务端音量；随后重复/更旧通知不再触发 GET。
  - `failedHardwareVolumeRefreshDisablesWritesUntilReconnectSnapshot`：完整 GET 失败后快照 freshness 变 STALE，保留 last-known 仅展示、`canWrite=false` 且 controllable value 为空；重连接收新的完整 snapshot 后才恢复控制资格。
  - `lateHardwareVolumeReadCannotReplaceSnapshotAfterOriginSwitch`：旧 origin 的 GET 被延迟，期间切换 server origin 并接受新 epoch；释放旧响应后验证它不能覆盖新 origin，后续同步结果来自新 origin 的完整快照。
- `app/src/test/java/io/github/qyxx2/mpdserver/remote/runtime/StateReadCoordinatorTest.kt`
  - `ignoresInvalidateFromDifferentEpochOrConnectionGeneration`：错 epoch 和旧 connection generation 的 watermark 被忽略，不发起 GET、不改写当前快照。
  - 既有 `coalescesConcurrentReads`、`rereadsWhenWatermarkAdvancesDuringGet`、`ignoresOlderInvalidateWatermarkWithoutStartingAnotherRead` 继续覆盖 StateReadCoordinator 公共入口的并发单飞、在途水位推进后的重读和旧水位抑制；其中较高水位合并由 coordinator 单测独立证明，Runtime 测试验证 WS 事件路由与完整快照最终收敛。

测试使用 deterministic fake WebSocket 与 state reader；不是 NAS 实时 WS 流量验证。无须通过故意破坏生产路径制造 RED：B0 已证明通用 invalidate 处理满足这条路径，本批以行为回归测试锁定既有实现，而不加入冗余的 hardware-volume 专用分支。

## 3. 验证结果

命令均在指定工作区执行，Gradle 使用 `--offline --no-daemon --console=plain`：

| 命令 | 结果 |
|---|---|
| `./gradlew --offline testDebugUnitTest --tests '*RealtimeFrameDecoderTest' --tests '*RuntimeCompositionTest' --no-daemon --console=plain` | BUILD SUCCESSFUL；19 tests，0 failures/errors（decoder 8、runtime composition 11） |
| `./gradlew --offline testDebugUnitTest --tests '*RealtimeFrameDecoderTest' --tests '*RuntimeCompositionTest' --tests '*StateReadCoordinatorTest' --no-daemon --console=plain` | BUILD SUCCESSFUL；25 tests，0 failures/errors（含 StateReadCoordinator 6 tests） |
| `./gradlew --offline testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain` | BUILD SUCCESSFUL，exit 0；完整单测 90 suites / 518 tests，0 failures / 0 errors / 0 skipped；Lint 0 errors、18 warnings、2 hints；`assembleDebug` 成功 |
| `git diff --check` | exit 0 |

构建产物：`app/build/outputs/apk/debug/app-debug.apk`，14 MB；`applicationId=io.github.qyxx2.mpdserver.remote`，`versionName=0.1.0`；`apksigner verify --verbose --print-certs` 通过，使用既有 internal signer 的 APK Signature Scheme v2（证书 SHA-256：`36c589d3e947dee7f36204d1d19f4e96275159a6a7ff9cd058e5cdd448a2c89f`）。

## 4. 权威边界与副作用

- 只有现有 `RealtimeController` 持有实时连接；没有新建 WebSocket、poller、HTTP client 或硬件音量专用 GET owner。
- `hardware_volume` invalidate 只携带 domain/watermark，不携带硬件音量状态/数值；所有展示与控制资格仍来自完整快照及 B1 policy。
- SnapshotStore 仍是唯一 canonical owner。没有本地音量 state、数据库字段、持久化、snapshot patch 或 playback/queue/history mutation。
- 没有修改 MPD、NAS、ALSA/USB 输出或服务端音量；没有覆盖安装 APK，也没有修改/清理已安装 App 数据。B2 的验证为本地单元/Runtime 集成测试，不将其描述为真机实时 WS 验收。
- Task 9 B3–B7 未开始；尤其没有实现 PUT、幂等写入、命令 gateway、DAC 弹窗、Slider、前台音量键或浮窗。

## 5. 退出门

- [x] hardware_volume domain-only invalidate 被 decoder 接受；帧不携带音量 payload。
- [x] 事件只进入现有 RealtimeController → StateReadCoordinator → GET `/api/state` → SnapshotStore 路径。
- [x] 单飞/高水位合并、重复/旧水位、epoch/generation、origin change 与迟到响应保护有测试覆盖。
- [x] 读取失败 fail-closed；重连后的新完整快照恢复硬件音量可写资格。
- [x] 没有增加第二个 realtime/network/state owner；`git diff --check` 通过。
- [ ] NAS/Android 实时 WS 设备验收：留待 Task 9 B7，不属于本批自动化证据。

**结论：** Batch 2 的代码与自动化退出门满足。仅新增回归测试和批次文档，没有必要的生产代码变更。工作区保留本批未提交改动；未 commit、未 push、未 merge。Batch 3 及后续批次未执行。
