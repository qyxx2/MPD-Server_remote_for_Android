# Android 客户端现有 Web 行为继承规格

> **状态：权威行为迁移合同（基线快照）。** 迁移到 Compose 的是 API 使用、操作语义、状态判断、错误恢复与交互行为，不是 Vue 组件或 DOM/CSS。
> 核对基线：`qyxx2/MPD-Server/main` commit `2ad84454ceb70817bd996c92b590cfa2d9e9b128`（2026-10-18）。

## 1. 继承原则

- 服务端快照、API 回执及资源是唯一业务事实。Android 可有本地 pending、输入、滚动、seek/drag draft、展示模式和动画等暂时 UI 状态，但不可将其伪装为已提交的服务端事实。
- 页面不各自建立播放状态、realtime socket、mutation retry 或活动通知 owner；它们共享同一客户端状态 store 与操作协调器。
- 领域规则以现有服务端合同和 Web 当前行为为准；有疑问先核对上游源码与测试，不从视觉截图推断业务。

## 2. 连接和快照生命周期

1. 新 WebSocket 连接的第一个有效帧必须是完整 snapshot；在接纳 snapshot 之前，客户端不可开放要求 fresh 状态的写操作。
2. 同一 epoch 内，sequence 增大的 invalidate 是重读信号；它不是可以合并的状态 patch。按需 GET `/api/state`，合并同时进行的相同读取，防止并发返回乱序覆盖。
3. 检查 epoch、sequence、library/playlist revisions、library_sync generation 和 explicit_stop_generation。更旧快照不可回写覆盖已接受水位；GET 在等待时若水位再次提高且当前快照未覆盖它，则需要再次读取。
4. 网络中断、初始读取超时、协议错误或重连期间可保留最后已知事实作 stale 展示，但需禁用依赖当前确认状态的 controls。重连必须用新完整快照恢复可信状态，旧连接迟到响应不可写入新连接。
5. HTTP 200 本身不代表外部播放观察是 fresh。所有 fresh/stale/unknown、observed_at 和 error 字段按服务端实际值呈现。

### 客户端进程死亡与重启

- 导航、Composable 或 ViewModel 重建不能丢失当前进程中的 mutation owner；共享操作协调器必须比页面生命周期更长。
- 当前 Web 的操作记录由运行时内存维护，并不形成跨进程持久化的 mutation journal。Android 首期沿用该边界：进程被系统杀死或应用崩溃后，不恢复旧快照为权威状态，也不自动重放重启前尚未确认的写请求。
- 重启后先建立新 realtime 连接并取得完整权威快照，再重读受影响的资源。若无法据此判定先前写操作是否生效，应明确提示结果仍未知，不能静默生成新 `Idempotency-Key` 再次提交；在用户确认新的操作意图前不得自动重试。
- 跨进程保存并恢复原 `method/path/payload/key` 的持久化操作日志不属于首期范围。若未来需要该能力，必须另行冻结保留期限、隐私边界、服务器幂等记录有效期及恢复交互，不能仅通过持久化完整状态快照实现。

## 3. 什么才算实际正在播放

当前 Web 只在连接可写、`playback_observation.freshness` 与 `actual_freshness` 为 fresh、`matches_current == true`、存在 `bound_queue_item_id`，同步状态为 `CONFIRMED` 或 `NO_CANDIDATES`，且实际状态是 playing/paused 时，才将业务 `current_song` 当作已确认实际曲目。

`playback` 或 `current_song` 单独存在不证明当前 MPD 输出正播放此曲。UNBOUND、EXTERNAL_DRIFT、UNCONFIRMED_STOP、SYNC_FAILED、stale/unknown 等必须表现为未确认/诊断/最后已知，不可假装播放中。封面、歌词高亮和控制能力也不得使用过期 occurrence。

## 4. Mutation、幂等和操作结果

- 每个明确用户意图创建一个 mutation intent 和 Idempotency-Key。相同意图的网络重试复用原 key、method/path/payload；新的用户意图才创建新的 key。
- 请求 pending 时阻止相互冲突的重复控制。操作记录归共享协调器管理，导航和 Composable 生命周期不能让其丢失。
- 网络 timeout、连接中断或返回格式无法解析时结果可能 unknown，不能判为明确失败，也不能自动用新 key 再发。向用户提供重试原 intent 或重新读取权威状态。
- HTTP 业务错误是明确失败，保留 error code 并读取受影响状态/资源。写成功后如果快照/资源读取失败，应显示“操作已确认、状态尚未同步”，不能把成功改判失败或叫用户用新 key 再点一次。
- 204/局部 mutation 回执不可用来拼造完整状态。新 epoch 下旧操作上下文不得迁移成新 mutation；需要明确旧操作结果待重新读取。

## 5. 播放 controls 与 current target

- pause/resume/seek/next/previous 能力由最新实际观察、绑定和可用 target 决定，服务端执行时仍会重新验证。客户端预检不是安全保证。
- resume 必须携带服务端 `control_target`，继续原 occurrence，不可退化成重新播放歌曲或创建新队列。
- seek 使用操作开始时的 target。若 current/绑定变更或 target 失效，取消本地拖动草稿并重读，不得把旧 seek 转发到新 current；后端未提供保护能力时不可静默 fallback 到无 target 的 seek。
- 进度拖动只改本地 draft/preview；canonical progress 仍来自服务端观察。seek 未解决期间阻止重复拖动，并在 current 身份变化时清除草稿。
- 明确 Stop 是用户意图；实际 STOPPED 本身不代表用户执行 Stop。Queue 清空不是 Stop。

## 6. Queue / Played

- 保留服务端 Queue occurrence、position、source (MANUAL/AUTOPLAY)、context、revision 和完整成员。相同歌曲可有多个 occurrence；操作指定 `queue_item_id`，禁止用 song_id、URI 或标题替代身份。
- `position == 0` 表示业务 current；`position > 0` 是 pending，Played 区域语义以当前服务端/领域合同为准。视觉投影不修改服务端队列。
- 点击某 Queue 行播放指定 occurrence，调用 `POST /api/playback/queue/items/{queue_item_id}/play`；不可先用 song_id 搜索相同曲目再选择另一个 occurrence。
- 仅 position > 0 的待播项可拖动排序。排序只是本地草稿；epoch、连接世代、Queue revision、current 或成员变化时取消。服务端负责最终校验及排位。
- `DELETE /api/playback/queue` 按服务端语义清除待播，不代表 Stop，也不应让 Android 删除 current/Played 的本地缓存来伪造状态。之后服务器可能继续填充 AutoPlay。
- 保存 Queue 为歌单只提交 `{name}`；服务器在其事务边界读取完整待播集合（包括 UI 可能未展示的候选），不含 current/Played。客户端不得只提交可见行、擅自去重或自己写歌单成员。

## 7. Collection、Library、Playlist 和 Favorites

- CollectionRequest 的 source、排序、随机选择、不可用成员及 Playback Context 由服务端解析。Android 负责发起明确意图、展示返回资源和刷新，不复刻算法。
- 歌单、收藏成员、顺序、重复成员限制和系统歌单约束以 API 返回/错误为准。不要本地保存另一份可独立编辑的业务状态。
- 搜索分类 groups 和歌曲 id 来自服务器；为了 UI 读取 song DTO 可以额外 GET，但本地拼出的结果不是新的曲库权威。
- `Song` 中 lyrics/format/source/status、artwork 和 availability 都是服务端事实。MISSING、UNREADABLE、无封面、无歌词、歌词读取失败不能混成一种状态。

## 8. 歌词与展示时钟

- 全屏播放器通过原生 Compose 展示封面和歌词。点击封面切换两种模式；禁止 WebView。
- 继承当前解析行为：LRC/text 标签、`[mm:ss]`、分数秒及 `[offset:...]`；无有效时间戳的普通文本不能伪造逐句同步。
- seekbar 与同步歌词共享同一个展示时钟。可对 fresh 服务端样本做展示性插值，但不能借本地时钟修改 canonical state、触发 next 或断言歌曲已自然结束。
- 手动滚动歌词时暂停自动跟随；当前 Web 在用户停止操作约 3 秒后尝试恢复。文本选择不得意外 seek；点歌词只有在 target 和 seek 能力有效时才提交。
- 歌词读取失败但有可显示文本时可以显示，同时保留失败提示。没有歌词、普通文本、不能同步、进度未确认应分别表达。
- 源文件 codec/bit-depth/sample-rate 与实际输出观察分开显示。输出 ACTIVE 只代表后端观察到启用，不证明硬件实际发声或 bit-perfect。

## 9. 统一活动与应用内状态环

- Web 当前以共享活动 store 汇总播放操作、连接/读状态、资源、曲库同步、歌单/收藏、封面/歌词等活动，状态包括 pending、syncing、unknown、failed、retired、success、info，并可能包含 warning 标记及 retry/read 行为。
- Android 同样使用共享活动模型，让 ring 与详情面板只是这些事实的可视投影。不能让每个页面创建独立状态/错误体系，也不能因活动列表只显示近期几项就丢弃未解决操作的 owner。
- 用户确认状态环只在 MPD-Server Android 应用内部全局显示：不实现跨应用悬浮窗，不请求悬浮窗权限。
- 状态环的精确颜色优先级、动画曲线和圆环尺寸默认值待 Android UI 细化规格决定；状态来源必须是共享活动 store、连接状态和服务端 freshness/observation。点击状态入口打开统一详情，重试只能复用原 intent/key。

## 10. 当前 Web 参考实现

- 网络/实时/DTO：`web/src/services/api.ts`、`web/src/services/realtime.ts`、`web/src/services/wire.ts`、`web/src/types/api.ts`
- 状态和活动：`web/src/stores/player.ts`、`web/src/stores/notices.ts`、`web/src/runtime/taskActions.ts`
- 播放：`web/src/runtime/playerSession.ts`、`web/src/components/player/playerFacts.ts`、`playerActions.ts`、`playerClock.ts`、`lyrics.ts`
- 队列：`web/src/components/queue/queuePresentation.ts`、`queueActions.ts`、`web/src/views/QueueView.vue`
- 页面/布局：`web/src/views/PlayerView.vue`、`SearchView.vue`、`LibraryView.vue`、`PlaylistView.vue`、`FavoritesView.vue`、`SettingsView.vue`、`web/src/components/layout/AppShell.vue`、`AppBar.vue`、`BottomNavigation.vue`、`PlaybackStatus.vue`
- [播放/Queue 领域规格](https://github.com/qyxx2/MPD-Server/blob/2ad84454ceb70817bd996c92b590cfa2d9e9b128/docs/superpowers/specs/2026-09-24-playback-model-queue-semantics-design.md)
- [Library/Playlist/Search 规格](https://github.com/qyxx2/MPD-Server/blob/2ad84454ceb70817bd996c92b590cfa2d9e9b128/docs/superpowers/specs/2026-09-24-library-playlist-tag-search-design-2-1.md)
- [系统/输出规格](https://github.com/qyxx2/MPD-Server/blob/2ad84454ceb70817bd996c92b590cfa2d9e9b128/docs/superpowers/specs/2026-09-24-system-architecture-playback-output-design.md)
- [全局 Web UI 合同](https://github.com/qyxx2/MPD-Server/blob/2ad84454ceb70817bd996c92b590cfa2d9e9b128/docs/superpowers/specs/2026-10-03-web-pwa-visual-design.md)