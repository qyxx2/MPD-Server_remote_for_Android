# Task 5 合同矩阵与验收覆盖

> 关联权威设计：[Task 5 主页、搜索与音乐库业务合同](2026-10-31-task-5-home-library-search-contract.md)。
> 上游基线：MPD-Server main 2ad84454ceb70817bd996c92b590cfa2d9e9b128；Android 起点 4e33c80。
> 本矩阵冻结 Task 5 的实现/测试责任，不表示任何功能已经实现或通过。

## 1. 阶段边界

| 维度 | Task 5 负责 | 明确不负责 |
|---|---|---|
| 页面 | Home、Search、Library、Playlist、Favorites | Player/Queue UI 改造、Settings、MediaSession |
| 服务端 | 消费现有 Library/History/Playlist/Favorites/Playback API | 新 endpoint、数据库迁移、推荐算法、历史分页 |
| 权威状态 | 复用 AppRuntime SnapshotStore / MutationCoordinator | 页面级 snapshot、第二个 WebSocket、客户端 queue/history/AutoPlay |
| 数据读取 | 共享 AppRuntime ResourceMemoryCache | Room/SQLite/磁盘业务缓存 |
| 视觉 | 原生横向封面 rail、歌曲优先的 Library 分类 | Web 像素级复制、Task 3 App Shell 重做 |
| 验收 | 自动化、设备联调和人工页面验收 | Task 5 以外的播放器/系统设置/锁屏功能验收 |

## 2. 功能合同矩阵

| ID | 域/功能 | 权威来源与 API | Android 合同 | 必须验证的核心点 | 批次 |
|---|---|---|---|---|---|
| T5-00 | Typed API/DTO foundation | 既有 Library/History/Playlist/Favorites/Playback API 与 FullStateSnapshot | 严格类型化读取接口，统一 path/query 编码；PlaybackStateDto 对齐 playback_context_id/position_seconds | required/null/unknown enum/URL encoding/错误 envelope；canonical playback DTO 解析与 Task 4 projection regression | B0 |
| T5-01 | Home 最近更新数据 | GET /api/library/songs?sort_by=modified_at&sort_order=desc | 使用服务端完整排序；按 album_id 首次出现分组，null album_id 按 song_id 独立呈现 | 修改时间降序、未知时间在后、稳定并列顺序、同专辑去重、不可用曲目不改变排序 | B2 |
| T5-02 | Home 最近更新封面 | Song.artwork + GET /api/library/songs/{song_id}/artwork | 同专辑选择代表封面；无封面显示占位，不能借用其他专辑图 | Artwork 404/500/错误 MIME/坏 bytes；迟到响应不串专辑 | B2 |
| T5-03 | Home 持久播放历史 | GET /api/history | 按服务端 started_at/history_id 顺序；同专辑去重并保留最近事件位置 | 顺序不本地重排、重复记录去重、移出曲库的 song 仍保留历史项 | B2 |
| T5-04 | History 与 Played 分离 | GET /api/history vs GET /api/history/played | Home 只用持久 History；Queue Played 维持临时历史 | 确认两接口不混用；尚未持久化的 active event 不伪装成 History | B2 |
| T5-05 | Home 猜你喜欢候选 | FullStateSnapshot playback + queue | 只显示 position>0 且 source=AUTOPLAY、autoplay_enabled=true 的候选 | 不读 Queue 副本；MANUAL 不混入；无 current/disabled/empty 的状态 | B2 |
| T5-06 | Home AutoPlay occurrence | queue_item_id + POST /api/playback/queue/items/{id}/play | B2/B3 保留 occurrence 身份；B5 经 MutationCoordinator 执行指定 occurrence 播放 | 重复 song 不混 occurrence；迟到回包/queue revision 变化不播放错误项 | B2/B3/B5 |
| T5-07 | Home 布局 | Task 5 Home contract + Task 3 App Shell | 三分区固定顺序、纵向填满可用正文、每区横向滚动 | 三行均可见；无不必要上下留白；窄/矮屏不裁掉第三行 | B3 |
| T5-08 | Home 滚动复位 | Compose route lifecycle | 离开 HOME 后三条 rail 回到 index 0 | 进入详情/搜索/播放器再返回；滚动状态互相独立 | B3 |
| T5-09 | Home 局部错误 | API result + snapshot freshness | 每 rail 独立 Loading/Empty/Ready/Stale/Failed | 一组失败不清空其余组；stale 不能启用 mutation | B3 |
| T5-10 | AppRuntime 资源缓存 owner | AppRuntime + RuntimeReadApi | 单一进程内 cache，所有页面共享同一实例和 HTTP owner | 跨导航页面 cache 命中；重建 destination 不新建 cache/network owner | B1 |
| T5-11 | GET single-flight | method/path/query + resource identity | 同 key 同身份并发只发一个 GET | 并发重复读、请求完成后命中、不同 query 不误合并 | B1 |
| T5-12 | Cache 身份隔离 | origin/generation/epoch | origin/generation/epoch 变化时清空、取消 in-flight，拒绝迟到响应 | server A -> B、reconnect generation、epoch restart | B1 |
| T5-13 | Cache revision invalidation | revisions.library / revisions.playlist | Library 与 Playlist/Favorites 按依赖 revision 失效 | library revision 只清 Library 域；playlist revision 清 Playlist/Favorites | B1 |
| T5-14 | History cache invalidation | snapshot.history fingerprint + 60s max TTL | History 无独立 revision，使用 fingerprint 并以 TTL 兜底 | active_event 变化、history 新增后刷新、TTL 到期 refresh | B1 |
| T5-15 | Cache stale / failure | SnapshotStore freshness + API result | 断线保留 last-known 但标 stale；失败不缓存为 Ready | stale 写禁用、恢复后重新验证、失败可重试 | B1 |
| T5-16 | Cache bounded memory | LRU / concurrency / artwork budget | metadata 有 entry/weight 上限；artwork 8MiB/item、24MiB total；并发 3 | LRU 只淘汰完成项；预取不无限增长；metadata 不因图片淘汰变成失败 | B1 |
| T5-17 | Shared Song DTO/artwork | GET /api/library/songs/{id} + artwork | Home/Search/Library/Playlist/Favorites/Queue 共用 song resource | 同 song 同身份只加载一次；请求 ID 与响应 song_id 一致 | B1 |
| T5-18 | Library root order | Task 3 eight-category contract | Songs、Playlists、Artists、Albums、Genres、Years、Tags、Favorites | 八项全有、Songs 第一、其余原相对顺序保持 | B4 |
| T5-19 | All songs list | GET /api/library/songs | 默认 title asc；availability/metadata 使用 DTO | 空/正常/缺字段/重复 ID/不可用行 | B4 |
| T5-20 | Song sort | GET /api/library/songs?sort_by=...&sort_order=... | title/modified_at × asc/desc，排序交给服务端 | 四组合请求参数与列表顺序；UI 当前字段方向明确 | B4 |
| T5-21 | Category summaries | GET /api/library/{albums,artists,genres,years,tags} | 使用 server id/name/song_count，不从 display name 推 ID | 空列表、重复 name 不混 ID、未知/空 year 值 | B4 |
| T5-22 | Category members | GET /api/library/{kind}/{id}/songs | summary ID 驱动成员读取；保留 Song availability | 404、空成员、缺元数据、不同 category 的 source mapping | B4 |
| T5-23 | Collection play/shuffle | POST /api/playback/collections/play | ALBUM/ARTIST/GENRE/YEAR/TAG/LIBRARY/PLAYLIST/FAVORITES/SEARCH 使用正确 source | payload/source_id/query；服务端排序/随机；无客户端构造 Queue | B5 |
| T5-24 | Song menu | POST /api/playback/songs/{id}/{play,play-next,queue} + Favorite API | Web 已有 Play Now/Next/Add/Favorite 语义 | available gating、写入 pending/unknown/fail/readback、song ID 精确 | B5 |
| T5-25 | Search input | GET /api/library/search/results?q= | 空 query 不请求；原生输入；可 debounce；query 变更取消旧请求 | 快速连续输入、清空、IME、页面离开、迟到回包 | B4 |
| T5-26 | Search groups | SearchResultsResponse | groups 顺序/ID/name 来自服务端 | albums/artists/genres/years/tags 映射与详情导航 | B4 |
| T5-27 | Search Song resources | Search song_ids + shared Song cache | 唯一 ID 读取一次；复用其它页面 Song DTO | 重复 ID 去重、资源缺失、旧 revision 回包 | B4 |
| T5-28 | Playlist list/detail | GET /api/playlists, /{id}, /{id}/songs | list/detail/member IDs 对齐 | system playlist 标志、rename/delete restrictions、空歌单、404、成员缺失 | B6 |
| T5-29 | Playlist CRUD | POST/PATCH/DELETE /api/playlists | 只允许用户歌单创建/改名/删除 | 成功 readback、rename/delete-only system restriction、name validation、unknown 恢复 | B6 |
| T5-30 | Playlist members/order | POST/DELETE /{id}/songs, PUT /songs/order | 添加、移除、完整成员顺序提交 | Duplicate song、member mismatch、revision 冲突、不可用 song | B6 |
| T5-31 | Favorites data | GET /api/favorites/members + GET /api/favorites | 成员 ID 与 Collection 由服务端提供 | 空/正常/失效 ID/刷新后一致性 | B6 |
| T5-32 | Favorite mutation | PUT/DELETE /api/favorites/{song_id} | 单一 MutationCoordinator；读回成员状态 | 收藏/取消收藏、重复点击锁、204 readback、unknown | B6 |
| T5-33 | Search/Library/Home cache integration | Shared cache + snapshot | 资源共用、不按页面重复请求 | Home -> Search -> Library -> Queue 重复访问请求计数 | B7 |
| T5-34 | Full Task integration | All Task 5 contracts | 页面导航、mini player 和 runtime 不受业务页变化影响 | 四根入口、返回状态、播放同步、Queue/Player regression | B7 |
| T5-35 | No backend/local authority drift | architecture boundary | 无新 API、业务 DB、客户端 AutoPlay/history/queue 算法 | diff/dependency audit、网络请求清单、持久化清单 | B7 |
| T5-36 | Home collection controls | Task 3 §9.1 + existing collections API | 最近更新 Play All/Shuffle uses LIBRARY modified_at desc; History uses SONGS plus complete unique history IDs | payload source/sort/order/randomize; not only visible items; stale/unknown disabled | B5 |
| T5-37 | Home AutoPlay occurrence preservation | Snapshot queue.items + queue-item play API | one card per AUTOPLAY occurrence; no album or song dedup; play by queue_item_id | same album/repeated song occurrences remain independently visible and actionable | B2/B3 |
| T5-38 | Home album-card navigation and shared album detail | Server `album_id` + `library/albums/{album_id}` | Every Home card with a server album ID, including one-track/SINGLE_SONG cards and AutoPlay occurrences, opens the Album route used by Search, Library and Player | opaque ID preserved; no display-name-derived ID; all entry points share Web-equivalent Album hero/actions/song rows; AutoPlay occurrence Play remains independent | B5 correction |

## 3. DTO / API 缺口矩阵

当前 Android 类型与 Repository 覆盖尚不完整，Task 5 实现须补齐：

| 资源 | Android 当前状态 | Task 5 要求 |
|---|---|---|
| Song list/detail | SongDto 存在；LibraryRepository 当前接口不完整/返回 JsonElement | 完整 typed SongListDto/强类型接口；保留 nullable song_id 与 availability |
| Album | artwork/ArtistSummary 已有部分类型 | AlbumSummaryDto/ListDto，字段匹配 server schema |
| Genre/Year/Tag | typed summary 尚缺 | 各自 summary/list DTO，Year value nullable |
| History | 缺少 typed history envelope/event | HistoryListDto/HistoryEventDto，按服务端返回顺序保留 |
| Queue | QueueItemDto/QueueSnapshotDto 已有 | 直接复用 snapshot；Home 必须保留 queue_item_id |
| Playback | PlaybackStateDto 当前 wire 字段使用 legacy context/position，与服务端 playback_context_id/position_seconds 不一致 | B0 对齐 snapshot/API DTO，并修正 PlayerActions/Handoff 的 context identity 读取；通过 Task 4 回归 |
| Search | 缺少 typed groups/results | SearchResultsDto/SearchGroupDto，kind 严格 enum |
| Collection | 缺少完整 typed response | CollectionDto，保留 unavailable_song_ids/random_seed |
| Favorites | 缺少 FavoriteMembersDto | members/song_ids typed DTO |
| Playlists | PlaylistDto/ListDto 已有 | 扩展 detail/members 严格 DTO 和 readback |

不要在页面 Compose 中直接解 JSON 元素或用 Map 作为领域模型。未知 enum、缺少必需字段、响应 ID 不匹配必须成为协议错误。

## 4. 缓存失效依赖矩阵

| 资源组 | Identity | 失效触发 | 备注 |
|---|---|---|---|
| Library songs/categories/details/search/artwork | origin + generation + epoch + library revision | library revision 改变、epoch/origin/generation 改变 | 不按每次 sequence 更新失效 |
| Playlists | origin + generation + epoch + library revision + playlist revision | 两个 revision 任一改变 | 成员 Song metadata 也依赖 library |
| Favorites | origin + generation + epoch + library revision + playlist revision | 两个 revision 任一改变 | favorite members 不在每首歌独立读取 |
| History | origin + generation + epoch + history fingerprint | history fingerprint 改变，最长 60s TTL | 当前 API 没有独立 history revision |
| Queue/AutoPlay projection | canonical SnapshotStore | queue revision/playback autoplay/current context 改变 | 不再单独 GET queue 或缓存一份 Queue |
| Artwork bytes | server identity + library revision + song/artwork identity | 以上身份/版本变化，或 LRU/预算淘汰 | 单图 8MiB，全局 24MiB |

## 5. 跨页面网络请求复用验收

以同一 server identity、epoch 和 revision 为前提，按以下导航顺序记录实际 GET 计数：

| 顺序 | 操作 | 预期 |
|---|---|---|
| 1 | 打开 Home，读取最近更新 + History + AutoPlay | Song 列表、History 各读取一次；AutoPlay 不发 queue GET；封面按需读取 |
| 2 | 打开 Search，搜索包含 Home 已加载歌曲的查询 | Search results 读取一次；共用 Song ID 不再发第二次 Song detail |
| 3 | 打开 Library -> Songs | 读取其排序 query；相同 Song detail 命中共享缓存 |
| 4 | 打开一个 Album | summaries/members 读取一次；Song metadata/artwork 已缓存的项命中 |
| 5 | 打开 Playlist/Favorites | 列表资源按 path/revision 命中；已加载 Song 不重复 GET |
| 6 | 进入 Queue 并滚动到已见 Song | Queue 使用同一 Song resource owner，不建立 Queue 专属重复 GET |
| 7 | 回到 Home | 未过期且 revision 未变的 Home resources 命中缓存；横向位置按合同重置 |
| 8 | 改变 library/playlist revision | 对应资源失效并只重新读取受影响资源 |
| 9 | 改变 server origin 或 epoch | 旧资源与 in-flight 全失效；不能跨 server/session 命中 |

## 6. Task 5 Batch 顺序

| Batch | 内容 | 先决接口/边界 | 独立交付与退出条件 |
|---|---|---|---|
| B0 | Typed DTO、API path builder 与只读 RuntimeReadApi | 当前服务端固定基线和本合同 §3 DTO 缺口矩阵 | 所有 Task 5 GET 路由有严格 DTO 和 route/parser tests；不改页面、不写业务代码 |
| B1 | AppRuntime 全局 ResourceMemoryCache；融合 QueueSongCache、QueueArtworkThumbnailCache 与 PlayerArtworkMemoryCache | B0 typed reads；AppRuntime/RuntimeReadApi/ snapshot identity | single-flight、身份隔离、revision invalidation、LRU/预算和队列/播放器共用数据可独立测试 |
| B2 | Home 数据层与投影：最近更新、持久 History、AutoPlay occurrence | B0+B1；Home projection data contract | 无 UI 的纯投影与 controller tests 通过；Home 三段数据只依赖服务端与共享缓存 |
| B3 | Home 原生 UI：三等高分区、横向封面卡片、空/错误态、滚动复位 | B2 state models | UI tests 证明三段占满、全部横向可浏览、离开 Home 复位；Home actions 的入口已定义但不实现独立业务规则 |
| B4 | Library 分类/歌曲列表/详情与 Search 输入/分类结果 | B0+B1；Library routes 和 Search API mapping | 页面只读导航完整；Songs 第一；排序由服务端；Search stale response/route back tests 通过 |
| B5 | Song menu、集合 Play All/Shuffle、Home 最近更新/History 集合操作及 AutoPlay occurrence 播放 | B1+B2+B3+B4；MutationCoordinator | 写入统一 coordinator、fresh gating、unknown retry identity 和 snapshot readback tests 通过 |
| B6 | Playlist 与 Favorites 浏览/CRUD/成员编辑/收藏切换 | B0+B1+B4+B5；Playlist/Favorites API | system playlist 限制、完整排序 payload、收藏读回和错误状态 tests 通过 |
| B7 | 全页面集成、网络缓存复用/性能审计、Task 4 regression、设备验收 | B0–B6 | clean build、unit/instrumentation、跨路由 GET 计数、真机手动门与边界审计齐全；Task 5 才能申请退出门 |

Batch 不得打乱依赖顺序。每个 Batch 的完整文件清单、接口名、测试步骤和提交门详见 [Task 5 Implementation Plan](../plans/2026-10-31-task-5-implementation.md)。每批独立提交到 task5 分支；未通过当前 Batch 的机器验证不得开始下一 Batch。不得创建 CI 或远端构建任务。

## 7. 验收门与证据

### 自动化

- 单元测试：纯投影逻辑、排序保序、album grouping/dedup、history identity、AutoPlay occurrence identity、DTO parser、cache key/identity/invalidation/eviction。
- Repository/runtime 测试：HTTP path/query、single-flight、请求上限、revision 失效、迟到结果丢弃、错误不缓存、成功 mutation readback。
- Compose UI 测试：三分区可见、横向滚动、离开 Home 后回到 index 0、Library 分类排序、Search 结果空/错/加载、Playlist/Favorites 按钮可用/禁用。
- Regression：Task 4 Player/Queue、mini player、App Shell navigation、snapshot sequence/epoch 规则。

### 真实服务只读联调

- 比对 GET /api/library/songs modified_at desc 的响应顺序与 Home 第一 rail。
- 比对 GET /api/history 的 started_at/history_id 顺序，并确认 GET /api/history/played 不被误用。
- 读取真实 snapshot queue，确认 Home 只呈现 AUTOPLAY pending 项；不调用播放/清空/补队列来验证展示。
- 对封面 missing 和真实图像响应验证占位及 MIME。

### 人工设备验收

- 主页三分区按顺序显示，图片为主要视觉内容，正文上下无多余空白；横向滚动可浏览全部可展示卡片。
- 离开主页进入其它页面/全屏播放器再返回，三条 rail 均从开头开始。
- 音乐库 Songs 位于第一个，其余七项顺序不变；歌曲排序四种组合有效。
- Search 可输入、清空、分类跳转和返回；旧结果不会覆盖新 query。
- 歌单创建/改名/删除/成员调整和收藏/取消收藏的结果与服务器读回一致。
- 断网/恢复、server origin/epoch 切换、空库、无封面、历史歌曲缺失时 UI 无假数据且写操作状态正确。

## 8. 暂不通过条件

- 主页任一模块的数据来源没有服务端合同或使用本地随机/假数据。
- Home history 使用 /api/history/played 或把未持久化 active_event 视作历史。
- “猜你喜欢”不是 server AutoPlay occurrence，或写操作用 song_id 取代 queue_item_id。
- Home 最近更新不是 modified_at desc，客户端又做了会改变服务端顺序的排序。
- Songs 未置于 Library 第一项或其它类别被删/重排。
- 每个 screen/ViewModel 各自拥有重复 resource cache/HTTP/WS owner；跨页面相同资源反复 GET。
- origin/epoch/revision 变更后迟到资源回写；stale cache 允许写操作。
- 增加 Task 5 未批准的 server endpoint、数据库或业务规则副本。
