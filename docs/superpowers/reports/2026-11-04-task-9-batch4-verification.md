# Task 9 Batch 4 — DAC 紧凑弹窗与信息展示验证

- 日期：2026-11-04
- Android 仓库：`/home/Gold/WorkSpace/MS_remote_for_Android`
- 分支：`feature/task-9-hardware-volume-control`
- Batch 开始 HEAD：`f7ebce248f7c73f897712091bcb19edf4a3d2da2`
- 本批范围：替换大型 Output Details dialog，复用现有 PopupMotion/Dialog owner 与 SnapshotStore 音量投影；Slider 保持只读禁用占位。

## 1. 结论

Batch 4 完成。全屏 Player 与 Queue 的 DAC 入口现在共用同一个紧凑两行面板；原 `OutputDetailsDialog` 已删除，生产代码中不再存在旧大弹窗调用。没有引入额外状态 Store、网络/Realtime owner 或 volume 本地状态，DAC/音量信息均沿用现有 presentation 和 `SnapshotStore` 接受的服务端快照投影。

B4 未实现 Slider 写入、音量键监听或全局提示浮窗；这些分别留给 B5、B6。没有执行任何 MPD/NAS 音量或输出状态写入。

## 2. 实现内容

### 共用紧凑面板

- 新增 `ui/player/CompactDacPanelPopup.kt`，第一行显示 DAC 输出摘要及短状态，第二行显示音量图标、禁用 Slider 和整数/未知值。
- DAC 格式、采样率、位深和声道只在 output observation 为 `FRESH` 时显示；缺失字段省略。STALE/UNKNOWN/ERROR 等非 fresh 状态不会继续展示历史格式数据，避免旧采样参数被误认为当前输出。
- 输出状态覆盖 ACTIVE、INACTIVE、UNAVAILABLE、STALE、UNKNOWN、ERROR 的中文短标签；“已启用”仅表达服务端状态，不表示实体 DAC 已确认出声。
- 音量只读取 `HardwareVolumeCapabilityPolicy` 对共享快照的投影。0–100 范围内的服务端确认值只显示纯数字；未确认/未知值显示 `—`，不显示 `%`，也不把禁用 Slider 的内部占位 0 暴露为状态。
- 禁用 Slider 使用只读语义：服务端值有效时只暴露该确认值对应的真实 0–100 范围；UNKNOWN 时不设置进度范围，只播报“数值未知”，避免 TalkBack 把内部布局占位误读成 0。两种情况都保持 disabled，不开放 B4 写入。

### Popup/动效与入口

- DAC 面板宿主直接采用 `QueueRowMenu` 队列条目三点菜单的实现方式：attached `Popup` + `AnchoredDropdownPositionProvider(48.dp)` + `PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)`。
- 复用 Task 8 的 `PopupMotionHost` / `PopupMotionAnchorState` 和真实 trigger/owner screen bounds：180ms、0.82→1 scale、alpha、实测锚点 transform origin 与反向关闭。不创建全窗口 Dialog、scrim 或透明外部拦截层。
- 表面样式与队列条目菜单保持一致：白色 94% alpha、18dp 圆角、7dp shadow、0dp tonal elevation。
- 面板宽度按 DAC 信息文本与状态文本的实际测量结果展开：最小 200dp，最大受 92% 可用宽度 / 520dp / 16dp 水平安全边距约束。缺少格式/采样字段时面板变短，信息较多时自然加宽并在上限处省略。
- DAC 入口再次点击、Popup 外部点击与 Back 均可关闭；播放器和 Queue 复用同一面板，不再有第二份旧详情弹窗。

## 3. 验证

### Android 实机 instrumentation

设备：Samsung SM-G986N，Android 13。应用与测试 APK 均使用 `adb install -r` 原位覆盖安装；未卸载、未执行 `pm clear`，未清理 App 数据。

| 测试类 | 结果 |
|---|---:|
| `FullScreenPlayerPresentationUiTest` | 9 tests，0 failures |
| `PopupMotionUiTest` | 3 tests，0 failures |
| `QueueScreenUiTest` | 9 tests，0 failures |
| `QueueRowMenuUiTest` | 4 tests，0 failures |
| 合计 | 25 tests，0 failures |

覆盖内容包括：两行结构、输出字段缺失、省略与 stale 数据抑制、六种状态文案、纯数字/未知音量、禁用 Slider、确认值/未知值无障碍语义及深浅主题切换、92%/296dp/520dp/16dp 几何约束、1.35 倍字体缩放、外部点击/Back 关闭与反向动画、入口再次点击切换，以及 Queue 共享 DAC 面板入口。

### 完整自动化回归

| 命令 | 结果 |
|---|---|
| `./gradlew --offline testDebugUnitTest --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false`（全量集成命令的一部分） | 91 suites / 527 tests，0 failures/errors/skips |
| `./gradlew --offline testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false` | exit 0；53 actionable tasks，10 executed；全量单测、Lint、Debug APK 均通过 |
| Lint | 0 errors，18 warnings，2 hints |
| `git diff --check` | exit 0 |

第一次完整回归出现过一次与本批无关的 `HomeDataControllerTest.libraryRevisionRefreshesSongIndexWhileHistoryRemainsCached` 断言失败（`songLoads` 期望 1、实际 0）。未修改该测试或其生产模块；该测试单独重跑通过，随后完整 527 项回归再次通过，0 failures/errors/skips。最终结果以第二次完整回归为准。

### APK 与数据保留

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，约 14 MB。
- applicationId：`io.github.qyxx2.mpdserver.remote`；内部签名证书 SHA-256：`36c589d3e947dee7f36204d1d19f4e96275159a6a7ff9cd058e5cdd448a2c89f`；`apksigner verify --print-certs` 通过。
- 实机通过 `adb install -r` 覆盖安装 App 与 instrumentation APK。没有卸载、清数据、改服务器地址或对服务器发送硬件音量写入。

## 4. 权威边界与未覆盖项

- MPD-Server 仍是唯一硬件音量状态权威；App 只显示 `SnapshotStore` 接受的服务端快照投影。
- 本批 Slider 始终 disabled；没有 local preview、PUT、retry、按键输入、Android 本机音量 API 或顶部提示浮窗。
- Queue 复用同一个 DAC 面板，不建立第二份弹窗或状态所有者。
- 本批未做 NAS 写入/恢复、播放与 Queue 状态前后写保护验收；这些留给 B7。

## 5. Batch 4 退出门

- [x] Player 和 Queue DAC 入口复用唯一紧凑两行弹窗，旧 Output Details dialog 已删除。
- [x] DAC 格式字段仅使用 fresh 观察；缺失字段省略，非 fresh 历史格式不冒充当前输出。
- [x] 音量值来自共享 SnapshotStore projection，只显示确认整数或 `—`，不带 `%`，UNKNOWN 不伪装为 0。
- [x] Slider 在 B4 保持禁用；无障碍语义不暴露伪造进度值。
- [x] 92% / 296dp min / 520dp max / 16dp safe margin、深浅主题、1.35x 字体缩放通过 UI 断言。
- [x] 复用现有 Dialog/PopupMotion host、trigger transform-origin 和反向关闭；外部点击/Back/re-entry 通过测试。
- [x] SM-G986N Android 13 connected instrumentation 25/25 通过。
- [x] 全量 527 单测、Lint、APK 构建和 `git diff --check` 通过。
- [ ] Slider 写入/读回、Activity 前台音量键、浮窗和 NAS 实机写保护：不属于 B4，留待 B5–B7。

**结论：** Batch 4 的代码与退出门完成。未 commit、未 push、未 merge；工作区保留 B0–B4 的未提交变更。


## 6. B4 完成后的 UI 调整 — 2026-11-04

根据后续视觉反馈，DAC 面板进一步对齐 Queue 页面每个条目三点菜单的弹窗方式。此节为当前最终实现：

- **参考对象是队列条目三点菜单，不是“清空待播”确认弹窗。** 清空待播弹窗未在本次调整中修改，也不作为 DAC 面板的视觉或交互样板。
- DAC 面板改为 attached `Popup`，使用与 `QueueRowMenu` 相同的 `AnchoredDropdownPositionProvider(48.dp)`、focusable / dismiss-on-Back / dismiss-on-outside-click 属性与 PopupMotionHost。完全移除 DAC 面板对 `MotionAlertDialog` / Dialog window 的依赖；`MotionAlertDialog` 本身恢复原状，普通确认弹窗行为不受本次调整影响。
- 表面与三点菜单统一为白色 94% alpha、18dp 圆角、7dp 阴影、0dp tonal elevation；没有 scrim，也没有覆盖全屏的透明拦截层。
- Popup 锚定 DAC 按钮上方，采用共享 dropdown provider 的横向对齐和空间回退策略；屏幕边缘和上方可用空间由 provider 处理。宽度仍按信息量自适应，最小 200dp、最大受可用窗口和 520dp 限制。
- 面板保持两行约 77dp；第二行音量轨道为 4dp 圆角填充条，数值仅使用服务端确认快照，未知显示 `—`。B4 仍只读且禁用，Slider 写入留待 B5。
- UI tests 使用设备级输入注入验证 Popup 外部点击关闭，并使用与 `QueueRowMenuUiTest` 一致的 Back 输入路径验证返回关闭；同时验证 Popup motion、锚点、字体缩放、信息宽度与音量语义。QueueRowMenu 本身的 4 项基准测试也一并通过。

### 修正后实机验证

设备：Samsung SM-G986N / Android 13。

| 测试类 | 结果 |
|---|---:|
| `FullScreenPlayerPresentationUiTest` | 9/9 |
| `PopupMotionUiTest` | 3/3 |
| `QueueScreenUiTest` | 9/9 |
| `QueueRowMenuUiTest` | 4/4 |
| 合计 | 25/25 |

最终命令：`./gradlew --offline connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.player.FullScreenPlayerPresentationUiTest,io.github.qyxx2.mpdserver.remote.ui.components.PopupMotionUiTest,io.github.qyxx2.mpdserver.remote.ui.queue.QueueScreenUiTest,io.github.qyxx2.mpdserver.remote.ui.queue.QueueRowMenuUiTest --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false` — exit 0，SM-G986N 上 25/25 通过。


最终全量回归：

- `./gradlew --offline testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false` — exit 0，53 actionable tasks / 10 executed。
- 单测：91 suites / 527 tests，0 failures / 0 errors / 0 skipped。
- Lint：0 errors、18 warnings、2 hints；Debug APK 构建成功。
- `apksigner verify --print-certs` 通过，签名证书 SHA-256 仍为 `36c589d3e947dee7f36204d1d19f4e96275159a6a7ff9cd058e5cdd448a2c89f`。
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` 实机安装成功；未卸载或清除 App 数据。
- 安装后手动打开 DAC 面板，WindowManager 确认其为 attached `APPLICATION_SUB_PANEL`。外部点击与 `KEYCODE_BACK` 均只关闭该 Popup，主 Activity 保持前台；无 Dialog window / scrim。未修改 server address，也未发送音量或播放/Queue 写请求。
- `git diff --check` 通过。未 commit、未 push、未 merge。
