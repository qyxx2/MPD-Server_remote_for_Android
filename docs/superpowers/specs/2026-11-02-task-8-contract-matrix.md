# Task 8：播放器交互与视觉打磨 — 合同矩阵

> 状态：设计已由用户确认；本矩阵和实施计划已建立，产品代码尚未开始修改。
> 基线：feature/task-8-player-interaction-polish，从 main / 4d185f9a9928852f169d3e127e0e7cf25183c731 创建。
> 范围说明：本工作流是 Task 8 的一个明确授权 UI-polish 子范围，不替代 Task 7 Batch 4 的实机锁屏验收，也不提前关闭 Task 7 或 Task 8 总退出门。

## 1. 权威来源与冻结判断

- 设计规格：[Task 8 Player Interaction Polish Design](2026-11-02-task-8-player-interaction-polish-design.md)。
- Android 产品与工程总规格、Web 行为继承规格、客户端边界与架构规格仍是业务行为和单一状态来源的上位合同。
- Task 4 的 Player / Queue、Queue occurrence、媒体身份与 mini-player pull-up 既有合同继续生效。
- Task 5 的资源缓存、导航页面与列表行为继续生效。
- Task 7 的系统媒体会话能力继续生效；本次不修改其状态投影、通知、Service、命令入口。
- 已确认采用设计规格 §4 的方案 B：共享弹窗动效/几何能力，Queue 拖拽与 artwork shared-element 保持各自领域状态。
- 不需要新增产品选择。实现前仍须依据本矩阵和批次计划验证 Compose 指针生命周期、导航转场和 Dialog/Popup 生命周期。

## 2. 全局不变量

1. 不新增服务端 API、HTTP/WS owner、播放状态副本、Queue 业务模型、播放命令入口或数据库。
2. Queue 重排唯一身份继续使用 queue_item_id；必须保留 epoch、connection generation、queue revision、current occurrence、pending membership 等 baseline guard。
3. Drag preview 只改变 UI 表现；服务端确认/readback 前不得改写 canonical Queue 顺序。
4. Current 与 played history 不可作为拖动来源或插入目标。
5. 保留现有 mini-player pull-up 的 50% 窗口高度提交阈值、速度触发规则、250ms settle、直接点击进入、整页随手势移动、底部导航运动及全屏下滑退出。
6. Player/Queue 横向 Pager 不得受到普通路由水平转场或新手势识别器干扰。
7. Artwork 使用当前已验证的 PlayerArtworkCacheKey 与 PlayerArtworkMemoryCache；不得显示不匹配当前媒体身份的 Bitmap。
8. 弹窗关闭动画结束前保留其内容宿主；动画不得重复、延迟或吞掉业务回调。文本输入、IME、焦点、Back 和外部点击行为保持。
9. 本范围只处理应用自己拥有的 Popup、DropdownMenu 和 AlertDialog；系统权限弹窗与 Android 系统 UI 不在范围内。
10. 除非测试证明平台限制且单独记录，不新增运行时依赖。

## 3. 合同矩阵

| ID | 冻结需求与可观察结果 | 实现边界 | 自动化验证 |
|---|---|---|---|
| T8-01 | 实现只发生在指定 feature 分支；基于已核实的 main commit；不修改 main/其他分支。 | Git 基线、Task 文档与提交边界。 | git status、git branch --show-current、git merge-base；每批提交前检查 diff。 |
| T8-02 | 全屏 Queue 行菜单：白色 94% 不透明背景、18dp 圆角、7dp 阴影、0dp tonal elevation；操作文字全部左对齐，固定图标列与间距；宽度由最长操作标签决定，标题单行省略且不撑宽。 | QueueRowMenu.kt；图标形态参照项目既有 SongActionMenu glyph 风格。 | 新增 QueueRowMenuUiTest：背景/尺寸/图标/左边缘/Intrinsic 宽度/长标题省略/Back/外部点击。 |
| T8-03 | 在六点拖拽把手上按下并越过 touch slop 即开始拖动；无 long-press 延迟。静止点击仍不触发拖动，点击行本身仍保留原有播放行为。 | QueueOccurrenceRow.kt 的 handle pointer input。 | QueueReorderUiTest 覆盖 touch-slop 前后、静止按下、handle-only；不得通过整行 pointer detector 抢占点击。 |
| T8-04 | 被拖动条目显示为半透明、行尺寸一致的浮动代理，连续跟随手指；原始 canonical 行保持原位，拖动取消/提交后代理被清理。 | QueueScreen.kt、QueueOccurrenceRow.kt、QueueOccurrenceDragGhost。 | UI 测试验证代理随位移、大小与源行一致、透明遮罩可见、取消无 mutation、结束无残留。 |
| T8-05 | 对每个可见 pending target 计算 dragged overlay 与 target row 的真实交叠面积 / target row 全面积；比例必须严格大于 50% 才合格。等于 50% 不合格。 | 新增可纯测的 Queue drag target policy；只消费 measured Rect，不依赖中心点阈值。 | 单元测试覆盖 49.9%、50.0%、50.1%、完全不交叠、面积边界、多个候选与并列 tie-break。 |
| T8-06 | 合格 target 显示明确的插入提示；释放时将 dragged occurrence 插入到目标 occurrence 之前；无目标时保留队尾追加语义；没有有效变化时不发 mutation。 | QueueScreen.kt、QueueReorderDraft.kt 及 target policy。 | 单测验证 beforeQueueItemId、append-at-end、无变化取消；UI 验证 cue 与真实提交目标一致。 |
| T8-07 | 重复拖动、不同条目连续拖动、拖动后立即打开三点菜单、菜单关闭后拖动、取消后再次拖动、多轮循环中，handle 和菜单一直可用。 | 必须先通过 B0 重现并定位 pointer node 生命周期、gesture cancellation/consumption、reset、LazyColumn 复用或菜单组合中的首个故障点；只修证据指向的根因。 | B0 建立 lifecycle regression matrix；B2 将每条场景固化为 instrumentation regression，并多轮重复运行。不得先假定根因。 |
| T8-08 | 拖拽继续受现有 Queue baseline 与 action lock 约束；任何 snapshot、epoch、generation、revision、current 或 pending membership 不兼容都会取消，不得提交陈旧顺序。 | QueueReorderDraft.kt 与 QueueScreen.kt。 | 复用并扩展 QueueReorderDraftTest、Queue UI 测试；验证旧 baseline 不发 mutation、使用 occurrence ID、不做乐观 canonical 重排。 |
| T8-09 | 普通 NavHost 前进转场：新页从右进入、旧页向左退出；pop/back 方向相反；200ms，水平位移约视口宽度的 1/4，不使用页面 fade。 | NavHost 全局 transition；不接管根导航选择或 back-stack 语义。 | AppNavigationUiTest 验证 forward/pop 的初末边界与方向；NavigationMotionPolicyTest 验证 duration/offset/easing。 |
| T8-10 | 普通页面转场不应用于 mini-player 手势进入/退出、Player/Queue Pager 或全屏 dismiss；既有阈值、速度触发、手指跟随和 250ms settle 不变。 | AppNavHost.kt 的 NavHost 与独立全屏层边界。 | 现有 FullScreenPlayerNavigationUiTest + 新增 route exclusion assertions；真机验证 Player/Queue 横向手势仍独立。 |
| T8-11 | 定时歌词 active/inactive 字号分别从 34sp/28sp 减为 32sp/26sp；lineHeight 先保留 40sp/34sp。第二行固定单行、省略号显示，不自动 marquee/滚动。 | LyricsStage.kt。普通纯文本歌词滚动、点击时间轴跳转、自动跟随行为保持。 | LyricsStageUiTest 验证字号、长第二行省略静止、tap-to-seek、选择/跟随与普通歌词滚动未回归。 |
| T8-12 | Mini-player 封面从其实测矩形连续移动并放大到全屏 ArtworkStage 实测矩形；四条边随现有 entry progress 线性插值，不缩放整张 Player 页面。 | MiniPlayer.kt、ArtworkStage.kt、AppNavHost.kt；只新增临时 presentation overlay/geometry。 | ArtworkTransitionGeometryTest 覆盖矩形插值/尺寸边界；instrumentation 验证源/目标 bounds 与 progress 绑定。 |
| T8-13 | Artwork drag、阈值提交后的 settle、直接点击三条路径使用同一几何；mini-player 标题/控制快速淡出，移动封面不重影；完成后与全屏封面无跳帧；提前取消恢复 mini-player。 | 同 T8-12；沿用当前 cache identity，不新增加载器。 | MiniPlayerArtworkTransitionUiTest 覆盖 drag、tap、cancel、handoff；切歌/无封面/迟到缓存身份变化不得串图。 |
| T8-14 | 所有应用自有弹出层从触发控件/条目中心向最终边界快速展开并反向关闭：180ms、scale 0.82→1、alpha 0→1，modal scrim 同步淡入/淡出。 | 新增共享 Popup motion 几何/进度机制；不同 Popup/Dialog window 的 bounds 先统一为屏幕坐标，trigger 丢失时回退到最后测得的 anchor 或 owning container bounds，各 owner 继续管理内容、表单状态和业务 action。 | PopupRevealGeometryTest、共享 Compose motion test：origin、open、reverse close、close 后才 dispose。 |
| T8-15 | 弹出层完整覆盖 SongActionMenu、QueueRowMenu、Library sort、Queue clear/save、Output Details、Playlist create/rename/delete/add，以及实现审计发现的其他应用自有弹出层。 | SongActionMenu.kt、QueueRowMenu.kt、LibraryCategoryScreen.kt、PlaylistScreen.kt、FullScreenPlayerScreen.kt、共享 components。 | screen-level UI tests 检查每类 popup/dialog 都调用共享 motion；inventory 扫描确认无漏项。 |
| T8-16 | 动画不能改变 popup 位置策略、宽度、裁切、外部点击、Back、action 顺序、焦点、IME、无障碍和表单输入；触发项消失/页面切换时应安全关闭。 | Popup/Dialog host adapters 与各页面的 anchor measurement；anchor 与 surface 统一为屏幕坐标后计算 transform origin；trigger 丢失时用最后测得的 trigger 或 owning control/container bounds。 | UI 测试覆盖外部点击、Back、action、TextField 输入/键盘显示、焦点恢复、anchor 离开 composition。 |
| T8-17 | 完成机器回归、目标设备交互验收并报告证据；Task 7 Batch 4 和 Task 8 总退出门不得因本工作流被错误标为通过。 | 最终回归报告、README/计划状态与 Task 8 范围说明。 | clean offline unit/Lint/APK/Android-test build；connected instrumentation；ADB 真机视觉验收；签名/包名和 git 审核。 |

## 4. 批次与合同映射

| 批次 | 交付 | 合同 |
|---|---|---|
| B0 — Queue 重复交互根因诊断 | 复现矩阵、故障点证据、回归测试基线；不猜测、不先行修复。 | T8-01、T8-07、T8-08 |
| B1 — Queue 菜单视觉重做 | 固定图标列、左对齐、94% 白色、18dp/7dp、按最长 action label 自适应宽度。 | T8-02 |
| B2 — Queue 拖拽与重复交互修复 | touch-slop 开始、overlay、>50% 几何 target、清晰 cue、before-target 提交、已证实根因修复。 | T8-03 至 T8-08 |
| B3 — 普通路由转场与歌词 | forward/pop 水平切换；定时歌词字号与第二行省略。 | T8-09 至 T8-11 |
| B4 — Mini-player artwork shared-element | measured source/target rect、跟随现有 progress、标题控件淡出、tap/drag/cancel/handoff。 | T8-12、T8-13 |
| B5 — 全局 Popup/Dialog 动画 | 共享 origin/progress；迁移全部应用自有菜单/弹窗；反向关闭及焦点/IME 回归。 | T8-14 至 T8-16 |
| 最终集成门 | clean build、全量回归、真机验收、报告与 Task 7/8 门状态核对。 | T8-17 |

## 5. 每批统一交付规则

- 先写针对单一行为的 failing test，再运行确认按预期失败；随后写最小实现、运行 focused tests、再运行相关完整回归。
- B0 对于当前报告的故障，必须保留复现步骤、日志/Compose semantics/pointer lifecycle 证据、已排除项与根因判断依据。无法稳定重现时明确记为未定位，不得猜测后续修复。
- 每批结束执行 git diff --check、查看完整 diff、检查无无关文件改动；按计划给出一个本地提交建议。未获指示不 push、不 merge。
- 运行测试必须记录命令、exit code、实际通过/失败数。环境未覆盖不写成通过。
- 用户实机验收前先完成 APK 构建；不得卸载或清除数据。保持当前 applicationId、签名和偏好持久化行为。

## 6. 退出门与阻塞关系

本 UI-polish 子范围完成，要求 T8-02 至 T8-16 全部通过，T8-07 根因有证据与回归覆盖，最终 clean 构建和真机视觉/交互验证完成，并留下报告。

Task 7 Batch 4 的实机锁屏/整体验收仍是 Task 8 总退出门的上游依赖。完成本矩阵覆盖的 UI polish 不得自动勾选 Task 7 或主计划 Task 8 的总体完成项。任何既有测试失败、无法覆盖的 Android API/厂商行为、或未做的用户实机步骤均单独记录。
