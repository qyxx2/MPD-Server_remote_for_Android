# Task 5：主页、搜索与音乐库业务合同

> **状态：Task 5 范围/产品/数据合同草案，待阶段验收前冻结。** 本文冻结 Task 5 的业务边界、数据来源、页面语义、缓存规则和错误恢复要求；不代替后端 API、Web 行为与客户端架构权威合同。与既有规格有冲突时，按本文明确列出的 Task 5 局部覆盖项执行，其余仍遵守既有合同。
>
> **核对基线：** Android 仓库 Task 5 起点 4e33c80（2026-10-31，task4 已并入 main）；MPD-Server main 为 2ad84454ceb70817bd996c92b590cfa2d9e9b128。本次直接检查了两仓库工作区代码：server/app/api 下 library、history、playlists、playback 路由、对应 service/schema/repository，以及 Web 的 Library、Search、Collection、Playlist、Favorites 和 Queue presentation。
>
> **本 Task 不包含页面代码实现。** 先冻结合同与合同矩阵；实现计划和批次执行计划在本合同审核后继续细化。

## 1. 目标

将 Android 的主页从 Task 3 占位页升级为可验证的音乐发现入口，并完成搜索、音乐库、歌单和收藏的原生浏览/操作能力。所有业务资源和播放决策继续由 MPD-Server 提供；Android 负责把既有服务端事实组织成适合手机的原生 UI，利用应用级内存缓存减少重复 GET。

首期主页固定顺序为：

1. **最近更新**：按服务端 modified_at 降序获得最新修改的曲目，再以专辑图片卡片横向展示。
2. **播放历史**：按服务端持久 History 的最近播放顺序，转换成去重后的专辑图片卡片横向展示。
3. **猜你喜欢**：直接投影服务端 AutoPlay 随机候选队列，以专辑图片卡片横向展示。

主页三个分区纵向占满可用正文高度；每个分区内部横向滚动。离开主页后再次进入时，三个横向列表均回到起始位置。

音乐库维持 Task 3 既有八类入口与整体列表视觉，只将“歌曲”调整到第一个；其他分类相对顺序保持不变。

## 2. Approaches 与本阶段选择

| 方案 | 做法 | 取舍 |
|---|---|---|
| A. 逐页复制 Web | 逐项复刻 Web 页面、组件层级和资源读取 | 行为接近，但容易复制 Web 的页面状态与缓存 owner，原生导航/滚动体验也不自然 |
| **B. 原生界面 + 共享服务端语义（采用）** | 使用 Compose 原生布局；保留 Web/API 的数据来源、排序、操作、错误和状态权威；由一个 AppRuntime 级资源缓存复用 GET | 在不复制业务算法的前提下适应手机；允许视觉/导航实现与 Web 不同 |
| C. 新增服务端推荐/聚合接口 | 为主页增加专门推荐、最近更新专辑、分页历史接口 | 可能提升聚合效率，但现有接口已经能支撑首期；增加后端复杂度与跨仓库依赖，本 Task 不采用 |

**选择说明：** 用户明确要求优先复用现有 API，并指出 Web 与 Android 行为等价不是硬性要求。因此，业务语义和结果来源保持一致，页面结构、水平滚动和搜索交互采用 Android 原生方式。首期不增加服务端接口、不在 Android 生成推荐候选、不重建 Web 的业务规则。

## 3. 范围

### 3.1 In scope

- Home：最近更新、持久播放历史、AutoPlay 候选三个横向专辑封面分区。
- Home：封面/元数据加载、空/错误/过期状态、点击导航与候选播放入口、离开页面时滚动位置复位。
- Library：八种分类入口、歌曲列表与服务端排序、专辑/艺人/风格/年代/标签详情及成员、播放列表和收藏入口。
- Search：输入、分类结果、歌曲结果、分类详情导航、请求取消/去重和状态展示。
- Songs：元数据、可用性、封面、收藏标记和现有 Web 单曲菜单动作。
- Playlists：读取、创建、改名、删除（非系统歌单）、成员读取/添加/移除/排序。
- Favorites：读取成员、收藏/取消收藏、播放全部/随机播放。
- 所有歌曲集合的集合级播放/随机播放入口，遵守 Task 3 集合操作合同。
- AppRuntime 级、仅进程内的共享资源缓存；跨页面共享 DTO、歌曲和封面读取，按服务端身份/修订失效。
- 自动化验证、真实曲库只读联调、人工导航/显示/操作验收。

### 3.2 Out of scope

- 新增 MPD-Server endpoint、数据库表、迁移或服务端推荐算法。
- Android 本地音乐扫描、文件访问、音频解码或本地播放引擎。
- Room/SQLite/文件形式的业务资源持久缓存；业务数据不离线成为第二份权威。
- 自定义“猜你喜欢”算法、客户端随机洗牌、客户端历史写入或客户端 AutoPlay 补队列。
- Task 4 全屏播放器/Queue 的改造（只复用它们的 Runtime、歌曲资源与共享缓存）。
- Task 6 系统设置与服务端配置表单；Task 7 MediaSession；Task 8 全项目交付验收。
- 超出当前 API 能力的历史分页、专辑级修改时间字段或个性化推荐模型。

### 3.3 Anti-goals

- 不以静态假数据、占位封面或本地随机歌曲伪装真实数据。
- 不把 HTTP 成功、写请求回执或缓存命中等同于服务端当前状态已确认。
- 不让页面各自创建 HttpClient、WebSocket、状态 Store、MutationCoordinator 或独立业务缓存。
- 不让陈旧缓存数据启用写操作，不允许旧 origin/epoch/connection generation 的迟到响应回写。
- 不为满足首页视觉而重复请求同一首歌曲的详情/封面，或为历史记录逐条无界并发 GET。
- 不把 AutoPlay occurrence 当成普通歌曲 ID；候选操作必须保留服务器 occurrence 身份。

## 4. 主页合同

### 4.1 布局与滚动

- 正文由三个纵向分区构成，顺序固定为“最近更新”“播放历史”“猜你喜欢”。
- 三个分区共同填满主页可用正文高度；建议使用等权重分配，标题行紧凑，专辑封面在各自分区内占主要面积。不得在正文顶部/底部或分区之间加入无意义的大块留白。
- 每个分区使用独立的横向列表，可向右滚动浏览服务端返回的全部可展示项目；封面使用正方形显示，卡片有简洁标题/艺人辅助文字，保持图片为视觉主体。
- Home 正文不需要额外的纵向滚动容器；高度受限的设备应自适应缩小封面与行内间距，不得裁掉第三个分区。系统 Insets、AppBar、迷你播放器和底部导航仍按 App Shell 合同占位。
- 某分区无数据时，在该分区内部展示简洁空状态，不移除分区、不用虚构卡片填充。
- 离开 HOME route（包括进入搜索、音乐库、设置或全屏播放器）时，清零三个横向列表的 scroll state；重新进入时从第一个卡片开始。切换 Home 内部加载/刷新状态不应重置滚动。
- 页面返回、窗口重建或数据更新时，不允许三个列表互相共用 scroll state。

### 4.2 最近更新

**权威来源：** GET /api/library/songs?sort_by=modified_at&sort_order=desc。

- 使用服务端排序结果；不可用 last_scanned_at、客户端文件时间、歌名或客户端排序替代 modified_at。
- 服务端 SongResponse.file_mtime_ns 是文件修改时间事实；API 的 sort_by=modified_at 是排序参数。时间缺失项按服务端现有行为排在已知修改时间项之后。
- Home 将排序结果按专辑聚合成封面卡片：首次出现的专辑决定该专辑卡片顺序，因此专辑按其最新修改曲目的位置排序。保留服务端曲目顺序，不对结果再次本地排序。
- 专辑身份优先使用 album_id；无 album_id 的歌曲按 song_id 形成单曲卡片，不得把多首未知专辑歌曲错误合并。
- 卡片封面从该专辑的服务端歌曲中，按原服务端顺序选取第一首同时满足 availability_status=AVAILABLE 且 artwork 非空的曲目，调用既有 GET /api/library/songs/{song_id}/artwork；若没有符合条件的成员则显示明确默认占位，不得借用其他专辑封面。
- 缺失/不可读歌曲仍保留其服务端列表事实，不因 UI 过滤改变最近修改排序；播放入口按 availability_status 禁用不可播放成员。
- Home 任意分区中，只要卡片代表曲目具有非空 `album_id`，点击卡片/专辑封面就必须进入该 `album_id` 对应的专辑详情；不因卡片仅代表一首歌曲、被投影为 SINGLE_SONG，或该专辑仅包含一首曲目而改为单曲菜单。仅 `album_id` 为空/缺失时，才沿用 Web 单曲菜单/动作，不新增 Song Detail 业务页。AutoPlay 卡片仍保留独立的 occurrence Play 控件，点击卡片主体进入专辑不改变其队列项身份。
- 最近更新列表的数量不在客户端硬截断；按服务端完整列表形成全部可滚动专辑项。资源解码采用可见窗口与有限预取，不需要一次解码全部封面。

### 4.3 播放历史

**权威来源：** GET /api/history（持久 History），不是 GET /api/history/played。

- GET /api/history 当前由 HistoryRepository.list_history() 提供，服务端按 started_at DESC, history_id DESC 排序，返回持久 HistoryEvent；客户端保持响应顺序，不按本机时间重新排序。
- /api/history/played 是当前 Queue 中 position < 0 的 Played 视图，供 Queue 历史区域使用；它不是持久历史，不能用来实现 Home“播放历史”。
- 当前正在播放的 _active event 尚未写入持久 History 时，不应伪装成已完成的历史记录；只展示 /api/history 已持久化事件。
- 将历史事件的 song_id 与共享 Song 资源缓存中的 Song DTO 关联，不能为每条 history event 分别请求相同歌曲元数据。
- 卡片按事件顺序生成；存在 album_id 时对专辑去重，保留该专辑最近一次历史事件的位置和代表曲目。封面源在当前曲库同专辑成员中，按曲库服务端顺序选第一首 AVAILABLE 且 artwork 非空的曲目；显示 metadata 仍取该专辑最近一次历史事件的曲目。无专辑 ID 或历史 song 已从当前曲库移除时，按单曲/不可用历史项保留，不得静默丢掉该条历史。
- 历史事件缺少当前 Song DTO、封面或可用文件时，显示可辨识占位/不可用状态；不得用相似歌曲代替。
- 点击专辑卡片进入对应专辑详情；已从库中移除的历史项不可触发错误的播放 mutation。
- 当前 API 没有 limit/cursor。首期读取接口返回的完整历史，内存中按事件顺序构建去重视图；不自造分页参数。若真实历史规模造成响应/内存压力，应作为后端分页需求另开决策，不得在 Android 隐式丢弃旧记录。

### 4.4 猜你喜欢

**权威来源：** AppRuntime.snapshots 当前已接受的 FullStateSnapshot.wire.queue 与 wire.playback，不另请求 Queue API。

- 只使用 queue.items 中 position > 0 && source == AUTOPLAY 的服务端 occurrence。
- 只有 playback.autoplay_enabled == true 且 snapshot.playback.playback_context_id 非空时，显示候选；否则显示明确空状态。仅保留 queue item.playback_context_id 与 playback.playback_context_id 完全一致的 occurrence；queue item 的 context 为 null 时排除，不能猜测它属于当前上下文。不得本地生成推荐项、洗牌或补候选。
- 按服务端 queue position 升序保持展示顺序；每个 AutoPlay occurrence 独立显示一张封面卡片，不因专辑相同或 song_id 相同而合并。
- 只显示 AUTOPLAY，不把 MANUAL 项混进“猜你喜欢”。即使队列里同时存在手动项，也不改变 AUTOPLAY 自身的服务端顺序；其内容仍然只是服务端 AutoPlay 候选，不承诺它会越过手动队列先播放。
- 卡片数据以 song_id 查共享 Song DTO；卡片/播放动作的身份必须保留 queue_item_id。重复 song occurrence 不能用 song_id 代替 queue_item_id。
- 卡片的显式播放动作使用既有 POST /api/playback/queue/items/{queue_item_id}/play，经共享 MutationCoordinator 提交；成功后由服务端快照刷新显示，不本地删除/重排候选。
- 点击有 album_id 的候选卡片进入其专辑详情；无 album_id 的候选作为单曲卡片，不推造专辑详情。当前曲目元数据缺失、不可用、无封面或 metadata 尚未 fresh 时仍保留 occurrence 卡片和可解释占位，并禁用依赖该资源的动作。
- Home 不触发 AutoPlay refill，不修改播放上下文，不生成一个新的 CollectionRequest。
- AutoPlay rail 保留每一个 queue occurrence（即使相邻项属于同一专辑或 song_id 重复），不按 album_id 去重；否则会隐藏服务端队列项并失去单项播放身份。每张卡片展示该 occurrence 所属曲目的专辑封面。

### 4.5 Home 集合操作

继承 Task 3 §9.1 的集合级 Play All / Shuffle 合同，主页中真正构成歌曲集合的两个分区提供全部播放和随机播放：

- 最近更新：`POST /api/playback/collections/play`，`source_type=LIBRARY`、`sort_by=modified_at`、`sort_order=desc`。两种动作的排序基线相同，是否随机完全由服务端 `randomize` 决定。
- 播放历史：`POST /api/playback/collections/play`，`source_type=SONGS`、`song_ids` 使用持久 History 响应中按服务端顺序去重后的全部 song IDs。不能只提交可见横向窗口，也不能本地随机排序；服务端负责可用性过滤与随机选择。
- 猜你喜欢是服务端现有 Queue 的 AutoPlay occurrence 投影，不是新的 Collection。此区不提供 Play All / Shuffle，以免生成新的 Playback Context 覆盖当前 AutoPlay 上下文；每张候选卡片使用自己的 `queue_item_id` 执行播放。
- 集合操作在 snapshot stale、资源未 fresh、连接不 writable 或存在未解决 mutation 时禁用；提交后只以共享 MutationCoordinator 状态及服务端新快照为准。

### 4.6 Home 故障与 freshness

- 分区分别表达 Loading、Empty、Ready、Stale、Failed；单一区域失败不应清空其他成功区域。
- snapshot 断线/过期时，保留已缓存内容但明确标为 last-known/stale；播放、收藏等写操作禁用。
- library revision 变化后只刷新依赖曲库的 Home 数据；history 标记变化后刷新持久历史；queue revision 或播放状态变化后重新投影猜你喜欢。
- HTTP 超时/非 2xx/DTO 解码失败显示分区错误和可重试入口；失败数据不可缓存为成功。
- 封面 404 ARTWORK_NOT_FOUND 显示无封面，不应反复重试或误报网络失败；其他读取错误应允许显式重试。

## 5. 音乐库合同

### 5.1 分类入口与顺序

Task 3 视觉规格冻结的八类入口全部保留。本 Task 唯一改动是将“歌曲”前置为第一个，其余相对顺序不变：

1. 歌曲（全部歌曲）
2. 播放列表
3. 艺人
4. 专辑
5. 流派/风格
6. 年代
7. 标签
8. 收藏

不得删减类别、将首页“最近更新”作为音乐库类别，或为满足 Web tabs 顺序而恢复旧的歌曲位置。分类行仍沿用 Task 3 已冻结的图标、文字、触控目标、细分隔线和简洁列表视觉。

### 5.2 全部歌曲

- 使用 GET /api/library/songs，默认排序与 Web 一致：sort_by=title&sort_order=asc（显式带参数以保证请求身份与 UI 当前排序可见）。
- 排序入口至少支持 title|modified_at 与 asc|desc；当前字段/方向必须可见，触控区域至少 48dp。
- 所有排序由 API 参数传给服务端；不在 Compose 中重排 Song DTO。
- 所有歌曲行显示服务端返回的标题、艺人、availability、收藏状态和 Web 已有单曲菜单入口；缺少字段使用短占位而不丢失行身份。
- AVAILABLE 才允许播放；MISSING、UNREADABLE 保留列表并显示不可用状态，禁用 Play Now/Next/Add。
- 集合级“播放全部”和“随机播放”使用 POST /api/playback/collections/play，source_type=LIBRARY，排序参数遵从当前选定的非随机排序；随机顺序由服务端负责。
- 不把全部歌曲集合的播放/随机动作放进每一首歌的菜单。

### 5.3 分类列表与详情

- 分类列表使用现有 GET：/api/library/albums、/api/library/artists、/api/library/genres、/api/library/years、/api/library/tags。
- 详情成员使用现有 GET：/api/library/albums/{album_id}/songs、/api/library/artists/{artist_id}/songs、/api/library/genres/{genre_id}/songs、/api/library/years/{year}/songs、/api/library/tags/{tag_id}/songs。
- 使用服务端返回的 summary ID/name/song_count 和 Song DTO；不得按 UI 文本重新生成 source_id。
- 详情提供集合级 Play All / Shuffle，使用真实来源 ALBUM|ARTIST|GENRE|YEAR|TAG 与原始 source_id；排序、随机化、可用成员及 Playback Context 由服务端决定。
- 缺失分类 ID 返回 404/错误时显示错误状态与返回入口，不构造空成功列表。
- 专辑 summary 有 artwork descriptor，但实际图像仍通过既有 song artwork endpoint 读取；无 artwork 不显示相邻专辑图。

### 5.4 歌单

- 列表/详情/成员分别使用 /api/playlists、/api/playlists/{playlist_id}、/api/playlists/{playlist_id}/songs。
- 允许创建歌单；非系统歌单允许改名、删除。添加/移除成员和重排沿用现有 API/Web 行为，不因 is_system=true 而额外隐藏或禁用。
- 系统歌单属性 is_system 是服务端权威；当前服务端明确限制系统歌单 rename/delete（SystemPlaylistModificationError），Web 也只对这两项隐藏入口。成员添加、移除和重排仍由现有服务端 API 校验，Android 不扩大系统限制。
- 成员列表保持服务端 song_ids 顺序；详情元数据响应的成员 ID 顺序必须与 Playlist DTO 的 song_ids 对齐。数据未一致或任一必要资源未 fresh 时，不开放排序/编辑写操作。
- 歌单排序提交完整 ordered_song_ids 至 /api/playlists/{playlist_id}/songs/order；不得只提交可见窗口，也不得本地先永久写序。
- 添加/移除操作使用既有 playlist endpoint；成功后重新读取 playlist list、detail 和 songs，写请求 payload 不作为最终 UI 权威。
- 歌单详情提供 Play All / Shuffle，来源 PLAYLIST + playlist_id，由服务端解析成员和不可用歌曲。
- Duplicate song、system playlist restriction、playlist not found、reorder member mismatch 等 API 错误必须可解释，不能静默忽略。

### 5.5 收藏

- 收藏成员 ID 来源 GET /api/favorites/members；可播放集合可按需使用 GET /api/favorites。
- 每首 Song 的已收藏状态来自共享 favorites resource；不在每个 SongRow 单独请求。
- PUT /api/favorites/{song_id} 收藏，DELETE 取消收藏。都走 MutationCoordinator，回执后重新读回收藏成员/集合。
- 收藏页通过共享 Song DTO cache 解析元数据；已收藏但曲库缺失的 ID 要保留占位行，并能表达无法播放。
- 收藏集合提供 Play All / Shuffle，来源 FAVORITES，由服务端解析；不在客户端维护顺序/随机算法。

### 5.6 单曲动作

- 菜单沿用 Web 已有动作范围：Play Now、Play Next、Add to Queue 和 Favorite toggle；仅在服务端/API 能力与 Song 可用性满足时启用。
- Queue occurrence 专用操作继续走 queue_item_id API；普通 Song 菜单不能把 song_id 伪装成 queue occurrence。
- 操作执行/锁定/unknown/readback 遵守既有 MutationCoordinator；不得单独创建页面级锁或乐观更改 canonical snapshot。

## 6. 搜索合同

- 使用 GET /api/library/search/results?q=... 读取服务端分类 groups 与 song IDs；结果组 kind 限于 albums/artists/genres/years/tags。
- 通过共享 Song resource cache 解析结果 Song DTO；每个唯一 song_id 至多有一个在途 GET，且与其他页面共享同一条请求。
- 分类入口按服务端 groups 顺序呈现，点选后进入对应 Library 详情，并保留可返回的原查询词。
- 关键词为空/仅空白时不发起搜索；输入采用原生 IME/键盘行为。实现可做约 250ms debounce，以减少逐键请求；这是 Android UI 优化，不要求逐帧复制 Web route query 行为。
- 新 query、页面退出、origin/epoch/revision 改变时取消旧请求或使其结果失效；迟到结果不得覆盖当前 query。
- 展示 Loading、Empty、Error、Stale；不得从本地曲库模糊匹配冒充服务端搜索结果。
- 搜索结果中的单曲菜单、可用性、收藏与集合级 Play All/Shuffle 沿用 Library 现有行为；搜索集合播放来源使用 SEARCH + 原 query，不以客户端解析后 ID 列表替代服务端搜索集合。

## 7. 全局资源内存缓存合同

### 7.1 所有权和非目标

- 新增一个 AppRuntime 级 ResourceMemoryCache（名称可在实现中按现有代码风格调整），由唯一应用 Runtime 持有；导航页面、Repository、Home、Search、Library、Playlist、Favorites 和 Queue 共享。
- 缓存只存 GET 读取到的 DTO/资源与只读封面字节。它不是数据库，不保存到 DataStore、Room、SQLite、文件或磁盘，不跨进程重启恢复。
- 不缓存写请求 payload/回执作为资源真相；不乐观写缓存。MutationCoordinator 的权威同步/readback 完成后按 revision/fingerprint 失效或刷新。
- 不建立额外网络 owner：复用 AppRuntime 当前 RuntimeReadApi/OkHttp client，不创建第二个 HttpClient、轮询器或 WebSocket。
- Playback/Queue canonical snapshot 仍只由 SnapshotStore 保存；全局资源缓存不得复制 FullStateSnapshot 或独立维护 playback/queue 状态。

### 7.2 资源覆盖和 key

- GET 资源缓存至少覆盖：Song detail、全歌曲列表及其 sort query、分类 summaries、分类成员、Search results、Playlist list/detail/members、Favorites members/collection、持久 History 和 song artwork。
- 缓存键至少包含 HTTP method、规范化 path + query；同一 song 详情在 Home、Search、Library、Playlist、Favorites、Queue 使用同一缓存键。
- 每个 entry 同时记录资源身份：server origin、connection generation、server epoch，以及资源依赖的 revision/fingerprint。不能跨 server 或跨 session 复用 fresh 条目。
- 对资源采用依赖水位，而不是对每个快照 sequence 无差别清空：
  - Library/Song/Search/Artwork：library revision。
  - Playlist/Favorites：library revision + playlist revision。
  - History：history snapshot fingerprint（history availability/event identity）并设置 60 秒最大 TTL 作为缺少独立 history revision 的保护性刷新。
  - Queue/Playback：不另缓存，直接从 SnapshotStore 派生；以 queue.revision 和当前快照为准。
- origin、connection generation 或 epoch 改变时，取消旧请求并清空全部旧身份资源；迟到响应丢弃，不得写入新身份 cache。

### 7.3 请求合并、内存上限和淘汰

- 同一个 cache key 在同一 resource identity 下并发读取必须 single-flight；所有调用者共享同一 Deferred/结果，不产生 N 次 GET。
- GET 并发上限初值 3；请求失败/解码失败不缓存为 Ready；失败可由用户重试。
- LRU 仅淘汰已完成条目，绝不淘汰仍在途请求的身份记录。metadata entry 上限参考现有 QueueSongCache 的 512 项；实现需同时有总权重预算，防止大响应无限占内存。
- 图片字节预算复用现有边界：单图最大 8 MiB、全局 artwork bytes 最大 24 MiB；图片响应必须验证 image MIME 和 PNG/JPEG/GIF/WebP 签名。metadata 与 artwork 分开计量。
- 可见封面优先于预取项；预取并发受同一上限约束。快速横向滑动不能触发同一资源反复取消/重读。
- 超预算只淘汰图片字节/非必要 metadata，不可把有效 Song metadata 改写成“读取失败”；重新需要已淘汰图像时允许重新 GET。

### 7.4 Freshness 与失效

- 页面可在资源仍有效时直接命中 AppRuntime cache；跨页面返回不重新请求同一 identity/path。
- Snapshot stale/断线时，允许保留 last-known 数据作为只读展示，但 entry 必须标 Stale，所有依赖 fresh server state 的写按钮禁用。
- cache TTL 到期时保留旧内容但标 Stale，并在有可信 snapshot/连接时后台刷新；旧值不能在刷新期间继续作为写入前置条件。
- Library revision 提升：使 Library/Song/Search/Artwork 依赖项失效。
- Playlist revision 提升：使 Playlist/Favorites 依赖项失效。
- history fingerprint 改变或 History TTL 到期：使持久 History 失效。
- Queue revision/AutoPlay enabled/current context 改变：从最新 snapshot 重建推荐投影，不触发 /api/playback/queue 重复 GET。
- 任何迟到响应在写入 cache 前，必须复核 origin/generation/epoch 与对应 revision/fingerprint 仍相等；不匹配就丢弃。
- 资源成功 mutation 不直接将回执写成完整资源；必须等待 MutationCoordinator 的同步快照和资源 readback，再把资源标成 fresh。

### 7.5 与现有缓存融合

- 当前 QueueSongCache 已缓存 Song DTO/Artwork，并有 3 并发、512 metadata entry、单图 8MiB、总 artwork 24MiB 限制。Task 5 不再复制一套同功能缓存；应把它提升/迁移为应用级共享资源 cache，或令其成为共享 owner 的 Queue presentation adapter。
- 当前 PlayerArtworkMemoryCache / QueueArtworkThumbnailCache 负责解码图像的展示缓存。实现时可保留解码/bitmap 专用 cache，但底层 artwork bytes 必须共享同一 identity 与预算，避免 Home/Queue/Player 对同一 artwork 的并发重复读取。
- 所有页面只观察 cache entry，不拥有自己的资源缓存与数据刷新算法。

## 8. API/DTO 映射与约束

| 功能 | 既有接口/数据 | 关键字段/约束 |
|---|---|---|
| 最近更新 | GET /api/library/songs?sort_by=modified_at&sort_order=desc | song_id、album_id、file_mtime_ns、artwork、availability_status |
| 持久播放历史 | GET /api/history | history_id、song_id、started_at、ended_at、reason、session_id |
| Home AutoPlay | Snapshot playback + queue | autoplay_enabled、position、source、queue_item_id、song_id |
| 专辑/分类 | /api/library/albums、artists、genres、years、tags 和各自 /{id}/songs | summary IDs 不得根据名称推造 |
| Search | /api/library/search/results?q= | groups[kind, source_id, name, song_ids] + song_ids |
| Song | /api/library/songs/{song_id} | song_id、元数据、availability、artwork；响应 ID 必须与请求匹配 |
| Artwork | /api/library/songs/{song_id}/artwork | 原始图像 bytes；MIME/signature 验证 |
| Playlists | /api/playlists、/{id}、/{id}/songs | playlist_id、song_ids、is_system、timestamps |
| Favorites | /api/favorites/members、/api/favorites | 服务端 song IDs 和 CollectionResponse |
| Mutation | 既有播放/歌单/收藏 API | 统一 MutationCoordinator、Idempotency-Key 规则与同步/readback |

Android 当前缺少/不足的 typed DTO 包括：Album/Genre/Year/Tag list summaries、History list/event、Search results/groups、FavoriteMembers、Collection response 及其严格 wire parser。现有 PlaybackStateDto 使用 legacy context/position 字段，与服务端 playback_context_id/position_seconds 不一致；Task 5 B0 必须对齐此 DTO，并修正依赖 Playback Context 的 PlayerActions/Handoff projection，同时运行 Task 4 回归测试。实现时应补充强类型 DTO，不用无类型 JsonElement 在页面层解析业务含义。

## 9. 数据错误、空值和状态处理

- song_id == null 的 Song 不能作为播放目标，但可以作为列表展示项；稳定 key 用 file_uri 等服务端身份字段。
- album_id == null 不得和其它 null album 项合并。
- availability_status 的 AVAILABLE|MISSING|UNREADABLE 必须区分。
- artwork missing（404 ARTWORK_NOT_FOUND）、song missing（404 SONG_NOT_FOUND）、读取失败（500 ARTWORK_READ_ERROR）、非法 MIME/bytes 必须区分；不得 fallback 到另一首歌。
- 历史记录存在但当前 Song 不存在时仍保留记录；不把其显示成正在播放或可播放。
- 空数组与请求失败不同；只有成功且空的响应才显示“暂无内容”。
- 资源 stale 时保留 last-known 内容，但不可执行依赖它的 mutation。
- DTO 缺少必需字段、enum 未知、ID 不匹配或响应 envelope 不符合合同，应进入 ProtocolError，不降级成空列表。
- Retry 复用当前 API resource identity；如 identity 已变化，必须读取新资源，不能把旧请求的错误/成功投影到新页面。

## 10. 安全/架构边界

- 根导航和迷你播放器沿用 Task 3 App Shell；Home/Search/Library 仍显示迷你播放器和底部导航，Settings 不显示迷你播放器。
- 只有 AppRuntime 持有一个 RealtimeController、SnapshotStore、MutationCoordinator 和 ResourceMemoryCache。
- Repository 保持薄 API/DTO 层；collection sorting/randomization/playback context/AutoPlay/history/playlist rules 均属服务端。
- 所有资源 cache 只依赖已接受 snapshot 的 watermark；不能读取未确认的本地或旧 snapshot 作为写入授权。
- 页面离开只释放页面订阅/滚动/弹窗，不清空 AppRuntime cache 或断开 realtime。
- 不为本 Task 修改后端。发现 API 真实缺口需另立需求，不在客户端伪造参数。

## 11. Task 5 完成定义

- Home 三分区顺序、数据来源、封面映射、横向滚动、无空白布局和离开复位均有代码与测试证据。
- Home“最近更新”严格按服务端 modified_at desc；History 来自持久 /api/history 并保留返回顺序；猜你喜欢只显示服务端 AutoPlay pending occurrence。
- Library 八类别顺序为 Songs、Playlists、Artists、Albums、Genres、Years、Tags、Favorites；歌曲排序字段/方向走服务端 API。
- Search categories/IDs 来自服务端；Search result Song 元数据复用全局缓存；迟到结果不覆盖新 query。
- Playlist/Favorites 的写操作经共享 MutationCoordinator，成功后 server readback；system playlist 和 unavailable song 约束正确。
- AppRuntime cache 跨页面生效、有请求合并、身份隔离、watermark 失效、LRU/内存预算和 stale-write 禁止。
- 不新增 API、业务数据库、本地 AutoPlay 或本地播放/历史权威。
- 自动测试覆盖合同矩阵；实际设备人工检查主页三分区、横向滚动复位、歌曲优先顺序、搜索、曲库、歌单/收藏与错误态。
