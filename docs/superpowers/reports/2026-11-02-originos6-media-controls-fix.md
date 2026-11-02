# OriginOS 6 / Android 16 媒体通知控件修复

日期：2026-11-02
分支：task7
设备：vivo V2307A / OriginOS 6 / Android 16 / API 36
包名：io.github.qyxx2.mpdserver.remote

## 问题复现与根因

此前通知中心能看到歌曲标题和艺术家，但整张通知没有上一首、播放/暂停、下一首按钮。

设备侧证据：

- `dumpsys media_session` 显示 MPD-Server Remote Session 为 active，拥有当前歌曲 metadata，PlaybackState 为 PAUSED，action flags 为 52（PLAY、SKIP_TO_PREVIOUS、SKIP_TO_NEXT）。
- `dumpsys notification --noredact` 显示原通知有 MediaStyle 和 MediaSession token，但 NotificationRecord 中没有 actions，统计数据 `numWithActions=0`。
- 通知权限已授权，媒体通知 channel importance=3，通知成功发布。
- 源码原先只设置 `Notification.MediaStyle().setMediaSession(token)`，没有向 Notification.Builder 添加显式 `Notification.Action`，也没有声明 compact action index。

因此不是 OriginOS 的通知权限、MediaSession 建立、服务器连接或 Service 启动问题。缺少通知对象自己的显式 transport actions，是系统状态栏仅显示普通媒体信息通知的直接原因。

## 修复内容

1. `MediaNotificationFactory` 从同一 canonical `MediaSessionProjection.actions` 生成显式上一首、播放/暂停、下一首 Notification.Action；当前不可用的动作不展示，播放和暂停只展示其中一个。
2. 每个通知按钮用显式 Broadcast PendingIntent 发送媒体按键事件，交由内部 `RemoteMediaButtonReceiver` 将事件分发到对应的原生 MediaSession callback。最终仍调用现有 `RemotePlaybackMediaSessionCallback` 和唯一 `PlayerCommandGateway`，没有另建网络请求、命令分发器或播放状态副本。
3. 将当前可用 actions 的 index 全部设置为 MediaStyle compact actions，确保通知折叠/紧凑视图也能显示控件。
4. 新增简洁的上一首、播放、暂停、下一首 vector 图标；Manifest 仅增加非导出 receiver，没有新增权限或 FGS 类型。

## 验证

### 自动化构建

执行：

```sh
./gradlew --offline --no-daemon :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

结果：BUILD SUCCESSFUL。Lint：0 errors / 16 warnings；warning 来自既有依赖/UI/配置项，没有命中本次新增的通知控件实现。

### API 36 真机

- Debug APK 和 Android-test APK 均通过 `adb install -r` 安装成功。
- 新增 `MediaNotificationActionsDeviceTest` 在 V2307A / API 36 上 3/3 通过：canonical actions 与 compact actions 对齐；不可用时不显示动作；广播接收器能把四种 transport key event 分发到原生 MediaSession callback。
- 启动 App 后重新检查系统真实 NotificationRecord：`actions=3`，依次为“上一首”“播放”“下一首”；`android.template=Notification$MediaStyle`；`android.mediaSession` token 存在；`android.compactActions=[0,1,2]`。
- OriginOS 6 通知中心截图已直接确认媒体通知中显示上一首、播放、下一首三个图标按钮。
- 同时确认 MediaSession 仍为 active、PAUSED，action flags=52；通知动作的能力来源与会话一致。

### 用户数据与安装

- `versionName=0.1.0`，targetSdk=36。
- firstInstallTime 保持 `2026-10-30 19:59:20`，为覆盖安装。
- DataStore SHA-256 安装前后相同：`3dfdb28dea13d01a130653e359abd7629230a73cd116437df180c74668879139`。
- `git diff --check` 通过。

## 验收边界

本次已在 OriginOS 6 实机确认通知中心实际显示三个媒体控件，并通过 instrumentation 验证 PendingIntent 接入原生 MediaSession callback 的映射。没有在真实 NAS 播放期间点击上一首/下一首/播放按钮，避免修改用户当前播放状态。因此未将真实服务器写操作与最终歌曲变化标记为已实测。

此修复解决通知按钮缺失问题；Task 7 Batch 4 其余锁屏、断网恢复、真实命令回归等整体验收仍按原计划执行。没有提交、合并或推送。
