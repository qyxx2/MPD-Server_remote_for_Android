# Android 客户端后端接口与实时协议规格

> **状态：权威接口合同（基线快照）。** 本文约束 Android 客户端如何消费当前 MPD-Server 接口，不重新定义服务端领域规则、数据库、MPD 执行流程或业务状态。
> 核对基线：`qyxx2/MPD-Server/main`，commit `2ad84454ceb70817bd996c92b590cfa2d9e9b128`（2026-10-18）。
> **2026-11-04 硬件音量扩展：** B0 已按 MPD-Server `feature/hardware-volume-control` 核对，当前上游 HEAD 为 `b2f318bc69e828d4b17ecbfa1acffa3381cf9dae`，NAS 部署代码为其祖先 `7194191b27ad117b593d1cd302cf78bb11792457`。硬件音量 wire DTO、HTTP 错误和 realtime invalidate 形状已在本文下方锁定，证据见 [Task 9 Batch 0 核对报告](../reports/2026-11-04-task-9-batch0-verification.md)。上方 2026-10-18 commit 仍是既有接口的历史核对基线，不代表当时已包含本扩展。

## 1. 权威与总原则

1. MPD-Server 是唯一业务状态来源。Android 通过现有 HTTP API 和 WebSocket 进行遥控、读取及原生展示；不得直接连接 MPD TCP 控制端口。
2. 本文只记录客户端所需的公开接口和 wire contract。数据库、服务端 service/repository、Queue/AutoPlay/History、播放状态恢复、MPD occurrence 映射、事务等仍由 MPD-Server 负责。
3. 上游 `main` 变动后，先核对实际 HEAD、服务端 schema、Web 调用点与测试，再更新本合同；不得使用旧 `v0.2` 分支或历史归档覆盖当前 `main`。
4. 字段为 null、stale、unknown 或缺失时必须按其含义展示；不能默认补成 0、空列表、停止或成功。

## 2. 通用 HTTP 与错误约定

- 请求基于已配置的服务 origin，接口统一位于 `/api/...`。
- 标准错误形状为 `{"error":{"code":"…","message":"…","details":…}}`。将 HTTP 业务错误、网络/超时导致的结果未知、JSON/DTO 格式错误区分处理。
- `/api/**` 下的 POST、PUT、PATCH、DELETE 一般要求 `Idempotency-Key`。当前唯一豁免是 `POST /api/library/collections`。
- 新的明确用户意图生成新的 key。对同一操作的传输重试必须复用原 key、method、path 和 payload；网络或解码失败可能意味着服务端已经提交，不能用新 key 自动重发。
- 成功 `204 No Content` 没有 JSON body。不得从写请求的 payload 推造完整权威状态；需要时重新读取完整状态或相应资源。
- `GET /api/health` 的成功不证明 MPD 已连接、输出已启用或实体 DAC 正在出声。
- `PUT /api/system/mpd/connection` 保存连接目标，但不代表当前活动连接已切换；现有部署需重启服务后应用保存地址。

## 3. 完整状态和实时协议

### `GET /api/state`

返回完整 `FullStateSnapshot`。当前主要字段：

| 字段 | 客户端含义 |
|---|---|
| `epoch`、`sequence`、`captured_at` | 服务会话、状态序列水位与快照时间 |
| `explicit_stop_generation` | 明确 Stop 的代次，不可用普通 STOPPED 猜测用户意图 |
| `revisions.library`、`revisions.playlist` | 曲库与播放列表修订水位 |
| `playback`、`current_song` | 服务端业务播放状态和当前歌曲元数据；单独使用不证明真实播放已确认 |
| `queue.revision`、`queue.items` | 完整服务端 Queue 及 revision |
| `history` | 历史存在状态、活动事件和 session |
| `playback_observation` | 实际播放、current 身份绑定、控制目标、新鲜度与错误 |
| `output`、`output_observation` | 输出请求/状态以及输出观察新鲜度 |
| `hardware_volume`、`hardware_volume_observation` | MPD 实例级硬件音量能力/值与观察状态、freshness；不代表每个 output ID 的独立音量 |
| `library_sync` | 曲库同步状态、generation、计数和错误 |

若组装本地必需域失败，接口返回 HTTP 503 `STATE_SNAPSHOT_UNAVAILABLE`，不能返回伪装为成功的部分快照。外部播放或输出观察可能返回显式 stale/unknown。

### `WS /api/realtime`

- 当前协议版本为 1。连接后的第一条成功帧是完整 snapshot：`type=snapshot`、`protocol_version=1`、`epoch`、`sequence`、`state`。
- 后续 invalidate 帧同样包含 `protocol_version=1`，以及 `epoch`、`sequence`、`domains` 和 `revisions`。客户端必须验证两种帧的版本均为 1；缺失或不支持的版本都是协议错误。domains 可包括 playback、queue、history、library、playlist、output、hardware_volume、library_sync。
- snapshot 外层 epoch/sequence 必须与 state 内部对应字段一致。
- invalidate 代表重新读取的义务，不是 state patch；消息可重复。客户端按 epoch/sequence 过滤后调用 `GET /api/state`。
- 每次重连都必须获得新连接的完整初始快照；不要依据旧 cursor 或本地持久化快照恢复权威状态。
- 当前 Web 有约 10 秒的初始 snapshot/读取超时及带抖动的指数退避。Android 可采用原生实现，但需保留超时、取消、重连和禁止旧连接回写的行为。

### `playback_observation` 重点字段

`actual_state` (`playing|paused|stopped|null`)、`actual_current`、`actual_freshness`、`bound_queue_item_id`、`control_target`、`sync_status`、`matches_current`、`position_seconds`、`duration_seconds`、`covered_playback_updated_at`、`observed_at`、`freshness`、`reconciliation_required`、`error_code`、`error_message`。

`sync_status` 当前为 `CONFIRMED`、`UNBOUND`、`EXTERNAL_DRIFT`、`UNCONFIRMED_STOP`、`SYNC_FAILED`、`NO_CANDIDATES`。`control_target` 是服务端生成的 `{queue_item_id, token}` 不透明条件对象，不是认证令牌；客户端不得生成/修改它，并须在 current/绑定变化时丢弃旧目标。

## 4. 当前可用 API 清单

### 健康、状态与系统

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/health` | 服务健康 |
| GET | `/api/state` | 完整状态快照 |
| WS | `/api/realtime` | 初始快照与 invalidate 通知 |
| GET / PUT | `/api/system/output` | 读取输出状态；PUT body `{mode, enabled}`，enabled 必须是 bool |
| GET / PUT | `/api/system/hardware-volume` | 查询/设置 MPD 实例级硬件音量；PUT body 使用 {`volumePercent`}，范围 0–100，并要求 Idempotency-Key |
| GET | `/api/system/mpd` | MPD 版本、统计、更新状态和分项错误 |
| GET / PUT | `/api/system/mpd/connection` | 读取/保存连接目标；保存后需按现有部署要求重启才生效 |

输出模式 schema 含 `NAS_DAC` 和 `CLIENT_STREAM`，输出状态含 `UNAVAILABLE`、`INACTIVE`、`ACTIVE`；schema 存在不保证当前服务实例支持某模式。`/api/system/output` 外层字段为 `states`、`last_request`，内部对象使用 camelCase；聚合的 `/api/state` 使用 snake_case。
### 硬件音量（2026-11-04 上游扩展，B0 已核对）

核对基线：MPD-Server `feature/hardware-volume-control` HEAD `b2f318bc69e828d4b17ecbfa1acffa3381cf9dae`；NAS 当前部署代码 `7194191b27ad117b593d1cd302cf78bb11792457`。字段来源为 `server/app/models/hardware_volume.py`、`server/app/api/system_schemas.py`、`server/app/api/realtime_schemas.py` 与相应 API / realtime tests。完整现场证据见 [Task 9 Batch 0 核对报告](../reports/2026-11-04-task-9-batch0-verification.md)。

#### 完整快照字段

`GET /api/state` 顶层包含两个彼此独立的 snake_case 字段：

- `hardware_volume`：`{state, last_request}`。
- `hardware_volume_observation`：`{observed_at, freshness, error_code, error_message}`。

`hardware_volume.state` 的字段是 `status`、`volume_percent`、`output_name`、`output_plugin`、`output_enabled`、`control_source`、`error_code`、`error_message`、`updated_at`、`stale`。其中 `volume_percent` 为 nullable 严格整数 0–100；其余硬件/错误描述字段按 schema 可为 null。状态枚举为 `AVAILABLE`、`UNAVAILABLE`、`UNSUPPORTED`；当前 control source 枚举为 `MPD_HARDWARE_MIXER`。

`last_request` 可为 null；非 null 时具有 `requested_percent`（严格整数 0–100）、`status`（`PREPARING`、`SUCCEEDED`、`FAILED`）、`error_code`、`error_message`、`updated_at`。Observation 的 freshness 当前为 `fresh`、`stale`、`unknown`，`observed_at` 可为 null。

#### 专用 GET / PUT response

`GET /api/system/hardware-volume` 与成功的 `PUT /api/system/hardware-volume` 返回相同 response schema，顶层为 `state`、`observation`、`lastRequest`。此 REST response 的 state / observation / lastRequest 内部字段均为 camelCase，与 `GET /api/state` 中的 snake_case 有意不同：

- `state`：`status`、`volumePercent`、`outputName`、`outputPlugin`、`outputEnabled`、`controlSource`、`errorCode`、`errorMessage`、`updatedAt`、`stale`。
- `observation`：`observedAt`、`freshness`、`errorCode`、`errorMessage`。
- `lastRequest`：null 或 `requestedPercent`、`status`、`errorCode`、`errorMessage`、`updatedAt`。

PUT body 为 `{"volumePercent": 0..100}`。字段必需、严格整数、范围 0–100，拒绝 bool/float/string/null、越界及未知额外字段。成功返回 HTTP 200 和完整状态；服务端先写入 MPD 再读回，只有 readback 与请求目标完全相等才令 `lastRequest.status=SUCCEEDED`。不是 204，也不能由客户端用 payload 乐观更新 SnapshotStore。

#### HTTP 错误与幂等

错误统一使用 `{"error":{"code":"...","message":"...","details":...}}`：

以下是 PUT 的错误映射；GET 的硬件状态不可用通常仍返回 HTTP 200 状态快照：

| HTTP | code | 语义 |
|---:|---|---|
| 400 | `HARDWARE_VOLUME_NOT_CONFIGURED` | PUT 时功能开关关闭/未配置；GET 返回显式 UNSUPPORTED |
| 400 | `HARDWARE_VOLUME_OUTPUT_AMBIGUOUS` | PUT 时 ALSA 控制目标不唯一 |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | 已用 key 被不同 method/path 或 payload 重用 |
| 422 | `VALIDATION_ERROR` | PUT body 字段、类型、范围或额外字段无效 |
| 422 | `IDEMPOTENCY_KEY_REQUIRED` | mutation 缺少 Idempotency-Key |
| 502 | `HARDWARE_VOLUME_WRITE_FAILED` | MPD 写入失败 |
| 502 | `HARDWARE_VOLUME_RECONCILIATION_FAILED` | 写入后的 MPD readback 与目标值不一致 |
| 503 | `HARDWARE_VOLUME_UNAVAILABLE` | PUT 时硬件目标/MPD 观察不可用；GET 缺少 manager 时也返回此错误 |
| 503 | `PLAYER_UNAVAILABLE` | PUT 时 MPD/player 当前不可用 |

GET 成功读取到“目标不存在”会返回 HTTP 200、`state.status=UNAVAILABLE`、`volumePercent=null` 和 freshness=unknown；已有确认值后遇到 player disconnect 则会返回 HTTP 200、`state.status=UNAVAILABLE`、保留 last-known 值并标记 stale。GET 只有无法取得 manager/构造完整响应时才通过错误 envelope 返回 503。

除 `POST /api/library/collections` 外，所有 `/api/**` 的 POST/PUT/PATCH/DELETE 均要求 Idempotency-Key。服务端按 method + path 和规范化 payload hash 绑定 key：同 key、相同 operation scope/payload 重放原 HTTP status/body，不重复执行 MPD 写入；key 被用于不同操作或 payload 时返回 409。新用户意图必须新建 key；同一 intent 出现 UNKNOWN 时，只能显式重试原 method/path/payload/origin/key，不能从当前 UI 状态重建或自动换 key。服务端 5xx 无法证明操作未提交，Android MutationCoordinator 必须按 UNKNOWN 处理。

#### 状态与兼容语义

- 只有连接/代次匹配、共享 snapshot fresh、status=`AVAILABLE`、observation freshness=`fresh`、`stale=false`、值非 null 且 0–100，才可写。
- `UNSUPPORTED` 表示服务端明确未配置/不支持；`UNAVAILABLE` 可能携带 last-known 值，但当 stale 时仅可展示，不可作为新命令基值。
- null、缺字段、未知未来枚举和无效音量子树均须 fail-closed；null 不是 0。旧服务端没有这两个新顶层字段时，Android 应局部投影为 UNKNOWN，不能使其他播放/Queue snapshot 解码失败，也不能将其伪装为显式 UNSUPPORTED。
- 硬件音量是 MPD instance 级，不是每个 output ID 的独立音量；当前 schema 不提供 ALSA raw channel 值或 dB。没有 current song、暂停或停止不改变音量能力资格。
- ACTIVE 只表示服务端输出状态，不证明实体 DAC 正在出声或 bit-perfect。

#### Realtime invalidate

WS `/api/realtime` protocol v1 收到硬件音量变化时，发送 `type=invalidate` 且 `domains` 包含 `hardware_volume`。典型帧结构：

```json
{
  "type": "invalidate",
  "protocol_version": 1,
  "epoch": "<current server epoch>",
  "sequence": 7,
  "domains": ["hardware_volume"],
  "revisions": {"library": 0, "playlist": 0}
}
```

该帧只提供 epoch/sequence/domains/revisions，不包含 `state` 或 `hardware_volume` 数据；revisions 目前仍只有 library/playlist，没有独立 hardware-volume revision。客户端必须经唯一 RealtimeController / StateReadCoordinator 重新读取完整 `GET /api/state`，继续执行 epoch、sequence、connection-generation 和迟到结果保护。不得把 invalidate 当作音量 patch，也不得另建 WS 或 polling owner。


### 播放和 Queue（前缀 `/api/playback`）

| 方法 | 路径 | 作用 / body |
|---|---|---|
| GET | `/api/playback/state` | 业务 PlaybackState |
| GET | `/api/playback/queue` | Queue 列表 |
| POST | `/api/playback/tracks/{song_id}/play` | 显式从单曲开启 Playback Context |
| POST | `/api/playback/queue/items/{queue_item_id}/play` | 播放指定 Queue occurrence |
| POST | `/api/playback/songs/{song_id}/play` | Play Now |
| POST | `/api/playback/songs/{song_id}/play-next` | 插入下一首 |
| POST | `/api/playback/songs/{song_id}/queue` | 加入待播 Queue |
| POST | `/api/playback/pause`、`/resume`、`/stop`、`/next`、`/previous` | 播放控制；resume 需要 target |
| POST | `/api/playback/seek` | body `{seconds, target?}`；seconds 非负，受保护调用使用非空 target |
| POST | `/api/playback/collections/play` | 服务端解析 CollectionRequest 并播放 |
| PUT | `/api/playback/queue/items/{queue_item_id}` | body `{before_queue_item_id: string|null}`，调整待播位置 |
| DELETE | `/api/playback/queue/items/{queue_item_id}` | 删除 occurrence，204 |
| DELETE | `/api/playback/queue` | 清除服务端定义的待播部分，204；不是 Stop |
| POST | `/api/playback/queue/save-as-playlist` | body `{name}`，由服务端保存队列 |

当前 Web 的 resume 和 seek 使用发起时的 `control_target`；若 target 能力缺失，不得静默退化为无保护 fallback。执行目标冲突由服务端判定。

### 曲库、搜索和集合（前缀 `/api/library`）

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/library/songs` | 全部歌曲；支持 `sort_by=title|modified_at`、`sort_order=asc|desc` |
| GET | `/api/library/songs/{song_id}` | 歌曲详情、歌词、音频元数据、可用性、封面描述 |
| GET | `/api/library/songs/{song_id}/artwork` | 返回嵌入封面的原始二进制图片，不是 JSON DTO |
| GET | `/api/library/albums`、`/albums/{album_id}/songs` | 专辑及成员 |
| GET | `/api/library/artists`、`/artists/{artist_id}/songs` | 艺术家及成员 |
| GET | `/api/library/genres`、`/genres/{genre_id}/songs` | 风格及成员 |
| GET | `/api/library/years`、`/years/{year}/songs` | 年代及成员 |
| GET | `/api/library/tags`、`/tags/{tag_id}/songs` | 标签及成员 |
| GET | `/api/library/search?q=...` | 搜索 Collection |
| GET | `/api/library/search/results?q=...` | 分类 groups 与 song ids |
| POST | `/api/library/collections` | CollectionRequest；该 POST 不要求 Idempotency-Key |
| POST | `/api/library/scan` | body `{root}`，202 返回 activity_id |

#### 封面二进制响应

- `GET /api/library/songs/{song_id}/artwork` 成功时，响应体是图片原始字节，`Content-Type` 由服务端读取到的实际图片 MIME type 决定；不能按 JSON DTO 解码。
- 歌曲不存在时返回 404 `SONG_NOT_FOUND`；歌曲存在但没有可读封面时返回 404 `ARTWORK_NOT_FOUND`；读取失败返回 500 `ARTWORK_READ_ERROR`。错误响应遵循通用 `error.code/message/details` 外层结构。
- 封面缺失与封面读取失败必须区分。请求失败时不能用其他歌曲的封面代替当前封面；切歌或资源身份变化后应丢弃迟到的旧图片响应。

CollectionRequest 字段为 `source_type`、`source_id?`、`query?`、`song_ids`、`randomize`、`random_seed?`、`sort_by?`、`sort_order`。来源类型为 `ALBUM`、`ARTIST`、`GENRE`、`YEAR`、`TAG`、`SEARCH`、`PLAYLIST`、`FAVORITES`、`LIBRARY`、`SONGS`。具体来源参数组合、可用歌曲和排序由服务端校验，不在 Android 重做。

### 歌单、收藏、历史

| 方法 | 路径 | 作用 / body |
|---|---|---|
| GET | `/api/playlists`、`/api/playlists/{playlist_id}`、`/api/playlists/{playlist_id}/songs` | 歌单、详情和成员 |
| POST / PATCH | `/api/playlists`、`/api/playlists/{playlist_id}` | 创建/改名，body `{name}` |
| DELETE | `/api/playlists/{playlist_id}` | 删除歌单，204 |
| POST | `/api/playlists/{playlist_id}/songs` | body `{song_id, position?}` |
| DELETE | `/api/playlists/{playlist_id}/songs/{song_id}` | 移除歌单成员，204 |
| PUT | `/api/playlists/{playlist_id}/songs/order` | body `{ordered_song_ids}` |
| GET | `/api/favorites/members`、`/api/favorites` | 收藏 song ids / 收藏集合 |
| PUT / DELETE | `/api/favorites/{song_id}` | 收藏/取消收藏；DELETE 为 204 |
| GET | `/api/history`、`/api/history/played` | 永久历史与 Queue 使用的 Played 数据 |

**History 与 Played 的区分：**
- `GET /api/history` 返回持久 `HistoryListResponse {items, count}`；每项包含 `history_id?`、`song_id`、`started_at`、`ended_at?`、`reason?`、`session_id?`。当前服务端 repository 按 `started_at DESC, history_id DESC` 排序；客户端保留服务端顺序。
- `GET /api/history/played` 返回 `QueueListResponse {items, count}`，来源为 Queue 中 `position < 0` 的 Played occurrence；它不是持久 History。
- `/api/state.history.active_event` 表示当前活动 History event，不等于已持久化记录；除非事件已进入 `/api/history`，不得当作持久历史展示。

**最近修改排序：** `GET /api/library/songs?sort_by=modified_at&sort_order=desc` 使用服务端 Song 文件修改时间（wire 字段 `file_mtime_ns`）排序。已知修改时间按降序排列，缺少修改时间的条目由服务端置后；客户端不得以 `last_scanned_at` 或本地排序替代。

**AutoPlay 候选：** 不存在单独的推荐 API。当前完整 `/api/state.queue.items` 已包含服务端 Queue occurrence 的 `position`、`source`（`MANUAL|AUTOPLAY`）、`queue_item_id`、`song_id`；是否启用 AutoPlay 由 `/api/state.playback.autoplay_enabled` 表达。客户端展示/操作必须来自这份 canonical snapshot，不能自行生成候选。

## 5. DTO 与兼容规则

- `SongResponse` 的 `song_id` 可空。主要内容包括标题、file_uri、艺人/专辑/年份/类型标签、duration、歌词及格式/来源/状态、codec/bit-depth/sample-rate/channel-count、文件元数据、`availability_status` (`AVAILABLE|MISSING|UNREADABLE`) 和封面描述。
- `QueueItemResponse` 包含 `queue_item_id`、`song_id`、`position`、`source` (`MANUAL|AUTOPLAY`) 与 `playback_context_id?`。`queue_item_id` 表示某一次 occurrence；重复歌曲必须能分别操作。
- `PlaybackStateResponse` 包含 `song_id?`、`state` (`PLAYING|PAUSED|STOPPED`)、`playback_context_id?`、`position_seconds?`、`autoplay_enabled`、`updated_at`。Android 的 snapshot 与 GET playback DTO 必须按这些 snake_case wire 字段严格解析；不能使用旧的 `context` / `position` 字段别名。
- `PlaylistResponse` 包含 `playlist_id`、name、created/updated 时间、`is_system`、`song_ids`。
- DTO 需要严格验证必需字段和 enum，区分 null 与缺省，不把 unknown 字段无条件忽略后当作权威成功。
- 系统输出状态、播放 observation、源文件编码/采样率与实际输出事实相互独立；输出 ACTIVE 不证明实体 DAC 发声或 bit-perfect。

## 6. 源码基线

- [上游 main commit](https://github.com/qyxx2/MPD-Server/commit/2ad84454ceb70817bd996c92b590cfa2d9e9b128)
- [API 路由](https://github.com/qyxx2/MPD-Server/tree/2ad84454ceb70817bd996c92b590cfa2d9e9b128/server/app/api)
- [播放控制与 Queue 路由](https://github.com/qyxx2/MPD-Server/blob/2ad84454ceb70817bd996c92b590cfa2d9e9b128/server/app/api/playback.py)
- [曲库、搜索与封面路由](https://github.com/qyxx2/MPD-Server/blob/2ad84454ceb70817bd996c92b590cfa2d9e9b128/server/app/api/library.py)
- [歌单与收藏路由](https://github.com/qyxx2/MPD-Server/blob/2ad84454ceb70817bd996c92b590cfa2d9e9b128/server/app/api/playlists.py)
- [历史路由](https://github.com/qyxx2/MPD-Server/blob/2ad84454ceb70817bd996c92b590cfa2d9e9b128/server/app/api/history.py)
- [系统与健康路由](https://github.com/qyxx2/MPD-Server/blob/2ad84454ceb70817bd996c92b590cfa2d9e9b128/server/app/api/system.py)
- [实时协议与完整状态路由](https://github.com/qyxx2/MPD-Server/blob/2ad84454ceb70817bd996c92b590cfa2d9e9b128/server/app/api/realtime.py)
- [公共 schemas](https://github.com/qyxx2/MPD-Server/blob/2ad84454ceb70817bd996c92b590cfa2d9e9b128/server/app/api/schemas.py)
- [实时 schemas](https://github.com/qyxx2/MPD-Server/blob/2ad84454ceb70817bd996c92b590cfa2d9e9b128/server/app/api/realtime_schemas.py)
- [通用错误与幂等中间件](https://github.com/qyxx2/MPD-Server/blob/2ad84454ceb70817bd996c92b590cfa2d9e9b128/server/app/main.py)