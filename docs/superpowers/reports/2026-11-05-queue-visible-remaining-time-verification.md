# Queue 可见待播剩余时间 — Batch 验证报告

## 实现范围
- 分支：feature/queue-visible-remaining-time
- 基线：57c298759c1cd0a6a2f060a7f1f961a169102bcd
- 变更仅在 Android 客户端：一个共享 PlayerClock、可见 Queue 歌曲 duration 读取、纯计算/格式化和 Queue 工具栏标签。
- 服务端仓库、API 合同、数据库、依赖均未修改。

## 验证结果
1. TDD RED：仅添加测试时执行 ./gradlew testDebugUnitTest --tests 'io.github.qyxx2.mpdserver.remote.ui.queue.QueueRemainingTimeTest'，因 QueueRemainingTime 尚不存在而按预期编译失败。
2. TDD GREEN：添加最小实现后，同一 focused test 通过，4 个测试完成。
3. 全量测试：./gradlew testDebugUnitTest — BUILD SUCCESSFUL。
4. 最终综合验证：./gradlew testDebugUnitTest assembleDebug — BUILD SUCCESSFUL，43 项 actionable tasks，8 executed、35 up-to-date。
5. 代码检查：git diff --check 通过。
6. 实机覆盖安装：adb install -r app/build/outputs/apk/debug/app-debug.apk — Success；随后 monkey -p io.github.qyxx2.mpdserver.remote 1 启动，系统前台 Activity 为 io.github.qyxx2.mpdserver.remote/.MainActivity。
7. 数据保留：files/datastore/ui_preferences.preferences_pb 覆盖安装后仍存在；firstInstallTime 查询值为 2026-10-24 18:47:26。未卸载应用、未清理数据。

## 覆盖行为
- 当前歌曲用 SongDto.duration - PlayerClock.positionSeconds。
- 后续 current+next 可见条目按完整时长相加；重复 song occurrence 分别计数。
- 缺少任一可见 duration 或当前播放位置未确认时为 --:--；没有可见行时为 0:00。
- 只请求 visibleRows 的唯一 song_id，通过 RuntimeReadApi.getSong 复用 ResourceMemoryCache；不额外请求 artwork。
- 100ms 时钟只由 Queue 时间标签收集，避免整个队列列表随时钟 tick 重组。
- Player、Queue 独立路由、横向分页和过渡预览共用 AppNavHost 的单一时钟；播放与队列的权威状态仍来自服务端快照。

## 实机验证边界
本次实机验证确认 APK 覆盖安装和应用启动，并确认持久化文件/首次安装时间未变化。没有通过 instrumented UI 测试改变实机队列或播放状态，因此未声称在真机现场测得时间数值递减；时间计算和边界情况由上述单元测试覆盖。用户此前测试过的 MS 地址配置文件在覆盖安装后仍保留，本次未独立抓取 API 成功响应作为重新连接证据。
