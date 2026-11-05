# Task 9 Batch 6 — 前台全局音量键与提示浮窗验证报告

**日期：** 2026-11-05
**分支：** `feature/task-9-hardware-volume-control`
**工作区：** `/home/Gold/WorkSpace/MS_remote_for_Android`
**设备：** Samsung SM-G986N，Android 13
**范围：** 仅 Task 9 Batch 6；未执行 Batch 7。

## 1. 结果

<badge label="Batch 6 已完成" color="success" />

前台音量键通过唯一的 Activity 输入入口路由到现有 `HardwareVolumeCommandGateway`。应用自有 Popup 使用共享 Compose key modifier 转发，`MotionAlertDialog` 使用同一适配器；所有输入都进入同一命令 FIFO 与既有 `MutationCoordinator`，没有增加 HTTP owner、canonical state store、持久化 volume state、MediaSession、Service 或本地音量控制调用。

顶部提示条由全局瞬态 controller 驱动。它显示服务端确认值与尚未确认的键输入预览，数字只显示整数；每次有效 DOWN/repeat 更新内容并重新开始 2 秒隐藏计时。异步快照只更新内容，不重置计时，也不会在隐藏后重新打开提示。提示位置按实际 `StatusRingWindowGeometry`、挖孔/状态环边界和安全 Insets 计算，并保留 8dp 间距。

普通 Activity/页面的提示由 Activity 内容树中的非焦点 Compose Popup 承载。新出现的可聚焦应用 Popup 使 Activity 失焦时，Activity 调用 controller 的层级更新，使提示 Popup 在应用窗口内重新创建。独立 `MotionAlertDialog` 是单独窗口，因此在其 Dialog 根节点内通过 composition-local 使用同一状态、几何和展示投影，再创建同样的提示 Popup；不使用跨应用悬浮窗权限。

## 2. 实现边界

- `ForegroundHardwareVolumeKeyInputAdapter`：Activity 前台门控；Volume DOWN/repeat 每次产生一个方向步进，UP 消耗但不额外步进，非音量键与后台事件放行。
- `ForegroundHardwareVolumeOverlayController`：2 秒计时、即时内容更新、层级重排；readback 不延长计时。
- `HardwareVolumeCommandGateway`：增加瞬态 `previewVolumePercent` 投影；以当前 canonical SnapshotStore 值、活动 intent 和 FIFO 输入计算预览，服务端读回已确认当前目标时不重复叠加。
- `HardwareVolumeOverlayPresentation`：复用现有 hardware-volume capability policy；stale/unknown/unavailable 不会把待确认目标伪装为已确认值，null 不转换为 0。
- `HardwareVolumeOverlayPlacementCalculator`：复用 StatusRing placement 和真实窗口几何，横条在状态环/挖孔外缘下方留 8dp。
- `HardwareVolumeOverlayPopup`：非焦点、非触摸拦截的应用内 Compose Popup，纯整数显示，不申请 `SYSTEM_ALERT_WINDOW`。
- `HardwareVolumeKeyDispatch`：对共享 Popup/Dialog 宿主提供单一按键转发 modifier；没有在各页面分别注册 listener。
- `MotionAlertDialog`：同一 overlay environment 投影到 Dialog 自己的窗口内，以覆盖其 scrim 与 surface。

未触碰 MPD-Server、NAS、Task 7 MediaSession/Service 生命周期、播放队列和本机 AudioManager；本批未执行真实服务端音量写入。

## 3. 验证结果

### 单元测试

命令：

```bash
./gradlew --offline testDebugUnitTest --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false
```

最终全量测试 XML 汇总：**96 suites，545 tests，0 failures，0 errors，0 skipped**。其中新增/扩展覆盖：
- 按键 DOWN/repeat/UP、后台放行、非音量键放行；
- 两秒计时、重复键重新计时、readback 不延长 deadline、不在隐藏后重开；
- bring-to-front 只改变 window order、不重启隐藏计时；
- canonical 快照加 FIFO 输入预览、readback 防止重复计算；
- stale/unknown/null/unavailable 的数字展示与 pending 状态；
- 状态环/挖孔实际几何、8dp 间距与手动校准回退。

### Android 真机 instrumentation

命令：

```bash
./gradlew --offline connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.components.HardwareVolumeKeyDispatchUiTest,io.github.qyxx2.mpdserver.remote.ui.components.HardwareVolumePopupZOrderUiTest,io.github.qyxx2.mpdserver.remote.MainActivityHardwareVolumeKeyInputUiTest \
  --no-daemon --console=plain \
  -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false
```

结果：**SM-G986N / Android 13，5 tests，0 failures，0 errors，0 skipped**。

设备测试覆盖：
- 真实 Android Volume Up 事件进入 Activity 的共享适配器，且测试回调不触发服务器写入；
- Compose Dialog 文本输入焦点下 Volume key 经共享 modifier 转发；
- 可聚焦 Compose Popup 文本输入焦点下 Volume key 经共享 modifier 转发；
- 新开的可聚焦应用 Popup 与提示 Popup 的真实屏幕截图层级；层级重排后提示可见；
- MotionAlertDialog 的独立 Dialog 窗口中，Dialog-local 提示 Popup 覆盖 scrim 与 surface；
- 使用设备实际 display cutout/system bar insets 验证提示位置，不用固定设备坐标模拟相机位置。

### Lint、构建与差异检查

命令：

```bash
git diff --check
./gradlew --offline testDebugUnitTest lintDebug assembleDebug \
  --no-daemon --console=plain \
  -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false
```

结果：`git diff --check` exit 0；Gradle **BUILD SUCCESSFUL，exit 0**。Debug APK 已生成：

`app/build/outputs/apk/debug/app-debug.apk`

APK 大小：14,184,369 bytes。SHA-256：

`5267c22dd8ac264f8ce3127b05efdeb63b9fc9033c58c0f689ddc454daccbe1e`

Android instrumentation test sources 另以 `compileDebugAndroidTestKotlin` 编译通过。

## 4. 未覆盖与范围声明

- 本批没有对 NAS/MPD-Server 执行真实 PUT 音量写入，也没有改变 DAC 或播放状态；真实服务写入与恢复属于 Batch 7 的验收范围。
- Activity 级 dispatch 是页面无关入口；Dialog/Popup 使用共用窗口 modifier/环境，不通过逐页注册 listener。真机测试覆盖 Activity、Dialog、Popup 的输入路径与层级，未逐个页面人工点击所有导航入口。
- 本报告不关闭 Task 7 Batch 4 锁屏验收、Task 8 全局退出门或 Task 9 Batch 7 的 NAS/Android 集成验收。

## 5. Git 状态

改动保留在 `feature/task-9-hardware-volume-control` 指定工作区。未提交、未 push、未 merge；工作区包含本批预期的源码、单测、instrumentation 和文档更新。
