# Task 2：网络协议、共享状态 Store 与操作协调器 Implementation Plan

> **For agentic workers:** 使用 `superpowers:executing-plans` 按 Batch 实施；每个 Batch 测试先行，完成独立验证与提交。不得开始 Task 3。

**Goal:** 为原生 Android 客户端建立严格、可恢复且可测试的服务端通信、权威快照状态和统一 mutation 生命周期基础。

**Architecture:** 单一 `app` 模块中分层实现 origin/HTTP/WebSocket transport、严格 DTO/wire 解码、薄 repositories、一个 snapshot Store、一个 realtime controller 和一个 mutation coordinator。所有业务事实来自 MPD-Server；共享 runtime owner 长于页面生命周期。

**Tech Stack:** Kotlin、现有 Gradle Wrapper 8.14.6、AGP 8.10.1、Kotlin/Compose plugin 2.0.21、现有 Compose BOM 2025.06.00；复用/核验 OkHttp、kotlinx.serialization、Coroutines/StateFlow 依赖，未经合同更新不得升级版本或增加无关库。

**Spec:** `docs/superpowers/specs/2026-10-24-task-2-network-state-coordinator.md`；同时受产品与工程总规格、后端 API 合同、Web 行为继承合同、客户端边界与架构合同和主计划约束。

## Global Constraints

- `applicationId`/`namespace` 固定为 `io.github.qyxx2.mpdserver.remote`；minSdk 26；原生 Compose，不使用 WebView。
- MPD-Server 是唯一业务状态来源；不新增 Room/SQLite 业务数据库，不复制 Queue/AutoPlay/History/Collection/Playlist/Favorites/reconciliation，不直连 MPD TCP。
- 全应用只有一个 canonical snapshot Store、一个 realtime controller、一个 mutation coordinator；页面/ViewModel 不得持有独立 owner。
- HTTP/WS 从同一个配置 origin 派生；默认服务端端口 18000，不硬编码 IP，不新增登录 API 或服务端 endpoint。
- 严格区分 missing/null/unknown、业务失败、传输结果 unknown、204 和后续读取失败；不得伪造 canonical state 或用新 key 自动重放 unknown mutation。
- 旧 epoch/sequence/revision/连接世代响应不得覆盖当前状态；invalidate 是完整权威重读信号，不是局部 patch。
- 不持久化业务快照或跨进程 mutation journal；进程重启重新取完整快照，不自动重放未确认写入。
- 只在 `/home/Gold/WorkSpace/MS_remote_for_Android` 实施；本地 Gradle 构建/测试/Lint；不配置 CI/远端构建，不修改服务端或 Web。

## Review Focus

- 旧 epoch/sequence、旧 WebSocket 和迟到 GET 覆盖新快照：归 Batch 3/4，必须有确定性乱序测试。
- WebSocket 首帧不完整或版本不兼容仍开放写操作：归 Batch 2/4，测试在完整 snapshot 前 controls eligibility 为 false。
- 同一 intent 的重试更换 key 或改变 payload，导致重复执行：归 Batch 5，测试完整 key/method/path/payload 保持。
- HTTP 204、非 JSON 错误或写后读取失败被当成业务失败/伪造状态：归 Batch 1/5，覆盖明确错误与 unknown 两条路径。
- 服务地址变更或进程重启后旧 mutation 被自动迁移/重放：归 Batch 4/5，测试端点/进程边界。

---

## Batch 0：上游合同复核与精确文件/接口映射

**目的：** 在任何实现代码之前，把合同矩阵映射到当前上游代码、当前 Android 工程和测试 seam。

**Files:**
- Review: `docs/superpowers/specs/2026-10-24-task-2-network-state-coordinator.md`
- Review: 四份既有权威规格和 `docs/superpowers/plans/2026-10-24-android-client-master-plan.md`
- Create/Update: 本计划中记录的实际 package/file map、fixture 来源和基线 HEAD（如需单独证据文件则放 `.superpowers/sdd/2026-10-24-task-2-network-state-coordinator/progress.md`）

- [ ] 重新核对 `MPD-Server/main` 实际 HEAD；若变化，比较 realtime/API schemas、Web services/runtime、相关测试，并更新受影响合同。
- [ ] 盘点现有 Gradle 依赖、测试配置和 `app/src`，确认 OkHttp、Serialization、Coroutines 是否已提供及其版本；不得假设依赖已经存在。
- [ ] 冻结包路径、类型名称、owner 创建位置、dispatcher/clock/test transport seam 与 DTO fixture 来源。
- [ ] 将每个合同矩阵行映射到具体 Batch、测试名、人工检查和退出证据。
- [ ] 运行 Task 1 基线 `./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug` 并保存退出码/摘要，确认起点可复现。

**退出条件：** 无未决 wire/DTO 差异；每个后续 Batch 的接口依赖与测试 seam 可定位。若发现服务端合同冲突，暂停相关 Batch，先修正规格。

## Batch 1：Origin、HTTP transport、DTO/wire 与错误解码

**目的：** 建立最小网络传输与明确的解码语义，不创建实时状态 owner 或产品 UI。

**Files:**
- Create: origin/config URL resolver、HTTP transport/API result types、wire DTO 与 serializer 配置、统一错误模型
- Test: origin/path/scheme、DTO/enum/null/missing、error envelope、204/202、非 JSON body
- Modify: Gradle version catalog/module dependencies only if Batch 0 proves required; Task 2 contract/ledger

**Interfaces:**
- 输入：唯一配置 origin、HTTP method/path/body、必要 headers。
- 输出：明确区分成功 body、无 body 成功、业务 HTTP 错误、传输错误和解码/协议错误的 typed result；不得直接修改 canonical state。

- [ ] 为 URL 解析、请求 origin 限制、HTTP/WS scheme 派生写失败测试。
- [ ] 为必需字段缺失、null、未知 enum、错误 DTO、204 无 body、202 activity 与非 JSON body 写失败测试。
- [ ] 运行目标测试确认 RED，再实现最小 transport/DTO/错误模型使其 GREEN。
- [ ] 验证写请求的 key/body 原样传输，敏感凭据/请求 body 不进入日志。
- [ ] 运行目标单测、`./gradlew --no-daemon testDebugUnitTest lintDebug`、`git diff --check` 并提交本 Batch。

## Batch 2：完整 Snapshot / Realtime wire 与连接生命周期

**目的：** 解析协议 v1 的完整初始 snapshot 与 invalidate，建立可单独测试的 WebSocket 连接 owner 和连接世代隔离。

**Files:**
- Create: realtime frame DTO/decoder、transport adapter、connection state/lifecycle abstraction
- Test: first-frame rules、protocol version、outer/inner epoch-sequence consistency、disconnect/reconnect、late old connection events

**Interfaces:**
- 输入：origin 与连接配置。
- 输出：连接状态事件、完整初始 snapshot candidate、invalidate watermarks；本 Batch 不直接合并 canonical Store，也不拼装业务 patch。

- [ ] 编写首帧必须为完整 snapshot、协议版本不支持、字段缺失、epoch/sequence 不一致的测试。
- [ ] 编写断线/取消/重连和旧连接迟到帧必须被忽略的测试。
- [ ] 确认 RED 后实现连接 lifecycle；重连策略需有超时、取消和有界/带抖动退避，具体值若与 Web 基线不同须在实现前说明并冻结。
- [ ] 验证任何有效初始 snapshot 未接纳前 connection readiness 不表示 fresh/writable。
- [ ] 运行目标测试、完整单测/Lint 和 `git diff --check`，提交本 Batch。

## Batch 3：Canonical Snapshot Store 与水位/并发读取规则

**目的：** 将快照接纳和状态水位防护独立为纯状态逻辑，避免网络回调直接覆盖状态。

**Files:**
- Create: immutable runtime state model、snapshot Store、snapshot acceptance/watermark policy、state read coordinator
- Test: old epoch/sequence/revision/generation、same-epoch invalidate、duplicate concurrent GET、watermark increases during GET、stale/unknown

**Interfaces:**
- Consumes: Batch 1 typed GET `/api/state` result；Batch 2 connection/snapshot/invalidate events。
- Produces: canonical `FullStateSnapshot` plus separate connection/freshness/read-error state；完整 snapshot 以外的 invalidate 不能作为 patch 写入。

- [ ] 写水位和状态转换的纯单测，先验证旧快照/旧连接覆盖的失败路径。
- [ ] 写同一状态读取合并测试，以及 GET 期间收到更高 invalidate 水位时必须再次读取的测试。
- [ ] 实现 Store 接纳策略：epoch 切换仅接受新连接有效 snapshot；同 epoch 拒绝倒退 sequence；相关 revisions/generation 不回退。
- [ ] 实现 stale/unknown 保留与 fresh 恢复规则；没有完整有效快照时不允许 fresh-dependent actions。
- [ ] 运行目标测试、完整单测/Lint 和 `git diff --check`，提交本 Batch。

## Batch 4：唯一 Realtime Controller 与应用级 Runtime ownership

**目的：** 把 transport 与 Store 接起来，保证应用中只有一个 realtime owner，页面生命周期不影响连接和未解决操作。

**Files:**
- Create: application runtime composition/root owner、single realtime controller、lifecycle/start-stop policy
- Test: one controller instance, screen/ViewModel recreation does not reconnect, connection generation switch, invalidation/read integration, process-runtime reinitialization

**Interfaces:**
- Consumes: Batch 1 transport/API、Batch 2 realtime events、Batch 3 Store/read coordinator。
- Produces: 应用级共享 state flow 与受控的 runtime lifecycle；不得由 Composable/ViewModel 创建新 canonical owner。

- [ ] 写 owner 复用和 ViewModel 重建测试，确认不会生成第二条 WebSocket/Store。
- [ ] 写端点切换后旧连接、旧 GET、旧事件均无法写入当前 Store 的测试。
- [ ] 将初始 snapshot、invalidate→GET、重连接纳串成 runtime 集成测试。
- [ ] 运行目标测试、完整单测/Lint 和 `git diff --check`，提交本 Batch。

## Batch 5：Mutation Coordinator、幂等与统一活动恢复

**目的：** 为后续 UI 提供唯一写请求 owner，正确表达 pending/success/failed/unknown 与写后同步失败。

**Files:**
- Create: mutation intent/request identity、coordinator/state machine、shared activity/error model
- Test: idempotency key and payload identity, duplicate/conflicting intents, timeout unknown, explicit HTTP failure, 204, post-write read failure, origin change, restart boundary

**Interfaces:**
- 输入：调用方提交的明确 intent（method/path/payload 和策略化的影响范围）；协调器负责生成/保留 key 与恢复动作。
- 输出：可观察的共享操作状态与活动记录；重试原 intent/key 或读取状态是显式恢复动作，不自动创造新意图。

- [ ] 写同一 intent 重试保持 key/method/path/payload、pending 冲突操作受控的测试。
- [ ] 写网络超时/断线/解码失败必须为 unknown，明确 HTTP 业务错误为 failed 的测试。
- [ ] 写 204 不解析 body、写成功后读取失败仍保留成功回执的测试。
- [ ] 写 origin 变化时禁止旧 mutation 迁移/新 key 重放，以及进程重启不自动重放的测试。
- [ ] 实现共享 coordinator/activity model；任何业务 API 调用不得绕过 coordinator 自行 retry。
- [ ] 运行目标测试、完整单测/Lint 和 `git diff --check`，提交本 Batch。

## Batch 6：集成协议回归、真实服务验证与 Task 2 退出审查

**目的：** 验证跨模块竞态和边界，更新主计划/证据；不开始 Task 3。

**Files:**
- Modify: `docs/superpowers/plans/2026-10-24-android-client-master-plan.md`（只更新 Task 2 状态/证据）
- Modify: 本 Task 合同与计划的验收记录/复选框
- Create/Update: `.superpowers/sdd/2026-10-24-task-2-network-state-coordinator/progress.md`
- Tests: 端到端 runtime fake-server/fixture tests，必要时补协议回归测试

- [ ] 运行所有协议/状态/协调器测试，覆盖初始快照、断线恢复、旧 epoch/sequence、并发读取、旧连接迟到响应、204/错误 DTO、幂等 key 与 unknown mutation。
- [ ] 执行 `./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug` 并检查退出码、测试报告、Lint warnings 和 APK 生成结果。
- [ ] 对真实 MPD-Server 做只读初始快照与状态观察；在安全可恢复条件下完成断线/恢复；仅做明确安全的可逆 mutation 验证，避免破坏性操作。
- [ ] 检查 diff/依赖，确认无 Room/SQLite、本地业务算法、UI 功能、媒体服务、CI 或服务端改动。
- [ ] 逐项核对 Task 2 合同矩阵、退出门与主计划；记录未通过项，禁止用假数据或未执行的人工验证标为通过。
- [ ] 进行独立 diff review（若当前环境没有 reviewer agent，明确记录为自审而非独立审查）；解决 Critical/Important 问题。
- [ ] 提交 Task 2 文档/实现范围内最终验收记录，并推送当前 Task 分支；不合并 main、不启动 Task 3。

## Task 2 完成报告模板

- Android base HEAD / branch / final commit：
- Actual MPD-Server/main HEAD and contract-diff result：
- Implemented batches and commits：
- `clean testDebugUnitTest lintDebug assembleDebug` result：
- Protocol/state/mutation tests and counts：
- Real-service snapshot/reconnect evidence：
- Human/device checks and limitations：
- Review result and remaining issues：
- Task 2 exit gate: PASS / BLOCKED (list unmet evidence)：
