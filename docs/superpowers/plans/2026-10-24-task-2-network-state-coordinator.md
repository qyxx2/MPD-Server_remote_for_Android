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

## Batch 0：固定合同映射与精确文件/接口映射

**目的：** 在任何实现代码之前，把已冻结的合同矩阵映射到 Android 工程、具体类型/文件和测试 seam。使用 Task 2 合同中记录的固定服务端基线；按用户决定，不在各 Batch 重复核对上游 HEAD。

**Files:**
- Review: `docs/superpowers/specs/2026-10-24-task-2-network-state-coordinator.md`
- Review: 四份既有权威规格和 `docs/superpowers/plans/2026-10-24-android-client-master-plan.md`
- Create/Update: 本计划中记录的实际 package/file map、fixture 来源和基线 HEAD（如需单独证据文件则放 `.superpowers/sdd/2026-10-24-task-2-network-state-coordinator/progress.md`）

- [x] 已读取 Task 2 合同、四份上位权威规格、Task 1 工程/测试配置与现有源码；服务端固定基线沿用合同记录的 `2ad84454ceb70817bd996c92b590cfa2d9e9b128`，本 Batch 不访问上游、不重复核对 HEAD。
- [x] 已盘点 Gradle 依赖、测试配置和 `app/src`：目前只有 Compose/Activity/JUnit；OkHttp、Kotlin Serialization、Coroutines/StateFlow 均未声明，后续 Batch 1 必须在依赖评估后按合同添加，不得把它们当成现成依赖。
- [x] 已冻结下方 package/file/type/owner/test seam/fixture map；Batch 0 不创建产品实现代码、不增加依赖。
- [x] 已将合同矩阵逐项映射到具体 Batch、自动测试、人工检查及退出证据。
- [x] 已执行 Task 1 基线 `./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug`；结果见 `.superpowers/sdd/2026-10-24-task-2-network-state-coordinator/progress.md`，完整日志 `/tmp/task2-batch0-baseline.log`。

### Batch 0 frozen implementation map

Package root: `io.github.qyxx2.mpdserver.remote`; all files remain in the existing single `app` module. Paths below are binding targets for later Batches, not files to create in Batch 0.

| Layer | Frozen path / primary types | Owner / test seam | Batch |
|---|---|---|---|
| Network origin | `network/ServerOrigin.kt`: `ServerOrigin`, `EndpointResolver` | Pure URL resolver; injected origin, no global mutable config | 1 |
| HTTP transport/results | `network/HttpTransport.kt`: `HttpTransport`, `HttpResult`, `HttpFailure` | Injected OkHttp `Call.Factory` or narrow transport interface; response body is consumed once and closed | 1 |
| Serialization/error | `protocol/JsonConfiguration.kt`, `protocol/ApiErrorDto.kt`, `protocol/ApiResult.kt` | Explicit `Json` configuration; typed business/transport/decode/protocol distinctions | 1 |
| API DTOs | `protocol/dto/StateSnapshotDto.kt`, `protocol/dto/SystemDto.kt`, `protocol/dto/PlaybackDto.kt`, `protocol/dto/LibraryDto.kt`, `protocol/dto/PlaylistDto.kt`, `protocol/dto/RealtimeFrameDto.kt` | Wire DTOs separate from canonical runtime models; required/missing/null/enum rules tested explicitly | 1–2 |
| Thin repositories | `data/StateRepository.kt`, `data/SystemRepository.kt`, `data/PlaybackRepository.kt`, `data/LibraryRepository.kt`, `data/PlaylistRepository.kt` | Accept injected `HttpTransport`; endpoint calls only, no parallel business algorithms | 1 and later consumers as needed |
| Realtime wire/lifecycle | `network/RealtimeTransport.kt`, `runtime/RealtimeConnection.kt` | Injected WebSocket adapter, callback/event seam, connection generation; no Store mutation in transport | 2 |
| Canonical snapshot | `runtime/FullStateSnapshot.kt`, `runtime/SnapshotStore.kt`, `runtime/SnapshotAcceptancePolicy.kt`, `runtime/StateReadCoordinator.kt` | Immutable snapshot + pure acceptance policy; injected dispatcher and controllable fake repository for deterministic ordering | 3 |
| Application/runtime composition root | `MpdServerRemoteApplication.kt`, `runtime/AppRuntime.kt`, `runtime/RealtimeController.kt` | `Application.onCreate` creates one `AppRuntime`; ViewModels only consume shared flows and never create owners | 4 |
| Mutation/activity | `runtime/MutationCoordinator.kt`, `runtime/MutationIntent.kt`, `runtime/OperationActivity.kt`, `runtime/ActivityStore.kt` | Injected transport/repositories, key factory, dispatcher and clock; stable intent identity for deterministic retry tests | 5 |
| Tests/fixtures | `app/src/test/resources/fixtures/task2/` plus package-mirrored `app/src/test/java/io/github/qyxx2/mpdserver/remote/{network,protocol,data,runtime}/` | Synthetic fixtures derived only from the frozen API/Web contracts and documented baseline; each fixture records source/field intent. Do not copy secrets or depend on a live server in unit tests | 1–5 |

**Ownership rules:** the `Application` composition root owns exactly one `AppRuntime`; `AppRuntime` owns the canonical `SnapshotStore`, one `RealtimeController`, and one `MutationCoordinator`. Repositories and transport adapters are dependencies of this runtime, not owners created by screens/ViewModels. HTTP and WS URLs derive from the same `ServerOrigin`; endpoint changes increment connection generation and invalidate old results. Dispatchers, time/clock, key generation and fake transport are constructor-injected only where needed for deterministic tests; do not add service locator/global singleton frameworks.

**Contract matrix → implementation/test/verification mapping**

| Contract row | Batch / expected test names | Manual check | Exit evidence |
|---|---|---|---|
| Origin and transport | B1 `EndpointResolverTest.httpAndWsShareOrigin`, `EndpointResolverTest.rejectsForeignOrigin` | B6 read-only connection to configured service origin | Resolver tests + configured-origin snapshot evidence |
| HTTP errors/body | B1 `HttpResultTest.distinguishesHttpBusinessTransportAndDecodeFailures`, `HttpResultTest.accepts204WithoutBody`, `HttpResultTest.preservesNonJsonErrorStatus`, `ApiErrorDtoTest.requiresErrorEnvelope` | B6 compare a real response/error with contract; no destructive request required for this row | Unit test report and response classification evidence |
| WebSocket initial frame | B2 `RealtimeFrameDecoderTest.requiresCompleteV1SnapshotFirst`, `RealtimeFrameDecoderTest.rejectsUnsupportedVersion`, `RealtimeFrameDecoderTest.rejectsOuterInnerWatermarkMismatch` | B6 confirm real connection supplies complete snapshot | Frame tests + recorded initial snapshot result |
| Invalidate and authoritative reread | B2/B3 `RealtimeFrameDecoderTest.decodedInvalidateIsNotPatch`, `StateReadCoordinatorTest.coalescesConcurrentReads`, `StateReadCoordinatorTest.rereadsWhenWatermarkAdvancesDuringGet` | B6 observe a state change and subsequent full-snapshot reread | Deterministic read-coordinator tests/log |
| Epoch/sequence/revisions | B3/B4 `SnapshotAcceptancePolicyTest.rejectsOlderEpochSequenceAndRevisions`, `RealtimeControllerTest.ignoresLateOldConnectionEvents` | B6 disconnect/reconnect and verify new snapshot ownership | Store/controller tests + reconnect observation |
| Fresh/stale/unknown and observation | B3 `SnapshotStoreTest.retainsLastKnownAsStale`, `SnapshotStoreTest.doesNotBecomeFreshWithoutAcceptedSnapshot`, `SnapshotStoreTest.doesNotInferActualPlaybackFromBusinessState` | B6 inspect real observation/freshness fields | State transition tests + captured field checklist |
| Mutation intent/idempotency | B5 `MutationCoordinatorTest.retriesSameIntentWithSameKeyAndPayload`, `MutationCoordinatorTest.marksTransportAmbiguityUnknown`, `MutationCoordinatorTest.doesNotAutoReplayUnknown` | B6 only one explicitly safe/reversible mutation if environment and state allow; otherwise document blocked | Coordinator tests + mutation receipt/readback evidence or explicit blocker |
| Write sync and 204 | B1/B5 `HttpResultTest.accepts204WithoutBody`, `MutationCoordinatorTest.keepsWriteSuccessWhenFollowupReadFails` | B6 verify receipt and readback are distinct if safe to exercise | Unit test report + activity/result evidence |
| Origin change/process restart | B4/B5 `RealtimeControllerTest.discardsOldOriginGenerationResults`, `MutationCoordinatorTest.doesNotMigrateIntentOnOriginChange`, `MutationCoordinatorTest.doesNotReplayAfterRuntimeRecreation` | B6 change origin only if a safe alternate endpoint is available; otherwise use deterministic tests | Generation/restart tests + documented manual limitation |
| Activity/error ownership | B5 `ActivityStoreTest.retainsUnresolvedOperationAcrossObserverRecreation`, `MutationCoordinatorTest.separatesUnknownFromFailed` | B6 inspect shared operation activity through a later UI integration only; Task 2 currently has no UI, so runtime evidence only | Activity/coordinator test report |
| Local persistence boundary | B4/B5 `RuntimeCompositionTest.hasOneCanonicalOwner`, `MutationCoordinatorTest.doesNotPersistOrReplayAcrossProcessBoundary` | B6 restart process and confirm fresh snapshot; no Task 2 UI assumption | Composition/boundary tests + restart observation |

**Fixture provenance:** fixtures are hand-authored minimal JSON based on the fields and invariants explicitly documented in `docs/superpowers/specs/2026-10-23-android-backend-api-contract.md` §§2–5 and `docs/superpowers/specs/2026-10-23-android-web-behavior-contract.md` §§2–4, with ownership/persistence constraints from `docs/superpowers/specs/2026-10-23-android-client-boundary-and-architecture.md` §§2–6 and `docs/superpowers/specs/2026-10-24-android-client-product-and-engineering-spec.md` §§10, 12. Fixture metadata must identify whether each value is contractually required, nullable, an enum, or a deliberately invalid case. Do not claim fixtures were captured from a live server unless actually captured and versioned as such.

**Batch 0 review ruling:** dependency versions are not frozen by this batch because those libraries are absent from the version catalog. Batch 1 must select versions compatible with the already frozen Kotlin/AGP toolchain, add the smallest required set, and record evidence in the contract before implementation. No new library is added during Batch 0.

**Exit condition:** the existing authority documents do not leave a known unresolved wire/DTO conflict for the mapped surfaces; every subsequent Batch has a concrete path, owner, test seam and negative test; baseline build/test/Lint result is recorded. Rechecking upstream HEAD is explicitly not an exit condition.

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
- Fixed MPD-Server baseline used (no recheck unless user requests/announces a change)：
- Implemented batches and commits：
- `clean testDebugUnitTest lintDebug assembleDebug` result：
- Protocol/state/mutation tests and counts：
- Real-service snapshot/reconnect evidence：
- Human/device checks and limitations：
- Review result and remaining issues：
- Task 2 exit gate: PASS / BLOCKED (list unmet evidence)：
