# Task 4 Batch 1 验证报告

日期：2026-10-27
工作区：`/home/Gold/WorkSpace/MS_remote_for_Android`
分支：`task4`
实施起点：`b56f08b`（B0 resource/read contracts）

## 本批次交付

新增纯事实读取与展示投影：

- `PlayerFacts`：从现有 app-scoped `SnapshotStoreState` / canonical snapshot 独立提取 Song、Queue、business playback、playback observation、output 和 output observation。单个资源解码错误不会将其它有效资源清空；不创建 Store、网络 owner 或业务副本。
- `PlayerPresentation`：把权威播放观察投影为 PLAYING / PAUSED / UNKNOWN 及明确诊断。必须同时满足 snapshot/连接代际、新鲜度、实际观察、matches_current、position 0 Queue occurrence 身份绑定、允许的 sync status 和 actual state，才能确认播放。
- 控件能力只在已确认播放且服务器 control target 绑定当前 occurrence 时开放；pause/resume 依赖实际观察状态。Seek 还需要有效、有限的 position/duration 范围。
- 曲目元数据只在 Song ID 与 position 0 occurrence 匹配，且观察绑定该 occurrence 时展示；重复 song ID 不会替代 queue_item_id 身份。
- Source codec / bit depth / sample rate / channels 只取自 Song DTO；DAC/output format 和状态只取自 output resources。过期/未知输出观察不展示旧格式数字；ACTIVE 不代表真实硬件出声或 bit-perfect。
- 对缺失/无效 Song、缺失封面引用、未知歌词状态、歌词 read_error、无/损坏 Queue、过期/未知输出状态分别保留状态或诊断。

本批次没有增加 UI、网络请求、播放/Queue 写入、时钟或新的状态 owner；未开始 Batch 2。

## 验证

按 TDD 执行测试先行：

1. 首轮新增测试在生产 mapper 尚不存在时按预期 RED（编译期 unresolved reference），证据见被忽略的本地 SDD 日志 `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b1-focused-red.log`。
2. 首次 GREEN 运行暴露重复歌曲 occurrence 绑定问题；实现已调整为要求 observation.bound_queue_item_id 与 position 0 的 queue_item_id 匹配，之后测试通过。
3. 聚焦 `PlayerFactsTest`、`PlayerPresentationTest`：通过。
4. 完整命令 `./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug`：BUILD SUCCESSFUL。单元测试合计 **146 passed / 0 failures / 0 errors**。离线模式使用工作区已有缓存依赖；没有改依赖声明。
5. B1 新增文件的 `git diff --check`：通过。lint 报告中未发现 `PlayerFacts` / `PlayerPresentation` 对应告警。
6. 生成 Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。

## 已安装设备的数据保留检查

目标设备：ADB `10.87.61.250:40689`。通过 `adb install -r` 覆盖安装新 Debug APK，安装返回 `Success`。安装前后应用 DataStore 文件 `files/datastore/ui_preferences.preferences_pb` 的 SHA-256 完全一致；`server_origin` 和 HTTP origin 字符串在前后均可见；包仍正常安装。此检查确认覆盖安装保留了现有配置，不等于本批次重新执行了 MS 联通性验收。

## 范围与未覆盖项

本批次未执行真实播放、暂停、seek、Queue 写入或 DAC 硬件验证。纯映射测试和构建不能替代这些后续批次/实机验收。没有修改服务端仓库，没有提交或合并其它预存的工作区改动。


## Web 行为一致性二次审查（2026-10-27 后续补充）

用户要求对“复用 Web 行为、单一服务端状态权威、Android/Web 行为等价”做实证验证。审计发现原 B1 的初始实现并非完全等价；已修正并增加真正由 Web 实现产生的对照测试，不能把最初的 146 项单测通过视为已经证明 Web 等价。

### Web 对照方法与结果

- Web 工作树：`/home/Gold/WorkSpace/MPD-Server/MPD-Server`，分支 `main`，HEAD `2ad8445`，工作树干净。
- 基准函数为 Web 实际代码 `web/src/components/player/playerFacts.ts::derivePlayerFacts`；基准输入来自现有 `web/tests/fixtures/wire.json`。
- 直接执行了 Web 现有测试：`npm test -- --run tests/invariants/player-facts.test.ts tests/invariants/player-controls.test.ts tests/invariants/queue-view.test.ts`，**3 个测试文件、71 项测试通过**。
- 使用 Web 实际 TypeScript `derivePlayerFacts` 计算 17 组完整 snapshot/expected oracle，保存在 Android 测试资源 `app/src/test/resources/fixtures/task4/web-player-facts-oracles.json`。这些场景覆盖 canonical 状态、只读/断连、未绑定、SYNC_FAILED、观察过期、NO_CANDIDATES、confirmed STOPPED、缺少 control target、position 未知但 duration 有效、无 DAC 状态、缺少 observed_at、DAC state stale、带 error 的 fresh 输出观察、单曲 Queue、存在 Played occurrence，以及实际 playing。
- Android 新增 `androidProjectionMatchesWebPlayerFactsOracleAcrossCanonicalAndDegradedSnapshots`：逐个读取同一完整 snapshot 与 Web 的实际期望输出，比较 bound/song identity/output freshness、pause/resume/seek/next/previous、源文件参数和可确认的 DAC 输出字段。定向差分测试通过。
- Android 最终完整验证：`./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug` 返回 `BUILD SUCCESSFUL`；**153 项单元测试通过，0 failures，0 errors，0 skipped**。日志为 `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b1-finalverify.log`。

### 这次实际找到并修正的偏差

1. 业务播放字段解码一度按 DTO 的旧字段形状解码整个对象，而实际 snapshot 用的是独立的 `playback.state`；现在只从 canonical snapshot 中解码该字段。
2. 初版无条件开放已确认 current 的上一首/下一首；现已要求 Queue 中确实存在相应位置的 occurrence。
3. 初版 seek 额外要求 `position_seconds` 已知；Web 规则只要求 active current、有效 target 和正 duration。现已与 Web 对齐。
4. 初版 output freshness 未要求 NAS_DAC 状态和 `observed_at`，且会把 observation error code 直接降为 ERROR；现已按 Web 对照规则处理 timestamp、freshness、DAC state 的 stale bit，并保留 error 字段而不擅自改写 freshness。
5. 初版会在观察 stale/stopped 时仍把相同 song_id 的元数据当作当前实际媒体展示；现已要求实际播放观察 fresh、匹配 current、绑定当前 Queue occurrence，并且播放状态处于 playing/paused，才将歌曲元数据投影为正在播放媒体。

### 权威边界审查与保留差异

- `PlayerFacts` 和 `PlayerPresentation` 仅读取 Application 现有 `AppRuntime` 所持有的 `SnapshotStoreState`/canonical snapshot 与 DTO；静态扫描没有发现它们创建新的 `AppRuntime`、`SnapshotStore`、`RealtimeController`、`MutationCoordinator`、HTTP client 或网络请求。B1 没有引入第二个播放/Queue 状态源，也没有本地业务状态持久化。
- 服务端仍是唯一播放事实来源；Android 的状态新鲜度/连接代际检查是对现有共享状态的消费，不会生成或覆盖服务端快照。
- 对相互矛盾的 payload，Android 有意比孤立的 Web `derivePlayerFacts` 更严格：额外要求 bound ID 与 Queue 位置 0 occurrence 一致、Song ID 一致、control target 与 current occurrence 一致；stale/unknown 时也会隐藏旧 DAC 数值，避免把历史数字显示成当前输出。这些是 Task 4 的 fail-closed 合同，不应描述为字节级相同算法。
- 本次验证覆盖的是 B1 的事实投影以及其 canonical/degraded snapshot 输入。没有声称验证完整的 WebSocket 生命周期、播放器时钟、所有 mutation 恢复路径、所有 UI 行为或真实播放/硬件端到端等价；它们分别属于既有 Runtime 和后续 Batch 的责任范围。
