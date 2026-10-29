# Task 4 Batch 9 最终集成与退出评估

日期：2026-10-30
分支：task4
范围：只完成 Task 4 Batch 9。本次没有修改生产 Kotlin、Web、服务端/API、构建依赖或 CI 配置。

## 结论

Batch 9 自动化集成与合同审计完成。**Task 4 整体退出门仍未关闭**：真实播放/seek/Queue 写操作、实体 DAC 输出语义、目标设备 Cutout/系统通知栏手势和完整手工视觉验收均未执行，按合同标为 BLOCKED / NOT COVERED。没有用合成快照代替真实 MS 写操作，也没有为验收清空或修改用户 Queue。

## 自动化验证

| 验证项 | 实际结果 |
|---|---|
| Clean 命令 | BUILD SUCCESSFUL：clean testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin |
| Unit XML | 262 tests；0 failures；0 errors；0 skipped |
| 完整设备 Instrumentation XML | 64 tests；0 failures；0 errors；2 skipped |
| 设备 | Samsung SM-G986N，Android 13 |
| 独立 B9 跨屏测试 | Task4CrossScreenIntegrationUiTest 在本设备独立通过，并纳入最终完整 connected suite |
| 其他聚焦回归 | Player / Lyrics / Queue / Played 13 个测试类，50/50 通过 |
| Lint / Debug APK / AndroidTest Kotlin | Clean 构建成功 |
| 静态边界与 diff check | 通过 |

完整日志位于 .superpowers/sdd/2026-10-27-task-4-full-player-queue/：b9-clean-finalverify.log、b9-connected-finalverify.log、b9-cross-screen-green.log、b9-connected-safe-ui.log。opt-in 的 LiveMsMutationCoordinatorTest 与 LiveMsStatusRingUiTest 未传入 liveOrigin，按设计跳过；未触发真实服务写操作。测试 XML 已逐项读回并统计。

## B9 集成覆盖

新增的 Task4CrossScreenIntegrationUiTest 使用 loopback-only、隔离内存 AppRuntime 和合成 canonical snapshot，覆盖 Home mini-player → 全屏 Player → Lyrics ↔ Artwork → Queue → 行菜单 → 拖动取消 → Now Playing → Back。它断言同一 SnapshotStore / StateSnapshot 对象贯穿页面，current song、LRC cue 和 Queue occurrence identity 不串联；菜单取消和拖动取消没有产生任何 mutation activity。重复 song_id 的 Played/current/pending 使用不同 queue_item_id；fixture 不依赖用户媒体或服务端演示数据。

现有 Player、Lyrics、Queue、Played、navigation、Insets 与 Runtime instrumentation 均参与完整回归。AppNavigationUiTest 改为遵循已配置 canonical Runtime，不再假设服务端必然 offline/空曲目；每个测试捕获并恢复 server origin、theme 和 status-ring preferences，避免修改用户本地设置。

## 只读设备验证与数据保持

- Overlay：adb install -r app/build/outputs/apk/debug/app-debug.apk；结果：Performing Streamed Install
Success
- Debug APK SHA-256：55327a3cf5b7fd4099b113da529d5c42c75474dd1650f47993e7be1f6f65f971
- firstInstallTime 安装前后保持 2026-10-24 18:47:26。
- DataStore files/datastore/ui_preferences.preferences_pb 安装前后 SHA-256 均为 86f0fc14d00b146e90ec67736c0103fbeffe9a57ef5309fb2f44aa1460e1666c。
- 保存的 MS origin 为 http://[2409:8a28:dec:7c40:2e0:4cff:feff:3468]:17999；没有清除应用数据、卸载应用或直接篡改 DataStore 文件。
- 应用重启后只读打开活动/状态面板的 UI 摘要：

  连接与活动
  当前状态：状态正常
  连接：已连接
  快照：最新
  暂无操作活动

本轮没有点击播放/暂停、seek、下一首、指定 occurrence 播放、拖拽提交、删除、清空或歌单写操作。

## 合同与架构边界

T4-01 至 T4-17 已逐项登记自动化证据和未覆盖项。静态扫描没有发现 Room/SQLite、WebView、Media3/ExoPlayer、第二个 runtime/socket/client/coordinator owner；未新增 API、分页端点、依赖库、业务持久化快照或生产状态副本。HTTP/WS 与 mutation 仍由原有 AppRuntime / RuntimeReadApi / MutationCoordinator 统一所有；UI 暂态没有升级为 canonical state。审计记录：.superpowers/sdd/2026-10-27-task-4-full-player-queue/b9-boundary-audit.txt。

## 明确的 BLOCKED / NOT COVERED

1. 真实 MS 播放/暂停、上一首/下一首、seek、指定 occurrence 播放和操作后读回。
2. 真实 Queue reorder、Play Next/Add/Remove/Clear、未知操作恢复/重试，以及实际服务端错误注入。本轮没有真实写操作授权。
3. 实体 DAC/输出设备、bit-perfect 语义与输出硬件状态。
4. 目标机 Cutout/挖孔、播放器/Queue 手势与系统通知栏竞争；本轮没有做完整物理手势/截图验收。
5. 真实数据中带负 position 的 Played 历史蒙版外观：B8 的 fresh 服务端快照未包含 Played 项；同列表排序/滚动使用合成 canonical occurrence 验证。
6. 大字体、窄屏与多种系统显示配置的完整人工视觉检查。
7. 艺术家/专辑目标列表的端到端内容行为由 Task 5 承接；现阶段导航交接测试不代表 Task 5 完成。

因此，本报告只关闭 Batch 9 已可执行的自动化/边界核对项，不将 Task 4 总体标记为全部通过。Master Plan 的 Task 4 总体退出 checkbox 保持未勾选。
