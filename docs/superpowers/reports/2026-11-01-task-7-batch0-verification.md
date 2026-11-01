# Task 7 Batch 0 验证报告

Date: 2026-11-01
Branch: `task7`
Base: `1b01447adf55b2922a75f193666d54c6e21a05cb`

## 1. 交付范围

已完成 Runtime 级 player command gateway，并将现有 Player UI 的普通播放操作委托到同一个 Gateway。系统媒体回调后续可直接复用 `AppRuntime.playerCommands`，无需创建第二个写操作入口。

- 新增 `runtime/PlayerAction.kt` 作为 Runtime canonical 动作类型，UI 包保留 `PlayerAction` typealias，既有 UI 调用点无需变更。
- 新增 `runtime/PlayerControlIdentity.kt`，统一 Gateway 与 PlayerActions 的 control identity 解析，避免播放目标身份规则分叉。
- 新增 `runtime/PlayerCommandGateway.kt`。
- `AppRuntime.changeOrigin()` 与 Gateway 共用同一把 JVM monitor；切换窗口先关掉命令门，再变更 MutationCoordinator origin，待 RealtimeController 完成 origin/generation 更新后才重新开放。保证命令要么在切换前完整提交到旧 origin，要么被拒绝，不能以旧 snapshot 向新 origin 写入。
- `AppRuntime` 提供单一 lazy `playerCommands` owner，复用既有 SnapshotStore、RealtimeController 和 MutationCoordinator。
- `PlayerActions(runtime)` 使用 `runtime.playerCommands`；PAUSE、RESUME、PREVIOUS、NEXT、SEEK 都经过同一命令入口。
- PlayerActions 继续持有 seek draft、preview、进度展示及回执 UI 瞬态；没有把 UI 草稿状态迁移到 Runtime。
- 新增 `PlayerCommandGatewayTest.kt`，验证系统/应用调用共同锁、能力与 target 映射、stale/UNKNOWN、Queue 边界、connection generation 变化、origin transition window 的 shared-lock 原子性和并发回调。
- 未新增 AppRuntime、SnapshotStore、RealtimeController、MutationCoordinator、OkHttpClient、WebSocket、API 或业务持久化。

## 2. TDD 与自动化验证

Gateway 测试先行：实现前测试因 `PlayerCommandGateway` 尚不存在而编译失败（RED）；完成 Gateway 和 PlayerActions 接入后转 GREEN。

最终 focused 回归命令：

```sh
./gradlew --offline --no-daemon :app:testDebugUnitTest \
  --tests io.github.qyxx2.mpdserver.remote.runtime.PlayerCommandGatewayTest \
  --tests io.github.qyxx2.mpdserver.remote.ui.player.PlayerActionsTest \
  --tests io.github.qyxx2.mpdserver.remote.runtime.MutationCoordinatorTest
```

结果：BUILD SUCCESSFUL。Gateway、既有 PlayerActions 与 MutationCoordinator 测试均通过；最后一次 PlayerActions 小幅重复判断清理后再次运行 focused suite，仍通过。

最终完整验证命令（在 shared control identity 和 origin-switch shared monitor/test 均完成后执行）：

```sh
./gradlew --offline --no-daemon testDebugUnitTest lintDebug \
  assembleDebug compileDebugAndroidTestKotlin assembleDebugAndroidTest
```

结果：最终 BUILD SUCCESSFUL；80 个单测 suite，共 445 tests、0 failures、0 errors、0 skipped。Lint 有 16 条 Warning、4 条 Hint，无 Error；Debug APK、Android-test Kotlin 编译及 instrumentation APK 组装通过。Lint 有 16 条 Warning、4 条 Hint，无 Error；Debug APK、Android-test Kotlin 编译及 instrumentation APK 组装通过。

## 3. Android 平台探针与技术选择

实机：SM-G986N / Android 13 / API 33；安装包 targetSdk=36。

临时 instrumentation probe 已执行并在证据收集后删除，没有进入正式源码。探针使用平台 `android.media.session.MediaSession` 与平台 `android.app.Notification.MediaStyle`，并将原生 `MediaSession.Token` 绑定到通知。

API 33 设备结果：

- `dumpsys media_session` 显示 `task7-platform-probe` session active；SystemUI 是 session controller，系统读到了探针 title/artist metadata。
- `POST_NOTIFICATIONS` 权限为 denied（`checkSelfPermission=-1`），`NotificationManager.areNotificationsEnabled()=false`。
- 在上述 denied 状态下，使用同一个 MediaSession token 构造的 media-style notification 仍出现在 `NotificationManager.activeNotifications`。
- 探针 channel importance=2（LOW），sound=null、vibration disabled；测试结束时撤下探针通知并删除 channel。
- instrumentation test 在 SM-G986N / API 33 运行通过。

技术决策：

1. 采用平台 `MediaSession` + 平台 `Notification.MediaStyle`，不采用 `NotificationCompat.MediaStyle`。API 33 已证明原生 token 与原生 style 配对；避免引入 Compat token 适配/依赖。
2. 本批不新增 `POST_NOTIFICATIONS`、foreground-service permission、Service、service type 或 Gradle 依赖。
3. 不因 NAS 远端播放就声明 `mediaPlayback` FGS：Android App 不进行本地音频解码或音频输出。也不在缺少策略证据时用 `connectedDevice` 代替。
4. B1 负责创建 AppRuntime 级唯一 Session owner；B3 再按真实后台恢复需求决定 Service 生命周期。当前 API33 探针证明 Session/MediaStyle 可以在无 FGS 声明下建立和发布，不证明进程被系统回收后仍能存活。

## 4. 未覆盖范围与 B0 状态

当前环境只有 API 33 实机；本机有 android-36 SDK platform，但没有 API 26 或 API 36 runtime/AVD。已通过 android-36.jar 静态核对 MediaSession、Notification.MediaStyle、POST_NOTIFICATIONS 与相关 FGS 常量存在。静态 API 存在不等于 runtime 限制已验证。

尚未验证：
- API 26 最低系统版本的实际 Session/MediaStyle 行为。
- API 36 runtime 下的权限/FGS 启动限制。
- API 33 通知 permission granted 与 user-disabled channel 组合。
- 不同厂商锁屏卡片的视觉表现与服务进程回收恢复。

因此，B0 的 Runtime gateway 实现、应用内命令接入、自动化验证和 API 33 原生媒体平台选择已完成；**API 26/API 36 runtime 平台覆盖仍是 B0 剩余验收项**。不进入 Batch 1，也不将 API 33 单机探针描述为 API 26–36 全平台通过。

## 5. 交付纪律

- 未修改 Manifest、Gradle catalog、applicationId、签名身份或 DataStore schema。
- 未增加第二个 Runtime、MutationCoordinator、HTTP client 或 WebSocket。
- 临时 platform probe 源文件已删除。
- 未推送远端、未合并其他分支。


## 6. 最终安装与启动复核

最终 APK：app/build/outputs/apk/debug/app-debug.apk
SHA-256：0f35fdb523a927ec819c4428b64fcfb7619c59c950b3031c04859c8aa6034222

- 在 SM-G986N / API 33 上执行 adb install -r，结果 Success。
- firstInstallTime 仍为 2026-10-24 18:47:26；lastUpdateTime 更新为 2026-11-01 17:27:38，证明是覆盖升级而非卸载重装。
- DataStore 偏好文件 files/datastore/ui_preferences.preferences_pb 的 SHA-256 覆盖安装前后均为 9231e0497905a55fd74e239ce742734b6e470a6c0f05cf65a5b578d40805bf7b，现有服务器地址/外观设置没有被重置。
- 签名证书 SHA-256 仍为 36c589d3e947dee7f36204d1d19f4e96275159a6a7ff9cd058e5cdd448a2c89f；packageId=io.github.qyxx2.mpdserver.remote、targetSdk=36、versionName=0.1.0 保持不变。
- 通过 launcher intent 启动后进程 io.github.qyxx2.mpdserver.remote 正常存活；crash buffer 没有该包的 FATAL EXCEPTION。
