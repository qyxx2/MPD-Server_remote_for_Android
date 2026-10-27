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
