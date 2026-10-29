# Task 4 Batch 8：历史队列滚动修正报告

日期：2026-10-30

## 修正范围

仅修正 Android Task 4 Batch 8 的 Queue / Played 历史展示；未更改 Web、服务端 API、数据库、依赖或权威状态 owner。

## 原因

旧实现把 Played 放在独立的零高度 reveal 区，并用自定义 pointer/nested-scroll 处理拖动。历史 occurrence 并非普通 LazyColumn 行；手势才驱动显示和资源加载，且自定义处理影响原生 scroll/fling 的惯性。历史排序及 current 初始定位也不够稳定。

## 实现

- 新增 PlayedHistoryOrder：过滤 position < 0 的 occurrence，按 position 从旧到新显示，然后接 current(position 0) 与 pending。position -1 的最近播放项紧邻 current。历史行 key 加独立前缀，避免与当前/待播 occurrence key 冲突。
- Played、current 与 pending 现在是同一个 LazyColumn 的真实行。移除独立 reveal region、fractional reveal reducer 和自定义手势拦截；拖动、滚动与 fling 由 Compose LazyColumn 原生处理。
- 首次进入 Queue 时等待真实列表布局后将 current 锚定到视口顶部。短队列中 current/pending 行可能无法提供足够的反向滚动范围，因此在列表末尾使用透明、非 occurrence 的布局 spacer；它不参与 canonical Queue、行操作或服务端状态。
- Queue entry 即开始预热最近 20 个 Played song 的元数据；滚动进入更早历史时按 viewport 邻域扩展有界预取。fresh snapshot 仍为数据权威，不额外 GET Played；非 fresh 时沿用既有一次性 fallback 与 generation guard。滚动不提交播放或 Queue mutation。

## 验证结果

- 通过：./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin。
- 通过：PlayedRevealUiTest connected instrumentation，设备 SM-G986N / Android 13。覆盖 current 初始锚点、旧 reveal 区不存在、原生轻扫后的 fling、最近 Played 行紧邻 current、fresh snapshot 不额外 GET Played、元数据在手势前预热且滚动不重复加载、无 Queue/playback mutation。
- 通过：PlayedHistoryOrder 与 PlayedReadGeneration 相关单元测试；git diff --check 通过。
- 已对 app/build/outputs/apk/debug/app-debug.apk 执行 adb install -r，返回 Success。firstInstallTime 保持 2026-10-24 18:47:26；DataStore 文件 ui_preferences.preferences_pb 的 SHA-256 安装前后均为 86f0fc14d00b146e90ec67736c0103fbeffe9a57ef5309fb2f44aa1460e1666c，未清除应用数据。

## 验收边界

UI instrumentation 使用合成 canonical Queue snapshot，避免改动设备当前真实播放队列；真实服务端历史外观未作人工逐项验收。没有执行真实播放或 Queue 写入。本次没有创建 Git commit。
