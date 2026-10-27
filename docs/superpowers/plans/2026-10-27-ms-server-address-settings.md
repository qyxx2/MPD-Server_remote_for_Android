# MS 服务器地址设置入口实施计划

**Spec：** [MS 服务器地址设置入口设计](../specs/2026-10-27-ms-server-address-settings.md)

**范围：** 当前 `task4` 分支，Task 4 Batch 1 前的独立前置任务。直接在既定工作区实现，不创建 worktree、不调用代理；不提交/推送，除非用户另行要求。

## Task 1 — Origin 输入解析（TDD）

- 新增 `ServerOrigin.parseAddress(input)` 和标准化显示值；继续复用 `ServerOrigin.parse` 与 OkHttp `HttpUrl` 的 host/port 校验。
- 测试 IPv4、IPv6（括号/非括号）、域名、显式 scheme/端口、默认 HTTP/18000，以及空值、非法端口、路径、凭据、query/fragment。
- 先运行新测试确认缺少解析 API 的失败，再实现并复跑测试。

## Task 2 — 地址持久化与启动恢复（TDD）

- `UiPreferencesRepository` 暴露当前 server origin Flow 和写入方法，默认值为 `http://localhost:18000`。
- 测试默认值、保存值跨 Store/Repository 重建恢复。
- `MpdServerRemoteApplication` 使用现有 DataStore 恢复 origin；保存操作等待初始化完成，再持久化并通过唯一共享 `AppRuntime.changeOrigin` 应用。

## Task 3 — 设置页输入/验证（TDD）

- 在已有设置页增加服务器地址分组、单行输入、保存按钮、格式错误与保存状态反馈。
- 修改 `AppNavHost` 将共享 origin flow 和保存回调传入页面；保留现有主题、状态环设置，不显示额外迷你播放器。
- Compose 测试验证入口、无效值不写入、有效 IPv4/域名/IPv6 入口可以保存并持久化。

## Task 4 — 文档同步与整体验证

- 更新 Task 4 计划/矩阵，说明这个有限的连接地址前置任务是 Task 6 完整服务器设置之外的明确例外。
- 更新 Task 2 历史验收说明，并写本次验证报告。
- 执行 `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin`、`git diff --check`；保留测试结果和当前工作区原有改动，不把它们混入本任务变更。

## 退出条件

测试通过、设置保存和冷启动恢复代码接通、文档清晰区分“保存/应用地址”与“真实服务器在线”、没有新网络 owner/API，也没有真实播放/Queue 写入。

## 执行状态（2026-10-27）

- Parser 与 Preferences：已完成 RED→GREEN 定向单元测试。
- Settings UI、输入校验、DataStore 持久化、Application 启动恢复、共享 Runtime 切换：已实现。
- 最终验证：30 suites / 130 tests 全通过；`lintDebug`、`assembleDebug`、`compileDebugAndroidTestKotlin` 与 `git diff --check` 全通过。
- 定向 Compose UI instrumentation 已在 SM-G986N / Android 13 通过（1/1），验证入口、无效值不更改配置、有效 IPv4 持久化并应用到共享 Runtime。真实 MS HTTP/WebSocket 连接验证与人工生产 Activity 视觉验收仍未执行；详细证据见 [验证报告](../reports/2026-10-27-ms-server-address-settings-verification.md)。
