# Task 9 Batch 0 — 上游接口核对与 Android 集成预检

- 日期：2026-11-04
- Android 仓库：`/home/Gold/WorkSpace/MS_remote_for_Android`
- Android 分支：`feature/task-9-hardware-volume-control`
- Android HEAD：`8803c2acd827eeceabc95488b920d552bb896ffa`
- Android 基线 / merge-base：`c6d9450534c179ce4cc80e6a15f1fd70fb5bb0ef`
- 上游仓库：`/home/Gold/WorkSpace/MPD-Server/MPD-Server`
- 上游分支：`feature/hardware-volume-control`
- 上游当前 HEAD：`b2f318bc69e828d4b17ecbfa1acffa3381cf9dae`
- NAS 部署代码：`7194191b27ad117b593d1cd302cf78bb11792457`

## 1. 核对结论

B0 的 wire contract 和 Android 既有集成接缝已锁定。API contract 已补齐完整字段层级、camelCase/snake_case 差异、错误状态、幂等重放规则和 realtime invalidate 形状。B1 可按明确结构实现 DTO，不再需要猜测服务端字段。

本批只做只读核对与文档修订：未修改 Android 生产代码；未执行 NAS 写操作；未改变 MPD 音量、输出、播放或 Queue；未安装/覆盖安装 APK；未清理或修改 App 数据。B1–B7 均未执行。

## 2. 上游版本与 NAS 部署对应关系

上游工作区状态：

- 分支为 `feature/hardware-volume-control`，本地 HEAD 与 `origin/feature/hardware-volume-control` 完全一致，均为 `b2f318bc69e828d4b17ecbfa1acffa3381cf9dae`。
- `7194191b27ad117b593d1cd302cf78bb11792457` 是当前 HEAD 的祖先。
- 对 `server/app` 和 `server/tests` 执行 `git diff 7194191..HEAD` 无差异；因此 NAS 部署 SHA 与当前分支的服务端实现/测试代码一致，HEAD 后续提交只记录部署验收文档。
- 上游工作区干净。

NAS 对应证据来自上游报告：
`MPD-Server/docs/superpowers/reports/2026-11-04-hardware-volume-control-nas-acceptance.md`

该报告记录：

- Synology DS920，MPD 0.23.5。
- NAS 容器运行镜像 `ghcr.io/qyxx2/mpd-server:sha-7194191b27ad117b593d1cd302cf78bb11792457`，多架构 digest 为 `sha256:d8ec86384ca831e1843e8b46e89d99e0db29403bffb5be67a3f5282ad3733464`。
- `MPD_HARDWARE_VOLUME_ENABLED=true`，镜像 revision 与部署 SHA 一致，容器状态 running。
- 报告时健康端点返回 HTTP 200，MPD 状态 connected。
- USB DAC 是启用的 ALSA 输出；HTTP Stream 输出禁用。音量控制源是 MPD hardware mixer。
- 报告记录了服务端 80 → 77 → 80 的可恢复 API 测试，最终恢复并确认音量 80；播放状态保持 PAUSED，current song ID 与 MPD songid 不变，Queue revision 保持 641，repeat/random 未改变。

**本次环境的实时复核限制：** 从当前 Ubuntu 工作区对 NAS 报告所用 HTTP 服务地址执行只读 `GET /api/health`、`GET /api/system/hardware-volume`、`GET /api/state`，三次连接均在 2 秒 connect timeout 后失败。请求均为 GET，没有写入副作用。这不能推翻 NAS 验收报告，但也不能作为“本次会话重新连通”的证据。当前服务部署关系以 NAS 验收报告和代码祖先关系为证；Task 7 中用户已确认的安装版连通情况作为现有上下文保留，Task 9 B7 仍须在 Android 真机上重新验证当前连接和版本。

## 3. 完整状态快照：`GET /api/state`

上游来源：

- `server/app/models/hardware_volume.py`
- `server/app/models/realtime.py`
- `server/app/api/realtime_schemas.py`
- `server/app/services/state_service.py`
- `server/tests/api/test_realtime_state.py::test_state_api_exposes_explicit_hardware_volume_snapshot_and_observation`
- `server/tests/api/test_web_wire_fixture.py::test_wire_fixture_documents_hardware_volume_snapshot_without_new_revision_domain`

两个顶层字段并列，不能将 observation 错误地嵌套进 hardware_volume：

```json
{
  "hardware_volume": {
    "state": {
      "status": "AVAILABLE",
      "volume_percent": 80,
      "output_name": "USB DAC",
      "output_plugin": "alsa",
      "output_enabled": true,
      "control_source": "MPD_HARDWARE_MIXER",
      "error_code": null,
      "error_message": null,
      "updated_at": "<server timestamp>",
      "stale": false
    },
    "last_request": null
  },
  "hardware_volume_observation": {
    "observed_at": "<server observation timestamp>",
    "freshness": "fresh",
    "error_code": null,
    "error_message": null
  }
}
```

字段约束：

- `hardware_volume.state.status` 当前枚举：`AVAILABLE`、`UNAVAILABLE`、`UNSUPPORTED`。
- `volume_percent` 为 nullable 严格整数，范围 0–100；null 是未知，不能转换为 0。
- `output_name`、`output_plugin`、`output_enabled`、`control_source`、`error_code`、`error_message` 可为 null。
- `control_source` 当前已定义值为 `MPD_HARDWARE_MIXER`。
- `updated_at` 是服务端状态时间；`stale` 是该状态对象的布尔标志。
- `last_request` 可以是 null；非 null 时包含 `requested_percent`（0–100）、`status`（`PREPARING` / `SUCCEEDED` / `FAILED`）、`error_code`、`error_message`、`updated_at`。
- 顶层 `hardware_volume_observation` 包含 nullable `observed_at`、`freshness`（`fresh` / `stale` / `unknown`）、nullable `error_code`、`error_message`。
- 完整状态 wire key 是 snake_case。当前 `revisions` 只有 `library`、`playlist`，没有单独的硬件音量 revision。
- 当前服务端 schema 没有暴露 ALSA raw left/right、dB 或 per-output volume；它表达的是 MPD instance 级硬件 Mixer 音量。

旧版服务端可能完全缺少这两个顶层字段。Android 新 DTO 必须将它们视为可选扩展；缺失时只把硬件音量投影为 UNKNOWN，不能把整个既有播放快照解码失败，也不能伪造为 0 或显式 UNSUPPORTED。

## 4. 专用接口：`GET/PUT /api/system/hardware-volume`

上游来源：

- `server/app/api/system.py`
- `server/app/api/system_schemas.py`
- `server/tests/api/test_system_hardware_volume.py`
- `server/tests/api/test_web_wire_fixture.py`

GET 和成功 PUT 使用同一个 response schema，顶层为 `state`、`observation`、`lastRequest`。与 `/api/state` 的 snake_case 不同，这个 REST response 及其内部对象使用 camelCase：

```json
{
  "state": {
    "status": "AVAILABLE",
    "volumePercent": 80,
    "outputName": "USB DAC",
    "outputPlugin": "alsa",
    "outputEnabled": true,
    "controlSource": "MPD_HARDWARE_MIXER",
    "errorCode": null,
    "errorMessage": null,
    "updatedAt": "<server timestamp>",
    "stale": false
  },
  "observation": {
    "observedAt": "<server observation timestamp>",
    "freshness": "fresh",
    "errorCode": null,
    "errorMessage": null
  },
  "lastRequest": null
}
```

PUT 请求 body：

```json
{"volumePercent": 77}
```

- `volumePercent` 是必需的严格整数，范围 0–100；bool、浮点、字符串、null、越界值以及未知额外字段均会被拒绝。
- 必须携带 `Idempotency-Key`。
- 成功响应是 HTTP 200 完整状态，而不是 204；`lastRequest.status` 为 `SUCCEEDED`，返回的是服务端写入后的 MPD 读回确认状态。只有读回值与请求目标一致才会报告成功。
- GET 会先执行硬件音量观察，再返回同一轮状态与 observation；不能将一个旧 observation 与新状态拼接。

错误映射（错误 body 统一由全局处理器包装为 `{"error":{"code":"...","message":"...","details":...}}`）：

| HTTP | code | 触发条件 |
|---:|---|---|
| 400 | `HARDWARE_VOLUME_NOT_CONFIGURED` | 功能开关关闭/未配置 |
| 400 | `HARDWARE_VOLUME_OUTPUT_AMBIGUOUS` | 选中的 ALSA 输出不唯一 |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | 已用 key 被不同 method/path 或 payload 重用 |
| 422 | `VALIDATION_ERROR` | body 字段、类型、范围或额外字段无效 |
| 422 | `IDEMPOTENCY_KEY_REQUIRED` | mutation 缺少 Idempotency-Key |
| 502 | `HARDWARE_VOLUME_WRITE_FAILED` | MPD setvol 写入被拒绝/失败 |
| 502 | `HARDWARE_VOLUME_RECONCILIATION_FAILED` | 写入后 MPD 读回不等于目标值，不能报告成功 |
| 503 | `HARDWARE_VOLUME_UNAVAILABLE` | MPD 状态/目标硬件 Mixer 无法观察或使用，或 GET 时服务 manager 缺失 |
| 503 | `PLAYER_UNAVAILABLE` | MPD/player 当前不可用 |

幂等语义来自 `server/app/main.py`、`server/app/services/idempotency_service.py` 和 API 测试：

1. 除明确豁免的 `POST /api/library/collections` 外，所有 `/api/**` 的 POST/PUT/PATCH/DELETE 都要求 Idempotency-Key。
2. 服务端以 method + path 和规范化 payload hash 绑定 key。相同 key、相同 operation scope 与 payload 会重放已保存的原 HTTP status/body，不再次执行 MPD 写入。
3. 同 key 对应不同 method/path 或 payload 返回 HTTP 409 `IDEMPOTENCY_KEY_CONFLICT`。
4. 新用户意图必须生成新 key。客户端传输结果 UNKNOWN 时只能显式重试原 immutable intent（原 method/path/payload/origin/key）；不能从 UI 当前值重建目标或自动换 key。
5. 服务端 4xx 错误不保存为成功幂等结果；服务端 5xx 不能证明写入未发生，Android 现有 MutationCoordinator 会将这类结果归为 UNKNOWN。

## 5. WebSocket realtime 协议

上游来源：

- `server/app/models/realtime.py`
- `server/app/services/realtime_coordinator.py`
- `server/tests/invariants/test_realtime_hardware_volume.py::test_websocket_volume_change_emits_v1_invalidation_without_hardware_payload`
- NAS 验收报告中的 80 → 77 → 80 实测记录。

音量状态变化发送 invalidate，不发送音量 patch。硬件音量 domain 的 frame 结构：

```json
{
  "type": "invalidate",
  "protocol_version": 1,
  "epoch": "<current server epoch>",
  "sequence": 7,
  "domains": ["hardware_volume"],
  "revisions": {
    "library": 0,
    "playlist": 0
  }
}
```

上例是按服务端模型和测试构造的结构示例；`epoch` 和 revisions 必须使用该连接实际值。NAS 报告实测音量写入分别收到 sequence 7、恢复写入收到 sequence 8；输出禁用/恢复额外收到 sequence 9/10。帧不得包含 `state`、`hardware_volume` 或音量数值。

客户端收到该 domain 后必须走唯一 RealtimeController → StateReadCoordinator 完整读取 `GET /api/state`，服从 epoch/sequence/revisions 水位、连接 generation 与迟到响应保护。禁止解析 invalidate 来更新本地音量，禁止另建硬件音量 WS 或 poller。

## 6. 服务端状态解释与可写门槛

- `AVAILABLE` 不足以单独开放控制：还必须同时满足当前连接有效、共享快照 fresh、observation freshness=fresh、`stale=false)、`volume_percent` 非 null 且在 0–100。
- `UNSUPPORTED` 表示服务端功能未配置/不支持；当前实际关闭开关时值为 null、observation unknown。
- `UNAVAILABLE` 可以携带最后一次确认的 `volume_percent`；当 MPD 断开或输出不可用时，示例为 last-known 值 + `stale=true` + observation stale。这只能展示，不能写入。
- 缺失字段、null、未知未来枚举或解码错误都必须 fail-closed。未知未来枚举应由 Android 局部降级为 UNKNOWN，而不是按 AVAILABLE 处理或使其他播放/队列字段丢失。
- 无当前歌曲、播放暂停或停止不改变硬件音量能力资格；不要复用依赖当前曲目的 Player transport control eligibility。
- 输出状态 ACTIVE 不证明实体 DAC 正在出声或 bit-perfect；此接口也不是 output-ID 独立音量。

## 7. Android 现有集成接缝核对

核对 Android 当前 HEAD 的以下实现：

| 组件 | 当前事实 | 对 Task 9 的约束 |
|---|---|---|
| `protocol/dto/StateSnapshotDto.kt` | 已有播放、Queue、output、observation 等快照字段；尚无两个 hardware-volume 字段 | B1 在完整快照 DTO 中添加可空扩展；缺字段兼容旧 server |
| `protocol/dto/StateSnapshotResources.kt` | 负责现有快照 JSON 子树 typed decode | B1 只增加薄 typed projection/decoder；局部错误不能破坏其他快照域 |
| `protocol/dto/RealtimeFrameDecoder.kt` | protocol-v1 invalidate 校验 domains 非空；没有固定领域白名单，也不读取 domain payload | 当前无需为了增加 hardware_volume 而改 decoder |
| `runtime/RealtimeController.kt` | 唯一实时连接 owner；所有 invalidate 都进入内部共享 read coordinator | hardware_volume 自动沿同一完整快照路径同步，不创建平行 owner |
| `runtime/StateReadCoordinator.kt` | GET 单飞/合并；水位前进时重读；拒绝旧 epoch/旧 generation 迟到快照，失败标记 stale | 继续复用，音量变化通过 SnapshotStore 的完整快照进入 UI |
| `runtime/SnapshotStore.kt` | 唯一 canonical snapshot owner | 不新增 volume Store/cache/persistence |
| `runtime/AppRuntime.kt` | 一个 SnapshotStore、一个 RealtimeController、一个 MutationCoordinator；写成功后调用完整状态读取，并以 origin/generation 检查后交由 SnapshotStore 接受 | volume command gateway 仅作为薄 intent adapter；不得直写 SnapshotStore |
| `runtime/MutationCoordinator.kt` | 新 intent 自动产生 UUID Idempotency-Key；UNKNOWN 可显式 retry 原 immutable request/key/origin；SYNCING 等待同步后才 SUCCESS；5xx/transport/decode/protocol 归 UNKNOWN | volume 命令必须复用此 owner；不要自建 HTTP transport 或 key/retry |
| `MainActivity.kt` | edge-to-edge Activity，持有系统 WindowInsets / cutout geometry；当前没有全局 volume-key dispatch | 后续输入源只能在应用级前台边界接入，不在各页面添加 listener；B0 不实施 |
| `ui/components/PopupMotion.kt` | 已有单一 PopupMotionHost/AnchorState；180ms、0.82→1 scale、alpha 与实际 screen-space anchor transform | DAC 面板后续复用现有 host；B0 不改动效 |
| `ui/player/FullScreenPlayerScreen.kt` | 当前仍有大型 OutputDetailsDialog | 后续 UI 批次按既有合同替换；B0 不提前修改 |

额外检查：

- Android 当前 `RealtimeFrameDecoder` 并不要求 invalidate domain 在固定 allowlist；`hardware_volume` 无需解码成 patch。
- `AppRuntime.mutations` 的 send adapter 将 MutationCoordinator 生成的 key 设置到 `Idempotency-Key` header；synchronizer 读取完整 `StateSnapshotDto`，并检查当前 origin 与 generation 后才交给 SnapshotStore。
- 目前没有 hardware-volume DTO/policy/gateway，这是 B1/B3 的工作，不是 B0 遗漏。
- 上游 `web/src` 生产源码未检索到 hardware-volume route/action/UI 实现；当前 Web wire fixture 已记录完整状态及 REST alias 形状。故不能将“Web 已有硬件音量 UI/命令流”当成事实。可复用的既有 Web/后端语义是：服务端快照唯一权威、WS invalidate 后完整读取、写入经幂等 mutation 并等待 readback。Android 必须优先沿用自身既有 Runtime 实现这些相同语义。

## 8. 验证证据与限制

上游 NAS 验收报告记录以下测试证据：

- `server/tests/api/test_system_hardware_volume.py`：GET camelCase 字段、UNAVAILABLE/null、disconnect stale、disabled UNSUPPORTED、严格 request validation、Idempotency-Key 必需、成功读回、同 key replay、conflict、MPD 错误和 reconciliation mismatch。
- `server/tests/api/test_realtime_state.py::test_state_api_exposes_explicit_hardware_volume_snapshot_and_observation`：完整快照两个新增字段、关闭功能时不伪造值。
- `server/tests/invariants/test_realtime_hardware_volume.py::test_websocket_volume_change_emits_v1_invalidation_without_hardware_payload`：domain invalidate，不携带状态/音量 patch。
- `server/tests/invariants/test_hardware_volume_playback_preservation.py`：写入对 playback/queue/history 的保护、与播放 mutation 串行及只广播净变化。
- CI GitHub Actions #56：validate 与 image 均成功；报告记录完整 server tests 2073 passed，硬件音量 API 子集 23 passed，deploy contract tests 5 passed，Ruff 全部通过。

尚未由这份 NAS 报告证明的物理/环境边界：

- API 写入前后的 ALSA PCM 原始左右声道值没有在同一轮取样；MPD/API 80→77→80 逻辑百分比不能替代 raw readback。
- 未执行第三方 ALSA mixer 直接写入；外部 Mixer 变化能否被 MPD status 观察仍未知。
- 未主动断开 MPD 网络连接；服务端单测覆盖 player disconnect stale，但 NAS 真实断线恢复未实测。
- 本次当前 Ubuntu 环境的直连 health/state GET 超时，当前会话未重新建立 NAS live connection。

这些项目不改变已锁定的 HTTP/WS wire schema；它们仍需在 Task 9 B7 对真实组合做验证，不能写成已通过。

## 9. B0 交付判定

- [x] 上游分支、HEAD、部署 SHA 及其祖先关系已记录。
- [x] `GET /api/state` 完整 hardware-volume schema 与字段层级已锁定。
- [x] 专用 GET/PUT 成功响应、请求 body、状态枚举、null/stale/fresh 和 HTTP 错误码已锁定。
- [x] Idempotency-Key 必需、同 key replay/conflict、UNKNOWN 同 intent retry 语义已锁定。
- [x] realtime invalidate frame 结构、无 payload 规则和完整快照重读路径已锁定。
- [x] Android DTO、RealtimeController、StateReadCoordinator、SnapshotStore、MutationCoordinator、MainActivity、PopupMotion 接缝已审查。
- [x] Android API contract 已补齐，本报告已加入文档索引。
- [x] 未修改 Android 生产代码，未对 NAS 进行写操作。

**结论：** B0 文档/集成核对退出门满足，B1 DTO 实现具备明确 wire contract。实时服务连接未在本次 Ubuntu 主机重验；B7 必须重新执行连接与 readback 验收。Task 9 其余批次仍未开始。
