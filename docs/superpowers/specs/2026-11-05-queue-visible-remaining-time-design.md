# Queue 可见待播剩余时间设计

## 目标
在 Queue 顶部显示从当前歌曲到队列当前可见待播集合末尾的剩余总时长。当前歌曲按服务端确认的播放位置扣除已播放部分，后续可见歌曲按完整时长相加。

## 行为边界
- 统计范围严格使用现有 QueuePresentation.visibleRows：当前业务条目（position = 0）及 Web 规则选出的 next。Played、未进入该投影的队列条目和仅在后台预取的歌曲均不计入。
- 手动队列优先及 AutoPlay 候选规则保持现状，不在 Android 端重新构造 Queue。
- 每个队列 occurrence 单独计数；同一 song_id 在可见行中出现多次时，每次都计入。
- 当前歌曲的剩余时长 = SongDto.duration - PlayerClock.positionSeconds，下限为 0；后续每首可见歌曲计入完整 SongDto.duration。
- 任一可见歌曲的 duration 缺失/无效、当前播放位置未被 PlayerClock 确认，或当前行无法确认时，显示 --:--，避免显示误导性的部分总和。可见行为空时显示 0:00。
- 显示格式沿用播放器现有的 m:ss 格式，小时数折算为总分钟数。

## 实现选择
1. **共享现有 PlayerClock（采用）**：把 PlayerClock 与 StateFlow 提升到 AppNavHost，Player 和 Queue 读取同一播放位置；AppNavHost 统一负责更新、前后台冻结/恢复和 100ms 展示采样。这样 Queue 使用与播放器相同的服务端观察和单调时钟，不产生第二个时钟。
2. **Queue 自建计时器（不采用）**：会与 PlayerClock 重复校准，且在切换 Player/Queue 时可能产生位置偏差。
3. **只使用快照 position（不采用）**：没有现有 PlayerClock 的短期平滑显示，进度会停留在最近一次服务端快照位置。

歌曲时长从现有 RuntimeReadApi.getSong(songId) 读取，来源是服务端 SongDto.duration，复用 AppRuntime 的 ResourceMemoryCache。查询只覆盖可见行的唯一 song_id，专门读取 Song DTO，不扩展 QueueSongCache 的专辑图预取范围。播放/队列快照仍以 MPD-Server 为唯一权威，不新增 API、数据库、状态 Store 或依赖。

## UI 与生命周期
- 100ms 时钟 StateFlow 只由独立的剩余时间标签收集，避免每次 tick 重组整个 QueueScreen。
- 时长数据按服务器 origin、epoch、library revision 和连接代次隔离；只接受与请求 song_id 匹配的成功响应。
- PlayerClock 的前后台行为沿用现有逻辑：后台冻结；回到前台刷新完整快照，并在所需时再做一次校准读取。
- Queue 作为独立路由或 Player/Queue 横向分页时，均使用 AppNavHost 的同一个 PlayerClock。

## 非目标
不修改 MPD-Server，不改变队列排序、AutoPlay、播放状态或时长 API；不统计 Played；不添加本地播放位置预测/持久化；不为读取时长额外请求 artwork。
