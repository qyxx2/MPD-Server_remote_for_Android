# Task 7 Batch 2 验证报告：MediaStyle 通知、封面与权限降级

日期：2026-11-02
分支：task7
工作区：/home/Gold/WorkSpace/MS_remote_for_Android
范围：仅 Batch 2；没有开始 B3 Service/媒体动作回调，也没有开始 B4 全面端到端验收。

## 实施内容

1. MpdServerRemoteApplication 新增唯一 app-scoped MediaNotificationController。控制器只订阅现有 RemoteMediaSessionController.projection，使用同一个原生 MediaSession.Token 和 canonical Session metadata/action state；没有新建 Player、AppRuntime、网络 client、WebSocket、MutationCoordinator 或播放状态副本。
2. 新建 MediaNotificationFactory，媒体 channel ID 固定为 mpd_server_remote_media，importance LOW、无声音/振动。若 channel 已存在，原样复用其用户配置，不覆盖 importance，不创建新 ID。
3. 通知内容点击使用 PackageManager.getLaunchIntentForPackage()，不增加播放器 deeplink 或新的导航 route。没有封面时用应用图标作为稳定 large-icon 占位；文本 metadata 先发布。
4. 封面通过 runtime.reads.getArtwork(songId) 获取，复用 RuntimeReadApi / ResourceMemoryCache。后台解码限制最大边 512px；回写前重新检查 origin、connection generation、snapshot generation、epoch、library revision、explicit Stop generation、song ID、queue occurrence 和 artwork ID。身份变化时立即清空旧 Bitmap，避免切歌后沿用上一首封面。
5. 新增 MediaControlAvailability：分开表示 API 级通知权限、应用级通知开关、channel importance 和发布错误。未获权限时不主动弹窗、不循环请求；现有「设置 → 状态」展示具体降级原因，并提供 Android channel/app notification settings 入口。
6. Manifest 仅声明 POST_NOTIFICATIONS。没有增加通知库依赖、Service、FGS 权限或类型；B3 才负责媒体动作回调和后台 Service 生命周期。

## 自动化验证

### TDD 与单元测试

- 新增 MediaControlAvailabilityTest、MediaArtworkIdentityGuardTest，先加入不存在的实现类型引用，确认编译 RED，再实现生产代码。
- Artwork guard 覆盖 current fresh、origin 改变、generation 改变、epoch 改变、library revision 改变、explicit Stop generation 改变、artwork ID 改变、song/queue occurrence 改变、stale 与 ambiguous current。
- RuntimeReadApiTest / ResourceMemoryCacheTest 的既有测试继续覆盖 artwork 404、missing reference/read error 不缓存为成功、single-flight/cache identity、supported MIME 与 PNG/JPEG/GIF/WebP 签名、非法 MIME/bytes 拒绝、重连后的身份重验证。
- 最终 unit test XML 汇总：83 suites / 472 tests / 0 failures / 0 errors / 0 skipped。

### 构建与 Lint

执行 clean 验证：

./gradlew --offline --no-daemon clean testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin assembleDebugAndroidTest

结果：BUILD SUCCESSFUL；Debug APK、Android-test APK、instrumentation Kotlin compile/assemble 通过。Lint：0 errors / 16 warnings / 4 hints。其后新增了 occurrence mismatch 的单元测试断言，再次执行 :app:testDebugUnitTest 成功，测试总量仍为 83 suites / 472 tests。

### API 33 instrumentation

在 SM-G986N / Android 13 / API 33 执行：

adb shell am instrument -w -e class io.github.qyxx2.mpdserver.remote.media.MediaNotificationFactoryDeviceTest,io.github.qyxx2.mpdserver.remote.media.StatusSettingsMediaAvailabilityUiTest io.github.qyxx2.mpdserver.remote.test/androidx.test.runner.AndroidJUnitRunner

结果：OK (5 tests)。覆盖 channel 创建/稳定 ID/LOW/no sound/no vibration/幂等、Notification.MediaStyle 同一 Session token、既有 launcher PendingIntent、无图标占位、真实平台权限状态读取，以及「设置 → 状态」降级说明 UI。

## API 33 真机覆盖安装与运行证据

设备：SM-G986N / Android 13 / API 33。

- Debug APK 与 Android-test APK 使用 adb install -r 覆盖安装成功，没有卸载或清除数据。
- firstInstallTime 保持 2026-10-24 18:47:26。
- DataStore files/datastore/ui_preferences.preferences_pb SHA-256 安装前后保持：9231e0497905a55fd74e239ce742734b6e470a6c0f05cf65a5b578d40805bf7b。
- 应用签名证书 SHA-256：36c589d3e947dee7f36204d1d19f4e96275159a6a7ff9cd058e5cdd448a2c89f，与原安装一致。
- 保持设备原有 POST_NOTIFICATIONS denied（POST_NOTIFICATION=ignore）状态进行运行验证。App 启动后 dumpsys media_session 显示本应用 active MediaSession，SystemUI 为 controller；Session 显示 canonical 曲目 metadata、PAUSED 状态和当前 action flags。
- dumpsys notification --noredact 显示 Notification ID 7317 的 active Notification.MediaStyle record，channel 为 mpd_server_remote_media，metadata 与 Session 相同，MediaSession token 存在，largeIcon 为 84x84 Bitmap。通知权限 denied 没有导致 Session 被撤销或 App 崩溃。
- 临时执行 pm grant 后，真实权限状态读取 instrumentation 通过；验证后恢复了设备原有 denied/app-op ignore 状态。
- 直接启动与实现一致的 ACTION_CHANNEL_NOTIFICATION_SETTINGS intent，Android Settings 成功打开本应用媒体通知 channel 页面。
- 安装后重新启动 App，进程正常；检查 logcat 未发现本次启动产生的 FATAL EXCEPTION。

上述证据证明系统已注册 Session、通知记录与封面资源可进入 NotificationManager；不等同于完整锁屏外观和按钮点击人工验收，该项仍属于后续 B3/B4。

## 未覆盖与阻塞

1. API 36 V2307A 的覆盖安装尝试返回 INSTALL_FAILED_ABORTED: User rejected permissions。未能安装本次新 APK；API 36 设备原有版本、firstInstallTime 与 DataStore 数据没有被更新或清除。因此本报告不宣称 API 36 runtime 已通过。
2. channel importance=NONE 的降级状态由 MediaControlAvailabilityTest 覆盖，代码对既有 channel 原样返回且不会创建新 ID；本次没有在用户真机上更改其通知 channel 偏好。
3. 未做 API 36 FGS 测试；本批没有新增 Service/FGS。系统媒体动作按钮点击与 callback dispatch 留在 B3。
4. 未进行系统锁屏界面的完整视觉/厂商差异人工验收，也未测试 Activity/Service 重建、系统回收和 force-stop 后恢复；这些属于 B3/B4。

## 结论

Batch 2 已完成代码实现与当前批次自动化/API 33 真机验证。单一服务端状态权威、Session token 和 action flags 沿用 B1，封面沿用 Runtime cache 与身份校验，通知权限降级可见且没有权限弹窗。API 36 安装被系统阻止的事实已记录，不伪称跨版本通过。没有本地提交、推送或合并。
