# Task 9 Batch 5 — Slider 交互与服务端读回验证

**日期：** 2026-11-04
**分支：** `feature/task-9-hardware-volume-control`
**范围：** 仅 Task 9 Batch 5；未实现 B6 音量键/提示浮窗，未执行 B7 NAS 写入验收。

## 1. 结果摘要

Batch 5 已完成。DAC 面板的 Slider 现在接入 AppRuntime 唯一的 `HardwareVolumeCommandGateway`。Player 与 Queue 使用同一 gateway、同一 SnapshotStore 和 MutationCoordinator；Slider 不持有持久音量值，也不建立网络写入路径。

拖动时只更新 composable 内的手势草稿与预览轨道/数字；手指释放或无障碍 `SetProgress` 动作完成时，产生一个 0–100 整数绝对目标并交给 `setAbsolute()`。在完整状态读回前，右侧数字保持服务端最后确认值；pending 使用进度指示器，FAILED 显示错误，UNKNOWN 禁用新的 Slider 操作并保留原 intent 的显式恢复路径。

## 2. 实现内容

- `HardwareVolumeCapabilityPolicy` 增加 `HardwareVolumeCanonicalTuple`，记录 epoch、connection/snapshot generation、连接与快照 freshness、能力状态、音量值、state stale 和 volume observation freshness。无关的播放/Queue 快照序号变化不会取消 Slider 草稿；音量控制会话身份或状态变化则会使草稿失效。
- 新增 `HardwareVolumeSliderDraft`，仅保存一次手势的 baseline tuple、preview 和 invalidated 状态。tuple 改变时清除 preview；release 只在 baseline 仍与当前 canonical tuple 相同且当前仍可写时返回整数目标。
- `CompactDacPanelPopup` 在 AVAILABLE fresh 且数值有效时启用 Material Slider，步进为 1；非可写、值未知或写入结果 UNKNOWN 时保持只读/禁用。触控区域保留 48dp 行高，视觉轨道仍为 4dp。
- Player 与 Queue 面板都收集同一个 `runtime.hardwareVolumeCommands.state`，并将释放后的绝对目标交给同一 `runtime.hardwareVolumeCommands.setAbsolute()`。
- AppNavHost 对 `hardware-volume:` unknown activity 的“使用原操作重试”路由到 gateway 的 `retryUnknown()`，不会绕过 gateway 重新生成目标或改用通用 mutation 重试。

## 3. TDD 与自动化验证

### RED / GREEN

- 先增加 canonical tuple 单测；RED 阶段因生产 projection 尚无 `canonicalTuple` 而在编译期失败，随后补入 typed tuple。
- `./gradlew --offline testDebugUnitTest --tests 'io.github.qyxx2.mpdserver.remote.ui.player.HardwareVolumeSliderDraftTest'`：exit 0。
- `./gradlew --offline testDebugUnitTest --tests 'io.github.qyxx2.mpdserver.remote.ui.player.HardwareVolumeSliderDraftTest' --tests 'io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeCapabilityPolicyTest'`：exit 0。

### 真机 Compose instrumentation

命令：

`./gradlew --offline connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.player.FullScreenPlayerPresentationUiTest`

结果：SM-G986N / Android 13，10/10 tests passed，0 failed、0 skipped。覆盖内容包括：

- 服务端当前资格决定 Slider 启用或禁用；视觉轨道保持纤细，交互行维持 48dp 触控区域。
- 一次实体拖动只提交一次绝对目标；提交后 pending 出现，数字仍是旧的服务端确认值，只有模拟服务端 canonical readback 更新后数字才变化。
- Compose `SemanticsActions.SetProgress`（TalkBack 可调节入口）走相同 release/callback/gateway seam，并且只产生一次新绝对目标。
- FAILED 保留 canonical 数字并显示错误；UNKNOWN 保留 canonical 数字、显示未知状态标识并禁用 Slider。
- 原有 DAC 面板布局、输出状态、字体缩放、宽度、占位值、专辑/艺人导航等相关 UI tests 继续通过。

手势 UI 测试的 `onVolumeCommit` 使用测试回调记录目标，不触发 MPD PUT；本批没有向 NAS 执行真实音量写入。

### 全量回归

命令：

`./gradlew --offline testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false`

结果：exit 0；unit-test XML 汇总 532 tests、0 failures、0 errors、0 skipped；`lintDebug` exit 0；`assembleDebug` 成功（本次调用显示 `UP-TO-DATE`，Debug APK 已由 connected instrumentation 构建依赖生成）。

## 4. 单一权威与设备边界

- 可见 confirmed 音量只取自 `SnapshotStore` 的 `HardwareVolumeCapabilityPolicy` 投影。手势 preview 是一次交互的临时 UI 状态，不写入 SnapshotStore、数据库、DataStore 或其他本地持久层。
- 写入只通过 AppRuntime 已有 gateway → MutationCoordinator；成功路径仍由 gateway 等待服务端完整快照 readback 后结束，客户端不会用 PUT 响应自行覆盖 canonical 值。
- 没有新增 HTTP client、WebSocket、poller、后台服务、AudioManager 或本地 mixer 控制。
- 在已连接的 SM-G986N Android 13 上运行 instrumentation；没有卸载应用或执行 `pm clear`。当前设备已有 MS 地址配置可用于现有 runtime 状态读取；本批自动化 Slider 写入测试采用本地回调，没有触发服务端音量 PUT。

## 5. Batch 5 退出门

- [x] 只有 AVAILABLE fresh 且值有效时开放 Slider。
- [x] 拖动只显示局部预览，释放只生成一个整数绝对目标。
- [x] pending/syncing 不将目标显示成 confirmed；确认数字等待服务端完整快照 readback。
- [x] volume-specific canonical tuple 变化时取消草稿，不能提交旧目标。
- [x] FAILED 回到 canonical 数字并展示错误；UNKNOWN 保留活动并通过 gateway 原 intent 显式恢复。
- [x] 无障碍 SetProgress 复用相同 Slider 提交路径。
- [x] Player 和 Queue 共用同一 AppRuntime gateway；未创建第二个服务端状态权威。
- [x] 532 项全量 unit tests、10 项相关 connected instrumentation、Lint 和 Debug APK 构建通过。
- [x] `git diff --check` 与最终完整 diff 审查通过（见本批最终检查）。
- [ ] B6 前台音量键/提示浮窗与 B7 NAS 可恢复写入验收：不属于本批。

**结论：** Task 9 Batch 5 的 Slider 交互与读回接线已完成。未执行 B6/B7；未 push、未 merge、未提交。
