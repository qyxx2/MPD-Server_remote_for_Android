# Task 7 Batch 1 验证报告

Date: 2026-11-01
Branch: `task7`
Scope: Batch 1 only — native MediaSession and canonical playback projection.

## 1. 交付范围

- 新增 `MediaSessionProjection`：纯状态投影，只读取同一 `SnapshotStoreState` 经既有 `PlayerFacts` / `PlayerPresentation` 得到的确认事实。
- 新增 `MediaMetadataMapper`：metadata 的 media ID 使用当前 `queue_item_id`，不是 `song_id`；title、artist、album 与 duration 按有效值映射，不伪造缺失字段。
- 新增 `RemoteMediaSessionController`：原生 Android `MediaSession` 的进程级 owner。只在 fresh、connected、当前 generation、current occurrence 唯一且绑定、playback/actual observation 确认 PLAYING/PAUSED 时发布。
- `MpdServerRemoteApplication` 持有唯一 controller，并注入已有 Application `AppRuntime`；未新增 Runtime、SnapshotStore、RealtimeController、MutationCoordinator、HTTP client、WebSocket、数据库、Gradle 依赖、Manifest 权限或 Service。
- `PlayerCommandGateway.availability()` 提供同一锁内的 snapshot 与 pause/resume/previous/next/seek 能力快照。MediaSession 的动作 flags 与现有 PlayerPresentation controls 相交，并受同一 PENDING/SYNCING/UNKNOWN/origin transition 锁约束。

## 2. 状态与动作语义验证

Projection 单测覆盖：

- fresh PLAYING / PAUSED；actual STOPPED；业务 playback.state=STOPPED 但 actual observation 仍确认 PLAYING。
- stale snapshot、stale observation、disconnect、connection generation 变化；只有本进程此前发布过的同 origin/epoch metadata 才可保留，保留期间 PlaybackState 为 NONE 且 actions=0。
- 首次 stale/unconfirmed 不创建 session；缺失或 ambiguous current、显式 Stop generation 增长时撤销。
- 实际 STOPPED 本身不冒充显式 Stop；清理 pending Queue 后仍存在的 current occurrence 继续发布，不把清空待播误作 Stop。
- origin/epoch 变化；同批到达的新鲜新曲目可直接替换旧媒体身份，旧 origin/epoch metadata 不会串入新会话。
- occurrence/song 绑定、重复歌曲不同 occurrence、song DTO 缺失/非法、字段空白、duration 无效。
- current 前后 Queue 边界、Pause/Resume、单边 Previous/Next、缺失 Resume target、player command lock。

RED-first：先添加测试并确认 projection 类型缺失时测试编译失败，再实现投影与 controller。最终测试结果见下节。

## 3. 自动化验证

最终执行：

```sh
./gradlew --offline --no-daemon clean testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin assembleDebugAndroidTest
./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug
```

结果：

- 最终完整 unit suite：81 suites / 466 tests / 0 failures / 0 errors / 0 skipped。
- 新增 `MediaSessionProjectionTest`：20 tests，全部通过。
- 扩展 `PlayerCommandGatewayTest`：15 tests，全部通过。
- Clean build 完成；Debug APK 与 Android instrumentation APK 组装成功，Android-test Kotlin 编译成功。
- 最终复核 build、unit tests、Lint、Debug APK 均成功。
- Lint：16 warnings、4 hints、0 errors。未新增 Task 7 Lint error。
- `git diff --check` 通过。

日志：
- `.superpowers/sdd/2026-11-01-task-7-system-media-session/b1-projection-red.log`
- `.superpowers/sdd/2026-11-01-task-7-system-media-session/b1-full-verification.log`
- `.superpowers/sdd/2026-11-01-task-7-system-media-session/b1-final-verification-rerun.log`
- `.superpowers/sdd/2026-11-01-task-7-system-media-session/b1-final-acceptance.log`

## 4. API 33 真机覆盖安装与 Session 验证

设备：Samsung SM-G986N / Android 13 / API 33。应用包：`io.github.qyxx2.mpdserver.remote`。

覆盖安装：

- `adb install -r app/build/outputs/apk/debug/app-debug.apk`：Success。
- `firstInstallTime` 保持 `2026-10-24 18:47:26`；`lastUpdateTime` 更新为 `2026-11-01 20:44:00`。
- applicationId、versionCode=1、versionName=0.1.0、minSdk=26、targetSdk=36 保持不变。
- 签名证书 SHA-256：`36c589d3e947dee7f36204d1d19f4e96275159a6a7ff9cd058e5cdd448a2c89f`，与 Task 0/B0 统一签名一致。
- DataStore `files/datastore/ui_preferences.preferences_pb` SHA-256 覆盖安装前后均为 `9231e0497905a55fd74e239ce742734b6e470a6c0f05cf65a5b578d40805bf7b`；未卸载、未清除数据。
- 启动后应用进程正常存活；crash buffer 未发现该包的 FATAL EXCEPTION。

原生 Session：

- `dumpsys media_session` 检测到唯一 `MPD-Server Remote` session，owner package 为本应用，active=true，flags=3。
- SystemUI 已注册为 controller。
- 实时 Session 显示服务端当前确认曲目 metadata（真机采样：`你爱我像谁` / `张卫健` / `我 (不: ) 是张卫健`），PlaybackState 为 PAUSED，position 8469 ms，actions=52（当前 PAUSED 对应 PLAY/Resume + Previous + Next；Pause 不会同时发布。flags 由服务端当前 Queue 与 PlayerPresentation 推导）。
- 该验证确认 API 33 真机的 AppRuntime → canonical projection → 原生 MediaSession 链路已接通。

## 5. 本批边界与未声称完成的内容

本次只完成 Batch 1。没有接入 MediaStyle 通知、通知权限/渠道、封面读取、媒体 action callback、后台 Service、FGS 或进程死亡恢复。当前真机验证证明 MediaSession 已发布并被 SystemUI 注册，不代表通知卡片和系统按钮回调已端到端完成；这些仍属于后续 Batch。

平台范围按用户确认定义为 Android 13（API 33）及以上；API 26–32 不属于 Task 7 系统媒体功能保证范围，应用本身的 minSdk=26 保持不变。API 34+ 的通知/后台服务限制将在后续实际接入相应能力时验证，不将 API 33 结果扩展描述为所有 Android 版本或厂商行为均已测试。

## 6. 交付纪律

- 仅完成 Batch 1；未开始 Batch 2/3/4。
- 未推送远端、未合并其他分支。
- 未查询或调用其他编程代理。
- 保留现有 MS 地址、主题及本地偏好数据。
