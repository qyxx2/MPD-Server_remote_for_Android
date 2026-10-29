# Task 4 合同矩阵与分析

> 状态：Task 4 合同矩阵/设计约束基线，已纳入用户确认的 B 方向、无顶部切换按钮、歌词替换主内容区域及 Played 与 current 同列表的原生惯性滚动。合同分析、视觉规格与逐 Batch 实施规划已完成；Batch 2 的实现与实际设备验收证据见 [Batch 2 Verification Report](../reports/2026-10-27-task-4-batch2-verification.md)。其余批次依各自退出门实施与验收，不能将矩阵中的 Batch 归属视为已完成证明。
>
> 分支：`task4`
> 关联视觉规格：[Task 4 全屏播放器与 Queue 视觉/交互设计规格](2026-10-27-task-4-full-player-queue-visual-design.md)

## 1. 权威合同与冲突解决

| 顺序 | 合同/来源 | 权威内容 | Task 4 允许细化的内容 | 不允许覆盖 |
|---|---|---|---|---|
| 1 | MPD-Server 当前 API/领域模型与实时协议 | 播放观察、control target、Queue occurrence/revision、资源、错误、幂等 | 原生呈现、输入草稿、滚动/拖动暂态 | 服务端播放事实、队列身份与语义 |
| 2 | Android 后端 API 合同 | 路由、DTO、HTTP/WS、错误、写入回执 | 对现有端点的调用编排 | 不新增端点、不假定 API 未承诺字段 |
| 3 | Web 行为继承合同 §§3–6、8–9 | 实际播放判断、mutation 恢复、Queue/Played、歌词/时钟、活动状态 | 原生交互、视觉和触控热区 | 不复制业务算法，不把 UI 暂态变为 canonical state |
| 4 | 产品/工程总规格与架构合同 | 页面职责、共享 Runtime、单一状态来源、技术边界 | Task 4 具体布局和页面内状态 | 不新增 runtime/socket/store owner/播放引擎/WebView |
| 5 | Task 3 视觉规格 | 主题选择、品牌色、Insets、安全区、触控尺寸 | 全屏播放器特有的版式和图标 | 不覆盖用户已冻结的全局主题、品牌色和系统栏规则 |
| 6 | 本 Task 视觉规格 | B 方向、封面/歌词/Queue 布局和手势 | 本阶段 UI 实现参数 | 不得以实现便利改变已确认的视觉/手势约束 |

## 2. 合同矩阵

| ID | 范围/行为 | 实施 Batch | 权威依据 | 实施约束 | 自动化验证 | 人工/设备验收 | 退出标准 |
|---|---|---|---|---|---|---|
| T4-01 | 实际播放事实 | B1, B3, B9 | Web 行为 §3；API playback observation | 只有 fresh、matches_current、bound occurrence、同步状态允许且实际状态 playing/paused 时才显示已确认当前播放；不得仅信 playback.state | fresh/stale/unknown、UNBOUND、EXTERNAL_DRIFT、SYNC_FAILED、NO_CANDIDATES 分支测试 | 真实 MS 下切歌、外部播放漂移、断线恢复 | 无误报播放，过期状态禁用依赖 fresh 的操作 |
| T4-02 | 当前曲目/封面/歌词一致性 | B0, B1, B4, B5, B9 | Web 行为 §§3、8 | 所有展示使用同一当前 occurrence 身份；切歌/target 变化时取消旧歌词/seek 草稿 | current occurrence 切换、迟到响应、旧歌词回包测试 | 连续切歌、快速切换歌词/封面 | 不串曲、不显示旧歌词或旧封面 |
| T4-03 | 全屏进入/退出与 Insets | B2, B8, B9 | Task 3 visual spec；产品总规格；Task 4 视觉规格 §2.1 | 长屏布局；状态栏/挖孔安全区；Player 运动层覆盖窗口物理顶部至底部并延伸到透明系统栏后方，不能置于已套 Insets 的父容器内；仅前景交互内容使用 safe-content padding；手势带从真实安全区之后开始；Player 从安全内容顶部手势带或专辑封面区域向下拖动退出，Queue 仍仅从顶部手势带退出；不拦截系统通知栏手势 | Insets/挖孔、全窗运动层 bounds、状态栏背后背景跟随下滑、不同尺寸、手势起始区域优先级、tap-vs-drag、与子控件冲突测试 | 目标真机状态栏/挖孔/下滑退出与系统返回 | 全屏画布及上边角与前景同步跟手；前景安全区正确；不遮挡系统栏、不误开通知栏、不误切歌词；退出不误触 seek、Queue 历史展开或拖拽 |
| T4-04 | B 方向封面布局 | B2, B9 | Task 4 visual spec §§2.1–2.2 | 大封面优先、控件简化、无重卡片；状态栏下方保持干净；取消顶部“正在播放/队列”；标题/艺术家/专辑递减；艺术家/专辑可跳转列表 | 可用高度计算、长标题、大字体、窄屏、无封面占位、无顶部控件测试 | 目标真机长屏比例、状态栏安全区与布局截图 | 控件不溢出、无大块无意义留白、顶部干净且层级清楚 |
| T4-05 | 进度与播放 controls | B3, B9 | Web 行为 §§3–5；API target contract | 展示时钟共享；seek 仅本地 draft；target/current 变化取消草稿；写操作通过共享 Mutation Coordinator | pause/resume/next/previous、seek target 失效、unknown outcome、幂等 key | 真实播放/暂停/上一首/下一首/seek | 无重复写入；操作后权威状态读回；unknown 不伪判失败 |
| T4-06 | 源文件音频参数 | B0, B1, B2 | Web 行为 §8；Song DTO | FLAC/bit depth/sample rate 居中放进度条下方，低字号、无边框；不得冒充 DAC 输出格式 | 缺失/未知格式、元数据切歌更新测试 | 有/无参数曲目与切歌 | 参数来源正确、布局稳定 |
| T4-07 | DAC/输出观察图标 | B0, B1, B2 | Web 行为 §§3、9；API output observation | 左下角线性图标；保留 unknown/stale/active 等真实状态语义；ACTIVE 不代表硬件发声/bit-perfect | 各 freshness/error/output state 映射 | 真实 DAC/服务端可用时观察；不具备设备证据时记录边界 | 图标不误报输出事实，触控与底部图标不重叠 |
| T4-08 | 底部三个入口 | B2, B4, B5, B9 | Task 3 theme；用户确认的布局 | 左 DAC、中间独立 now-playing glyph、右 Queue；无底框；仅当前项染色；播放按钮符号不复用 | 选中状态映射、触控热区、TalkBack label | 真机检查图标清晰度、触控和对比度 | 三个入口清楚且无语义冲突 |
| T4-09 | 歌词呈现/回切 | B0, B3, B4, B9 | Web 行为 §8；Task 4 visual spec §2.3 | 点击封面切换；隐藏曲名/艺术家/专辑，以歌词替换封面+元信息主内容区；歌词视口填充可用主内容区，每条 cue 最多两行，当前行放大/染色；左上角仅提供简洁小号水平左箭头回封面操作，外围触控热区为 48dp；进度/控制/格式/底部图标锚点不变。完整继承 Web：lrc/text（包括 format=text 但内容含有效时间戳）、内嵌时间轴、1–3 位小数/多 timestamp/offset/stable sort；有效 timed cue 点击时按 offset 抵消后走共享 seek；拖动查看立即暂停跟随，最后一次手势/滚动后 3 秒无新操作回到最新 active playback cue；plain text 可自由滚动，永不按 3 秒自动回位/高亮/seek；文本选择、drag/cancel 不触发 seek；missing/read_error/unsupported format/freshness unknown 分别表达 | Parser 与固定 Web 算法 fixture 等价；格式/时间戳/小数/offset/activeCue；cue tap vs drag/long press/text selection/cancel；3 秒计时 reset/timeout 时最新 active cue；plain text 超 3 秒保持位置；embedded/sidecar 的服务器返回状态；两行上限/长句/大字号/模式锚点 | 实际内嵌 LRC、普通内嵌文本、可读 fallback + read_error；点 cue 后 seek、拖动后 3 秒回位、纯文本自由滚动超过 3 秒、切歌隔离 | 行高亮只依赖 fresh playback clock；cue seek 不伪判成功；纯文本可自由浏览；滚动、seek 与模式切换不造成底部锚点跳动 |
| T4-10 | Queue 初始定位与重入 | B5, B8, B9 | Web 行为 §6；Task 4 visual spec §2.4 | Played rows 与 current/next 是同一 LazyColumn；初次进入 current(position 0) 为首个可见锚点，Played 在其前方、按 position 从旧到新；后接 Web next。退出再进入重置滚动位置到 current；UI state 不改业务状态 | current position、current 不确认、空队列、离开重进、重复 occurrence key、current 视口锚点 | 真机进出 Queue、不同数量 Played 和多曲目 | current 初始定位正确，历史顺序一致，滚动/退出不改变 Queue |
| T4-11 | Queue/Played 历史同列表滚动（方案 A） | B0, B5, B8, B9 | Web 行为 §6；Task 4 visual spec §2.4 | 复用 fresh snapshot；仅当 Played 数据不 fresh 时按现有读取规则请求一次 /api/history/played。所有真实 position<0 occurrence 直接加入同一 LazyColumn，内容顺序 oldest→newest→current→pending；position -1 紧邻 current。若 current/pending 行不足以填满视口，则以尾部透明、非 occurrence 的 layout spacer 保留可滚动范围。利用原生滚动与 fling 惯性，不再拦截/消费 pointer-up 的列表惯性。进入时预热最近历史元数据，向更早历史滚动时围绕 viewport 扩展有界预取。稳定 key、限并发预取与读取代际校验；不新增分页/API、不重复请求全量端点 | Played 顺序等价 Web；current 初始锚点；真实 Queue list 可滚动并保留自然 fling；fresh snapshot 不多发 Played GET；stale 数据最多一个 fallback GET；预热历史资源不依赖下拉触发；滚动只影响缓存范围、不提交播放/Queue mutation；迟到响应代际校验 | 真机轻扫/慢拖后自然惯性、从 current 进入历史、metadata 加载前后滚动稳定、历史/当前行菜单、真实历史有数据时检查排序 | 历史总是列表的一部分并可自然滚动；最新 Played 紧邻 current；无手势触发额外历史读取、无 mutation、无伪分页或虚假行 |
| T4-12 | Queue 拖拽排序 | B5, B7, B9 | Web 行为 §6 | 左侧 2×3 六点柄对齐；所有行可保留柄的视觉位置，但仅 pending position > 0 允许拖动；拖动是本地草稿，提交指定 occurrence/order；revision/current 变化取消 | 起始/目标 position、current/epoch/revision 变化、重复歌曲 occurrence、禁用柄无 pointer gesture | 真机长按拖拽、取消、提交和回读 | 顺序以服务端确认结果为准；Played/current 不可拖动 |
| T4-13 | Queue 行菜单/播放 | B0, B5, B6, B9 | Web 行为 §§4、6 | 右侧三点菜单按 Web 对所有可见行保留入口；Next/Add 仅对 position > 0 显示，Remove 仅对仍在 canonical snapshot 中的 occurrence 提交；最终操作合法性由服务端决定。点击行按 queue_item_id 播放，不按 song_id 搜索替代 | Play Now/Next/Add/Remove/Save/row play 的 occurrence、幂等与错误恢复 | 真机菜单、重复歌曲、服务端错误 | 菜单语义一致，无错播同名/重复曲目 |
| T4-14 | 清空待播/Stop 边界 | B6, B9 | Web 行为 §6 | Queue clear 不等于 Stop；仅由服务端决定待播清除范围，不能清掉本地 current/Played 来伪造成功 | clear 不调用 Stop、204 后刷新、AutoPlay refill、unknown outcome | 真实 Queue 清空与状态回读（用户授权后） | 服务端事实与 UI 一致，current 语义未被伪造 |
| T4-15 | 歌曲/专辑/艺术家导航 | B2, B9（列表本体归 Task 5） | Web 行为 §7；产品总规格 | 点击艺术家/专辑跳转现有对应列表；不在播放器里复制集合浏览算法 | 正确 ID/route、缺少元数据、返回播放器状态 | 真机跳转与返回 | 路由目标正确、返回后播放状态连续 |
| T4-16 | 资源缺失/异常 | B0, B1, B4, B5, B9 | Web 行为 §§3、8；服务端 SongResponse 与 media metadata 规则 | 无封面、无歌词、普通文本、未知 format、歌词读取失败、stale/unknown 分开表达；客户端使用服务端返回的 lyrics/source/status/format，不自行解析媒体 tag 或覆盖 sidecar/embedded 选择 | missing/read_error/unsupported format、带可显示 fallback 的 read_error、embedded timed/untimed text 与 plain text、迟到歌词响应、各状态下 seek/follow 禁用行为 | 实际缺失/失败资源样例；sidecar/embedded 返回值与错误提示 | 不崩溃、不假同步、不假播放、不把 read_error 隐藏成成功 |
| T4-17 | 操作活动/错误反馈 | B3, B6, B9 | Web 行为 §§4、9；Task 2 Runtime | 所有 mutation 走共享协调器和共享活动模型；页面退出/重建不丢 mutation owner | pending、success 后 readback 失败、unknown、重试复用原 key | 断网、超时、重连及结果未知 | 无页面级 mutation owner，无新 key 静默重发 |

## 3. 已核对的 Web 实现证据

核对工作区：`/home/Gold/WorkSpace/MPD-Server/MPD-Server`；分支 `main`；HEAD `2ad8445`（与本仓库记录的行为合同快照一致）。本轮仅读取源码，没有修改该仓库。

- `web/src/components/queue/queuePresentation.ts`：`deriveQueue` 用 `position === 0` 识别业务 current；确认播放图标还要求 freshness/actual_freshness 为 fresh、matches_current、bound_queue_item_id 相等、允许的 sync_status 与实际 playing/paused。Played 使用 `position < 0`，按位置从新到旧排序后再取展开前缀并反转为显示顺序。待播项只使用 position > 0；手动项优先作为 next，启用 AutoPlay 时最多追加一个自动候选。
- `web/src/stores/queueUi.ts`：Queue 进入时将 `expandedCount` 重置为 0；现有 `reveal()` 每次增加一条历史记录；epoch、playback context 或明确 Stop generation 变化会重置展开。Android 需继承这些领域语义，但将视觉展开实现改为连续 fractional progress。
- `web/src/views/QueueView.vue`：可见列表为展开的 Played + current + next；进入时滚动到顶。当前历史展开通过累计 pointer/touch 距离、每 64px 调用一次 `reveal()`；这是阶梯式体验的来源。Android 不继承该离散阈值和“释放后以整条记录计数”的状态模型，而是连续位移跟手、释放时冻结精确可见高度。
- `server/app/api/history.py`：`GET /api/history/played` 无 `limit`/`offset`/`cursor` 查询参数，调用 `HistoryService.list_played()` 并返回整个当前会话 Played 队列。
- `server/app/api/playback.py`：`GET /api/playback/queue` 同样不接受分页查询参数，返回当前 Queue 全部 occurrence。当前后端合同因此不支持真正的 server-side 有界历史分页；Task 4 不能假设存在该能力。
- `web/src/components/queue/queueActions.ts`：播放指定行使用 `/api/playback/queue/items/{queue_item_id}/play`；拖动仅接受 position > 0 项目，提交前检查 epoch、generation、Queue revision 等 baseline；删除、移下一首、移队尾和清空均通过现有 Task/Mutation owner。
- `web/src/components/queue/QueueOccurrence.vue`：当前 Web 将拖拽柄仅渲染给 position > 0 的待播项。Android 的视觉合同可为 current/Played 保留对齐用的六点弱化图形，但这些行必须没有拖动交互。

以上源码核对是矩阵依据，不等同于 Android 实现或测试验收。

## 4. 必须保持的合同约束

1. **Queue 数据规模与 API 事实：** 当前 Queue snapshot/资源是 occurrence 列表权威；歌曲 DTO/元数据读取遵循现有 API。不得臆造分页端点或把全部历史另存本地。
2. **下滑退出与历史展开互斥：** Player 从 WindowInsets 确定的安全内容顶部手势带或专辑封面图像区域开始向下拖动退出；手势不得覆盖曲目信息、进度/控制、底部入口或歌词滚动区。不从系统状态栏内部拦截手势。Queue 仍仅由顶部手势带下滑退出，列表正文开始的下拉只连续展开 Played。任何一次手势不得同时执行这两种行为。封面点击和向下拖动必须通过 tap slop/方向判定分离。
3. **Queue 视觉状态与业务事实：** 当前行高亮及“正在播放”符号必须基于服务端观察确认状态；freshness stale/unknown 时明确表现不确定，不能用亮色状态伪造已确认播放。
4. **源文件参数与 DAC 状态：** 两者语义完全独立；源文件参数不从输出观察推导，输出图标不从源文件格式推导。
5. **歌词视口不固定行数，不可牺牲可读性：** 视口填充可用主内容区；每条 cue 最多两行，长句第二行水平滚动显示余下文本，不用缩小字号硬塞固定行数。
6. **连续历史 reveal 与请求边界：** pointer movement 不得逐帧触发网络请求；每帧只更新轻量 reveal state。只有在 Played snapshot 不 fresh 且尚未读取时，才按数据代际合并发起一次现有全量读取；pointer movement 与 reveal 窗口边界本身不发网络请求。未就绪时通过固定高度 placeholder 跟手。当前 API 的 `/api/history/played`、`/api/playback/queue` 都没有分页参数；因此复用 fresh snapshot，必要时最多按资源 freshness 发起一次现有全量读取，再由 Android 在内存中按有限窗口分批展示；不得重复调用全量端点假装分页。若未来要真正限制服务端响应量，需要单独修改并冻结服务端/API 合同，不纳入 Task 4 的现有边界。
7. **释放状态没有 settle：** `ACTION_UP` 或 `ACTION_CANCEL` 后立即停止手势驱动，保存当下的浮点 reveal 高度与列表偏移；不吸附、不 settle、不回弹、不在触摸结束时改变行数或重新计算另一套整数布局。下一次拖动从该视觉状态继续；只有离开 Queue 重新进入、Queue revision/epoch/playback context 按合同重置时才可清理对应的 UI reveal state。
8. **历史数据固定采用方案 A：** `GET /api/history/played` 和 `GET /api/playback/queue` 当前均无 `limit`/`offset`/`cursor`，前者返回完整当前会话 Played 列表。Task 4 只复用 fresh snapshot；相关数据不 fresh 时按现有规则读取一次全量 Played，再由客户端限制逐步显示的窗口。不得新增服务端分页、重复请求全量端点模拟分页，或把客户端分段显示称为有限网络 batch。
## 5. 当前阶段边界（用户明确要求）

- Task 4 合同矩阵、交互约束、视觉规格与逐 Batch 实施计划已建立：`docs/superpowers/plans/2026-10-27-task-4-full-player-queue.md`。B0–B2 已实施；当前播放器展示与 Queue 导航壳的验证状态见 `docs/superpowers/reports/2026-10-27-task-4-batch2-verification.md`。Queue 数据主体、播放操作、歌词与历史展开仍属于后续批次。
- 本地 Web/API 的 Queue/Played 数据形态、当前 playback/target DTO、歌词 DTO 已核对并用于该计划；开始实现时在 Batch 0 按现有合同做具体映射验证，发现冲突需先记录 ruling，再继续。
- 用户额外授权在 Task 4 Batch 1 前增加有限的 MS server-origin 设置入口，仅含 IPv4/IPv6/域名输入、格式验证、本地持久化、应用到共享 Runtime 与启动恢复；不属于播放器/Queue Batch，也不替代 Task 6 的完整服务器系统设置。设计与计划见 [MS 服务器地址设置设计](2026-10-27-ms-server-address-settings.md) 和 [实施计划](../plans/2026-10-27-ms-server-address-settings.md)。
- Batch 2 未执行真实播放/Queue 写操作。只读 MS 联调已单独验证已保存 HTTP origin 可读取艺术家接口并恢复 fresh canonical snapshot；这不代表播放/Queue 写操作或物理音频输出已验收。

## 6. Batch 计划索引

逐 Batch 目标、文件边界、测试先行步骤、依赖关系与退出门见 [Task 4 Implementation Plan](../plans/2026-10-27-task-4-full-player-queue.md)。矩阵中的 Batch 标记只说明责任归属，不代表实现或验收已完成。B9 负责逐项核对 T4-01 至 T4-17 的机器/人工证据；未运行或未覆盖的场景须标记 BLOCKED/NOT COVERED。

## 7. Batch 5 实施证据（2026-10-28）

- **T4-02 / T4-10 的 Batch 5 部分：** `QueuePresentation` 读取 canonical snapshot 并沿用 B1 `PlayerPresentation` 的实际播放确认条件；Queue 当前项使用 position 0，后续行遵循 Web MANUAL-first/AUTOPLAY-fallback 规则；Lazy row key 为 `queue_item_id`。单测覆盖重复 `song_id` 的多个 occurrence、paused/current 状态、无确认/空/坏 snapshot；Compose 测试覆盖 current-first、stale 未确认、无 current 时不伪造 current 与导航重入。
- **T4-11 的 Batch 5/B8 correction 范围：** canonical fresh snapshot Played 不额外请求；snapshot 非 ready 时通过共享 RuntimeReadApi 使用一次 GET /api/history/played，核对 epoch/connection/origin 后才接受回包。B8 correction 将已取得的 Played occurrence 放入 current/pending 所在 LazyColumn，按 oldest→newest→current→pending 排列，并采用原生滚动惯性和触摸前 metadata 预热；具体验证以 2026-10-30 的 correction report 为准。
- **T4-12 / T4-13 的 Batch 5 部分：** row 以 occurrence ID 渲染，视觉拖拽柄禁用，尾部三点图形暂不交互；拖拽提交仍归 B7，菜单/播放写动作仍归 B6。
- **验证报告：** [Task 4 Batch 5 Verification Report](../reports/2026-10-28-task-4-batch5-verification.md)。Batch 5 自动化通过不替代 B9 对整个合同矩阵的退出验收；未覆盖的实时服务器 Queue 视觉检查如实保留为未覆盖项。

## 8. Batch 6 实施证据（2026-10-28）

- **T4-13：** 新增 QueueActions intent builder、occurrence row 点击播放与三点操作菜单。当前 Web 的具体语义已对照 QueueView.vue/queueActions.ts：所有可见行保留菜单；position > 0 才出现 Next/Add；Remove 对仍存在于 canonical queue 的可见 occurrence 发出请求，由服务端最终决定合法性。行播放和 Delete 都用 queue_item_id 而不是 song_id。URI path escaping 对齐 JavaScript encodeURIComponent 安全字符集合。
- **T4-14：** Clear 仅提交 DELETE /api/playback/queue，没有 /api/playback/stop 路由调用，也不在本地改写 Queue current/Played。二次确认取消不产生请求；操作结果只由共享 Runtime snapshot/readback 反映。
- **T4-17：** 所有 Queue writes 使用现有进程级 MutationCoordinator 和共享活动模型，没有 Queue 自有 HTTP client、Task/Mutation store 或状态副本。成功回执后保持 SYNCING 直到共享权威状态 readback 完成；readback 失败保留“写入已确认 + syncError”，不伪装为写入失败。Playlist 保存后还通过既有 RuntimeReadApi.getPlaylists() 执行 GET /api/playlists；若此读取失败，UI 仅提供“重试读取”，不再次 POST；切换服务器后也不能再次提交原保存。
- **未知结果与重试：** Queue mutation 在提交时记录基于 epoch、Runtime generation、explicit-stop generation、Queue revision、occurrence ID/song/position/source/playback-context 的 retry scope。共享活动面板仅当原 origin 和 retry scope 仍有效时启用显式重试；它复用原 intent、幂等 key、method/path/payload。相同连接下普通 read 变 stale 不单独使 intent 失效；断开、generation/epoch/revision/occurrence/context 变化或 snapshot freshness UNKNOWN 均阻止旧 intent 重发。
- **证据路径：** 单测与编排测试见 app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueActionsTest.kt、QueueMenuPresentationTest.kt、app/src/test/java/io/github/qyxx2/mpdserver/remote/runtime/QueueMutationScopeTest.kt、MutationCoordinatorTest.kt、RuntimeReadApiTest.kt、ActivityPresentationTest.kt；Compose instrumentation 见 QueueScreenUiTest.kt 与 QueueActionsUiTest.kt。最终自动化结果及 NOT COVERED 范围以 Batch 6 Verification Report 为准。
- 本 Batch 未发送到已配置的 MS 的实际 Queue write、清空、播放/切歌或歌单保存；所有写路由由 deterministic 单测/Compose fake 验证。真实 live Queue 操作/安全恢复验收需另行授权，不以自动化测试替代。
