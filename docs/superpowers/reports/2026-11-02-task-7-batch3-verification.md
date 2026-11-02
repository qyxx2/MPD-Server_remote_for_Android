# Task 7 Batch 3 — 后台 Service、恢复和生命周期验证

日期：2026-11-02
分支：task7
工作区：/home/Gold/WorkSpace/MS_remote_for_Android

## 范围与实现

本批次只完成 Task 7 Batch 3。没有改动 Batch 4 的锁屏视觉/手工交互验收，也没有创建第二个 Runtime、Realtime/WebSocket owner、SnapshotStore、MutationCoordinator 或播放状态副本。

- 增加私有 started Service RemotePlaybackService，Manifest 设置 exported=false、stopWithTask=false。它不是本地音频输出服务；按 B0 平台决定使用普通 Service 和 START_STICKY，不声明 FOREGROUND_SERVICE、mediaPlayback type 或额外权限。
- Service 的四个标准系统回调只映射到现有 PlayerAction.RESUME/PAUSE/PREVIOUS/NEXT，并直接提交唯一 AppRuntime.playerCommands。Seek、Stop、快进、倒退等回调不产生额外命令。
- Service 等待 DataStore origin 恢复；未配置真实 origin 时停止，不使用 DEFAULT placeholder。之后等待当前进程获得 CONNECTED 且 controls-eligible 的 fresh snapshot，并要求 projection 有 confirmed identity，才把 callback 附加到 Application 已持有的 MediaSession。
- RemoteMediaSessionController 支持幂等 attach/detach。Service 尚未接管 callback 时保留已确认 metadata，但发布零动作；附加后恢复同一 canonical action projection。不会在 Service 中创建 MediaSession。
- Application 只根据现有 canonical projection 管理 Service。origin/current 撤销时撤销服务；重复启动复用同一个 Intent 和 Service；Activity 前台恢复时重试曾被系统拒绝的后台启动。通知权限失败与 Service 启动失败分别报告。
- Service 被系统回收后只请求 best-effort sticky 重建；没有持久化曲目/播放状态或自动重放 mutation。force-stop 和厂商后台策略不在应用可绕过范围内。

## 自动化验证

执行命令：

./gradlew --offline --no-daemon :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest

结果：BUILD SUCCESSFUL。

- Unit：84 suites / 474 tests / 0 failures / 0 errors / 0 skipped。
- Android instrumentation 源码编译通过；本批次新增的 3 项 API 33 connected instrumentation 全部通过（3/3）：系统 callback 映射、Service 启动/重复启动/Activity 重建/Task 移除/停止后重附着、Manifest 私有及无 FGS type。
- API 33 生命周期测试校验 AppRuntime、RealtimeController 和 MediaSession token 在重复启动及 Activity recreation 后均保持同一实例；系统 PlaybackState action flags 与现有 PlayerCommandGateway availability 一致。
- Lint：0 errors / 16 warnings / 4 hints。清除了本批次新增的 Intent stopService 警告；剩余警告位于既有依赖版本提示、UI 参数顺序、debug 网络配置及既有 KTX 用法。
- APK：io.github.qyxx2.mpdserver.remote，versionName 0.1.0，minSdk 26，target/compileSdk 36；签名证书 SHA-256 36c589d3e947dee7f36204d1d19f4e96275159a6a7ff9cd058e5cdd448a2c89f。

## 实机覆盖安装与状态保持

### SM-G986N / Android 13 / API 33

应用和 instrumentation APK 均通过 adb install -r 覆盖安装成功。

- firstInstallTime 保持 2026-10-24 18:47:26。
- DataStore ui_preferences.preferences_pb 安装前后 SHA-256 均为 9231e0497905a55fd74e239ce742734b6e470a6c0f05cf65a5b578d40805bf7b。
- 本批次 3 项 connected instrumentation：3/3 通过。

额外进行了一次非 force-stop 的进程回收观察：在 App 前台启动 Service 后退到后台执行 am kill，Android 创建了进程重建/Service sticky 重启记录；新进程的 MediaSession 在获得新状态后恢复 metadata，但系统动作暂时为 0。将 App 再次带回前台后，MainActivity.onStart 的重试使同一个 Service 重新附着，action flags 恢复为服务端能力投影（该设备观测值为 52）。因此这里验证的是“状态先以新快照恢复、无 callback 时 fail-closed、前台重入后恢复 callback”的路径；不把普通 Service 的 sticky 重启描述为可绕过 Android/厂商后台限制的无条件常驻保证。Batch 4 仍需在实际锁屏场景继续验收。

### V2307A / Android 16 / API 36

已尝试使用同一签名 APK 执行 adb install -r，安装器返回：

INSTALL_FAILED_ABORTED: User rejected permissions

设备安装器中止了更新。本次未清数据、未卸载、未更改通知权限，也未绕过安装确认。设备原有应用的 firstInstallTime 仍为 2026-10-30 19:59:20，lastUpdateTime 保持 2026-11-02 00:49:19，DataStore SHA-256 保持 c23dcf8dfecf19caf1daaa6b8bc63489293aa51730c588ef069d5e268f91ce7c。因此 API 36 本次只有 targetSdk 36 构建/Manifest 静态验证，未声称已完成 API 36 runtime 或 instrumentation 验证。

## 边界与后续

- 没有添加 FGS，因为应用控制的是 NAS 端播放，并不在 Android 设备本地输出音频；API 33 既有 MediaSession/MediaStyle 路径可在无 FGS 下工作。
- START_STICKY 仅是系统回收后的 best-effort 请求。若后台启动被系统拒绝，状态页显示独立失败原因；应用再次进入前台时重试。force-stop 不会被绕过。
- API 36 实机安装被系统中止，需在 Batch 4 或设备安装许可恢复后补做 API 36 runtime 验证。
- Batch 4 的真实锁屏/通知栏按钮手工验收、完整断网恢复回归仍未执行。

## 工作树说明

现有 Batch 2 代码和报告在开始时即处于未提交状态。本批次继续在用户指定的 task7 分支工作，没有重置或覆盖这些变更；由于 Batch 2 与 Batch 3 在共享文件中存在依赖，未制造一个无法独立重放的 Batch 3-only commit。当前改动保留在本地工作树，未提交、未合并、未推送。
