# Task 9 Batch 1 — Hardware Volume DTO 与共享快照投影验证

- 日期：2026-11-04
- Android 仓库：/home/Gold/WorkSpace/MS_remote_for_Android
- Android 分支：feature/task-9-hardware-volume-control
- 批次开始 HEAD：0da9e6d0203a0bac226deb5022918ddc01cd8437
- 批次结束 HEAD：0da9e6d0203a0bac226deb5022918ddc01cd8437（本批未提交）
- 上游 wire contract：Task 9 B0 报告锁定的 MPD-Server feature/hardware-volume-control HEAD b2f318bc69e828d4b17ecbfa1acffa3381cf9dae；NAS 部署 SHA 7194191b27ad117b593d1cd302cf78bb11792457。

## 1. 批次结论

B1 已完成。服务端 GET /api/state 的两个硬件音量 snake_case 字段现已具备可空扩展 DTO、独立局部 decoder 和 capability policy。旧服务端缺少字段、未来未知枚举、非法值和错误子树都只会使硬件音量投影降级为 UNKNOWN/diagnostic，不会使既有完整播放/Queue 快照失效。

SnapshotStore 仍是唯一 canonical state owner。硬件音量只从 FullStateSnapshot.wire 派生；没有新增 Store、网络 owner、WebSocket、轮询、数据库或持久化状态。本批没有实现 B2 的 realtime invalidate，也没有实现 B3 的 PUT/mutation。

## 2. 实现变更

### DTO 与局部解码

- protocol/dto/StateSnapshotDto.kt 增加可选 hardware_volume 与 hardware_volume_observation 原始 JSON 子树，默认 null，因此旧服务端缺字段仍可解码。
- 新增 HardwareVolumeDto.kt：按 B0 wire contract 定义状态、last request 和 observation typed DTO。状态枚举为 AVAILABLE / UNAVAILABLE / UNSUPPORTED；控制源枚举为 MPD_HARDWARE_MIXER；last request 状态为 PREPARING / SUCCEEDED / FAILED。
- volume_percent 与 requested_percent 保持严格 JSON 整数语义及 0–100 范围；nullable volume 保持 null，不会转换为 0。对 JSON 数字字符串额外拒绝，避免 Kotlin serialization 将带引号的数字宽松解析成整数。
- StateSnapshotResources.kt 新增独立 decodeHardwareVolumeResources()；两个可选子树分别解码、分别记录 diagnostic。缺失/null/解码失败不会吞掉或伪造其他播放、Queue、输出资源字段。
- 既有 decodeResources() 将硬件音量投影及其局部 diagnostic 暴露给调用者；原有必需字段仍使用原来的严格解码。

### Capability policy

- 新增纯函数 HardwareVolumeCapabilityPolicy.evaluate(SnapshotStoreState)。
- 仅在连接为 CONNECTED、快照 freshness 为 FRESH、快照 generation 与当前 connection generation 一致、服务端状态为 AVAILABLE、状态 stale=false、observation freshness=fresh、音量为非 null 的 0–100 整数时输出 canWrite=true 和 controllableVolumePercent。
- displayVolumePercent 只投影有效服务端快照中的 last-known 数值；数据过期时仍可展示，但 controllableVolumePercent 必须为空，不能用展示值作为控制基值。
- policy 不读取当前歌曲、播放状态或 Queue；空 Queue、无 current song、STOPPED 也不会阻断满足服务端硬件音量条件的能力。
- FullStateSnapshot、SnapshotStore、AppRuntime、RealtimeController、StateReadCoordinator、MutationCoordinator 均未修改。

## 3. TDD 与测试证据

### RED

先添加 DTO/policy 测试，再运行：

    ./gradlew --offline testDebugUnitTest --tests '*HardwareVolumeDtoTest' --tests '*HardwareVolumeCapabilityPolicyTest' --no-daemon --console=plain

首次结果为编译失败（缺少 hardwareVolume DTO/resource/policy 类型及字段，exit code 1），确认测试先于实现。

测试期间另发现 Kotlin serialization 对带引号的数字字符串也可能解析为 Int。实现中因此增加 JsonPrimitive.isString 严格检查，并用 "80" fixture 断言它按非法 wire type 局部降级；随后 focused tests 全部通过。

### GREEN / 回归

| 命令 | 结果 |
|---|---|
| ./gradlew --offline testDebugUnitTest --tests '*HardwareVolumeDtoTest' --tests '*HardwareVolumeCapabilityPolicyTest' --no-daemon --console=plain | 14 tests，0 failures，exit 0 |
| ./gradlew --offline testDebugUnitTest --tests '*HomeDataControllerTest' --no-daemon --console=plain | 9 tests，0 failures，exit 0 |
| ./gradlew --offline testDebugUnitTest --no-daemon --console=plain（最终完整回归，代码与测试定稿后） | 513 tests，0 failures，0 errors，0 skipped，90 suites，exit 0 |

完整回归早先一次运行曾出现 HomeDataControllerTest.libraryRevisionRefreshesSongIndexWhileHistoryRemainsCached 单项断言失败（511 tests / 1 failure）。该用例随后单独运行通过、整个 HomeDataControllerTest 类 9/9 通过，最终完整回归 513/513 通过。此记录保留首次结果，不将其描述为已确定根因的代码缺陷。

本批新增测试：

- HardwareVolumeDtoTest：9 tests，覆盖旧快照缺字段、完整 wire 解码、未知状态/控制源枚举、数值越界、数字字符串类型错误、nullable volume、last request、observation 独立失败及对既有完整资源解码的隔离。
- HardwareVolumeCapabilityPolicyTest：5 tests，覆盖 status × observation freshness × snapshot freshness × connection status × connection generation × stale × nullable volume 的资格矩阵、last-known 仅展示、无歌曲/STOPPED/空 Queue 仍可用，以及缺字段/未知状态 fail-closed。

## 4. 权威边界与设备/服务状态

- 没有新增 DataStore、Room、SQLite 字段、持久化 volume state、第二个 SnapshotStore、独立 HTTP client、WebSocket 或 poller。
- 没有修改播放、Queue、History、音源路由、设备 AudioManager 或系统媒体会话逻辑。
- 没有访问 NAS 音量 PUT、MPD setvol 或 ALSA mixer；没有改变服务端音量/播放/Queue。
- 本批没有覆盖安装 APK：交付仅为协议 DTO、共享快照解码投影与纯函数 policy，不涉及 UI/设备输入。设备现有安装与 MS 地址数据未触碰。
- B2–B7 未执行；特别是 WS invalidate 同步、PUT/idempotency、命令串行化、DAC 面板、Slider、前台音量键/浮窗和 NAS/Android 实机写入验收均不在本批结论内。

## 5. 交付检查

- [x] B0 的 snake_case wire contract 已用于 DTO，不混用专用 REST endpoint 的 camelCase。
- [x] 旧服务端缺字段可兼容；非法可选子树局部 UNKNOWN/diagnostic。
- [x] capability policy 严格绑定共享快照 freshness、连接与 generation；不依赖播放状态。
- [x] 单测 focused GREEN 与最终完整 unit suite GREEN。
- [x] git diff --check：exit code 0。
- [x] 没有超出 B1 的生产行为变更或服务端副作用。

**结论：** Task 9 Batch 1 退出门满足。工作区保留本批未提交改动；未 push、未 merge。下一批 B2 尚未开始。
