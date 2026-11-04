# Task 9 — MPD DAC 硬件音量控制 Implementation Plan

**日期：** 2026-11-04
**分支：** feature/task-9-hardware-volume-control
**Android 基线：** c6d9450534c179ce4cc80e6a15f1fd70fb5bb0ef
**合同：** [Task 9 合同矩阵](../specs/2026-11-04-task-9-contract-matrix.md)
**设计：** [Task 9 设计规格](../specs/2026-11-04-task-9-hardware-volume-control-design.md)
**状态：** Batch 0–3 已完成；Batch 4–7 尚未执行。B1 DTO/共享快照资格投影见 [Batch 1 验证报告](../reports/2026-11-04-task-9-batch1-verification.md)，B2 realtime invalidate/完整快照同步见 [Batch 2 验证报告](../reports/2026-11-04-task-9-batch2-verification.md)，B3 mutation/命令串行化见 [Batch 3 验证报告](../reports/2026-11-04-task-9-batch3-verification.md)；上游接口核对见 [Batch 0 验证报告](../reports/2026-11-04-task-9-batch0-verification.md)。

## 0. 执行前约束

- 每次只执行一个 Batch；不得跨批预先实现后续 UI 或输入源。
- B0 必须先锁定 MPD-Server 实际 HEAD、完整 JSON schema、错误语义与测试证据。若与 Task 9 规格不同，先修订 API contract / Task 9 合同并说明差异，再进入 B1。
- 唯一 canonical owner 是 AppRuntime.SnapshotStore；唯一 HTTP 写 owner 是 AppRuntime.MutationCoordinator；唯一实时 owner 是现有 RealtimeController。
- 允许新增薄 DTO/repository/command adapter/UI projection；不得新增状态 Store、独立网络 client、WebSocket、持久化 volume state 或后台服务。
- hardware_volume 资格规则必须独立于 Player 当前歌曲/播放能力；硬件音量可在没有 current song 时使用。
- 音量键与 Slider 命令不得并发发送；每次下一步必须基于上一请求后的服务端确认快照。
- 保留现有输出观察与音源格式展示边界：ACTIVE 不等于实体 DAC 出声，MPD instance volume 不是 per-output volume。
- 每批按 TDD 执行：单一行为 failing test → 验证 RED → 最小实现 → focused GREEN → 相关全量回归。
- 本计划只在指定 Android 工作区操作；每批执行 git diff --check、查看完整 diff，并记录实际命令、exit code、通过/失败数量。
- 除非用户另行授权，批次提交仅本地，不 push、不 merge；不得卸载 App 或清理用户数据来完成设备验收。

## Batch 0 — 上游接口核对与集成预检

**目标：** 在 Android 代码实现前，核实上游交接中的硬件音量接口与当前客户端基础设施，消除 wire-contract 猜测。

**只读审查范围：**
- MPD-Server 实际 branch/HEAD、部署版本和与 NAS 实例的对应关系；
- GET /api/state 的 hardware_volume、hardware_volume_observation JSON schema；
- GET /api/system/hardware-volume 完整响应、状态枚举、null/stale/fresh/error 字段；
- PUT /api/system/hardware-volume body、成功响应、读回不一致错误、幂等 key 行为；
- realtime invalidate 帧中 domains=["hardware_volume"] 的确切 frame 示例；
- 服务端 tests 对 AVAILABLE/UNSUPPORTED/UNAVAILABLE、stale、null、断线恢复和写入保护的覆盖；
- Android 当前 StateSnapshotDto、RealtimeFrameDecoder、StateReadCoordinator、MutationCoordinator、PopupMotionHost 和 MainActivity 接缝。

**步骤：**
1. 记录上游精确 commit、部署版本以及读取证据文件路径/测试名称。
2. 对照本仓库 docs/superpowers/specs/2026-10-23-android-backend-api-contract.md，列出新增字段/路由/广播域的确切 wire mapping。
3. 确认 hardware_volume invalidate 会落到现有完整快照读取，而不是只 invalidation 资源缓存。
4. 确认现有 MutationCoordinator 对 PUT 自动创建 Idempotency-Key、写入确认后执行完整状态同步，且 retryScope=null 可按原 intent/key 显式恢复。
5. 写明旧服务端缺少 hardware-volume 字段时的兼容降级，不可把缺字段当成 0 或显式 UNSUPPORTED。
6. 若现有 API contract 不准确，先更新该文档；为本批新增一份只读核对报告，记录 exact JSON examples 和未验证边界。

**预期交付：**
- 上游 commit / 部署版与契约证据记录；
- API contract 已补全确切字段映射；
- B1 DTO 可以按真实结构实施，无待猜字段。

**验证：** git 状态与文件清单审查；不写入 NAS、不修改 Android 生产代码。
**退出门：** 上游 endpoint/schema/WS/error/idempotency 与 Android 连接 seam 全部有可追溯证据；否则暂停 B1，先修订合同。

## Batch 1 — Hardware Volume DTO 与共享快照投影

**状态：已完成。** DTO、局部快照解码、strict numeric validation 与 capability policy 均通过测试；证据见 [Batch 1 验证报告](../reports/2026-11-04-task-9-batch1-verification.md)。

**目标：** 将服务端硬件音量纳入现有 canonical snapshot，建立可测试的状态/freshness 判定。

**预期代码范围：**
- protocol/dto/StateSnapshotDto.kt
- protocol/dto/StateSnapshotResources.kt
- 新的 hardware-volume typed DTO / decoder 文件（具体字段按 B0 锁定）
- runtime/FullStateSnapshot.kt 或相关状态投影
- 必要的 Player-level presentation mapper / tests；不得在 Player UI 内单独缓存 volume。

**步骤：**
1. 为完整快照中硬件音量域写 DTO/decoder tests：AVAILABLE、UNSUPPORTED、UNAVAILABLE、nullable value、stale/fresh、字段缺失、未知未来枚举、越界值和类型错误。
2. 先验证缺少新字段的旧服务端快照仍可读取其他播放/队列字段，硬件音量仅表现 UNKNOWN；不得让旧服务端导致整个 snapshot 解码失败。
3. 对 invalid hardware-volume 子树采用局部 UNKNOWN/diagnostic 降级，不能吞掉或伪造其他必需 snapshot 域。
4. 增加纯函数 capability policy：只有当前连接/代次匹配、snapshot fresh、status AVAILABLE、observation fresh、stale=false、volumePercent 非 null 且 0..100 才可写。
5. 明确区分服务端值、展示 last-known 值和 slider local draft。last-known 只能用作 stale display，不是控制基值。
6. 保证 capability policy 不依赖当前歌曲、Queue 是否为空或 PLAYING/PAUSED。

**测试重点：**
- status × freshness × stale × null × snapshot connection generation 的完整矩阵；
- null 不会转换成 0；
- unknown 状态/不合法数值不会崩溃整个 app；
- 无 current song 时 AVAILABLE 音量仍 eligible；
- old snapshot/old generation 不可控制。

**退出门：** 所有 DTO/policy tests 通过；SnapshotStore 仍是唯一 volume canonical owner；无 DataStore/Room/SQLite 音量字段。

## Batch 2 — WebSocket hardware-volume invalidate 同步

**状态：已完成。** 现有 protocol-v1 decoder 已允许任意非空 domain，RealtimeController 已将所有 invalidate 统一交给 StateReadCoordinator；无需增加领域白名单或生产代码分支。新增 decoder、Runtime 集成与 coordinator 水位/世代回归测试，证明 hardware_volume 通知只触发合并后的完整状态读取，并通过 SnapshotStore 更新。证据见 [Batch 2 验证报告](../reports/2026-11-04-task-9-batch2-verification.md)。

**目标：** 将硬件音量变更接入唯一实时链路，确保任何值变化都由完整快照确认。

**预期代码范围：**
- protocol/dto/RealtimeFrameDecoder.kt（仅当实际 domain 验证需要）
- runtime/RealtimeController.kt
- runtime/RealtimeConnection.kt
- runtime/StateReadCoordinator.kt
- 对应 realtime/snapshot tests

**步骤：**
1. 新增 frame fixture：domains 包含 hardware_volume；frame 本身不含音量数值。
2. 验证现有 decoder 不会拒绝新增 domain；如有 domain 白名单，仅将该 domain 加入，不做业务解析。
3. 确认收到 invalidate 后通过现有 StateReadCoordinator 合并 GET /api/state，不得调用专用 GET 建第二条状态更新路径。
4. 验证相同通知重复/乱序时不重复回写；较高 sequence 触发的读请求进行水位合并。
5. 验证旧 epoch、旧 connection generation、server origin 切换期间的迟到 GET 不得覆盖当前状态。
6. 读请求失败时将共享快照 freshness 标 stale/unknown，音量写入资格关闭；成功重连后依赖新 snapshot 恢复。

**测试重点：** invalidate 不直接 patch、GET 单飞/合并、重复通知、迟到快照、断线/重连、origin 改变、读失败与恢复。
**退出门：** 仅一个 RealtimeController/WS owner；hardware_volume 更新完全通过 SnapshotStore 接受的完整状态呈现。

## Batch 3 — PUT mutation 与命令串行化

**状态：已完成。** 新增 AppRuntime 单例 `HardwareVolumeCommandGateway`，Slider absolute intent 与 Volume key delta 经同一个 FIFO 串行进入现有 `MutationCoordinator`；每步均等待服务端 post-write 完整快照 readback 后再从 SnapshotStore 最新 fresh 值计算下一目标。UNKNOWN 显式重试保留原 intent/key/payload，失败、origin/generation/freshness 失效会丢弃未发送输入。没有新增 HTTP client、状态 Store、持久化或本地音量写入。验证见 [Batch 3 报告](../reports/2026-11-04-task-9-batch3-verification.md)。

**目标：** 为两种输入建立同一个远端命令路径，复用现有幂等与完整快照 readback。

**预期代码范围：**
- runtime/AppRuntime.kt
- runtime/MutationCoordinator.kt（只有证据证明需要共性调整时才改）
- 新薄 HardwareVolumeCommandGateway / request factory
- 相关单测
- 如需要，薄 data/SystemRepository.kt 扩展；不得另建 HTTP client。

**步骤：**
1. 测试 PUT method/path/payload；按 B0 的真实 JSON 字段序列化 volumePercent。
2. 新 intent 交给现有 MutationCoordinator；确认自动携带 Idempotency-Key。
3. 成功后复用 MutationCoordinator 当前 GET /api/state post-write synchronize；不从 response body 或 payload 直接 patch SnapshotStore。
4. UNKNOWN/transport error/5xx/decode failure 保留 immutable intent/key/payload；不自动创建新 key。retry 必须显式调用同一 intent。
5. 设计单飞 command arbitration：同一时刻最多一个 hardware-volume mutation pending/syncing。
6. 将 Slider absolute target 和按键 delta 表示为短暂输入意图 FIFO；相邻 key delta 可合并，不跨越绝对 slider intent 合并。
7. 只有前一个写入 SUCCESS 且 canonical readback 已进入 SnapshotStore 后，才从最新 fresh 服务端值计算并发送下一条意图。
8. 若写入 FAILED/UNKNOWN、断线、origin/generation 变化或 freshness 失效，清空后续尚未发送输入；禁止重连后延迟执行。
9. 键步进为每个有效 DOWN/repeat ±1 个百分点，钳制 0..100；执行时使用最新 canonical 值。

**测试重点：**
- 每个新意图新 key；unknown retry 复用原 key 与 payload；
- 两种输入连续/交错时顺序正确，无并发覆盖；
- 已确认读回与目标不一致时不得显示目标已确认；
- 连接变化丢弃待发送意图；
- 音量写入与 playback/queue state 完全隔离。

**退出门：** 命令路径只有一个 MutationCoordinator；gateway 不持有 canonical volume state、不持久化、不建立 HTTP owner。

## Batch 4 — DAC 紧凑弹窗与信息展示

**目标：** 用扁宽两行弹窗替换当前大型 Output Details dialog。

**预期代码范围：**
- ui/player/FullScreenPlayerScreen.kt
- ui/player/FullScreenBottomNavigation.kt
- 新的紧凑 DAC panel composable（如有必要）
- ui/components/MotionAlertDialog.kt（仅当需要增加不带 title/footer padding 的 compact content slot）
- ui/components/PopupMotion.kt 仅当现有 anchor transform 支持被证明不满足时才扩展；优先复用现有能力
- 对应 player/popup UI tests

**步骤：**
1. 复用现有 DAC 图标及 screen-space anchor bounds；点击打开/再次点击关闭，锚点只用于 transform origin。
2. 替换而不是并列新增 OutputDetailsDialog；面板只保留两行，不显示传统 title/footer。
3. 第一行展示服务端 fresh 的 DAC 输出格式字段和简短状态；缺失字段省略，单行 ellipsis。
4. 第二行先显示音量图标、确认纯数字/未知占位及 Slider；此批 Slider 可以保持禁用占位，交给 B5 接入控制。
5. 设置 92% 宽度、296dp min、520dp max、16dp safe margins；沿用现有 MotionAlertDialog 全窗口居中 surface 布局，DAC icon 实测 bounds 仅用于 transform origin。
6. 复用现有 Dialog window host、PopupMotionHost / PopupMotionAnchorState 的 180ms、0.82→1、alpha 与 transform-origin；保留现有 0.32 scrim 和 surface 样式。若 MotionAlertDialog 当前 title/footer slot 不能表达紧凑两行内容，只为它增加 compact content slot，不复制第二套 dialog host。
7. 验证深浅主题、ACTIVE/INACTIVE/STALE/UNKNOWN/ERROR 文本、信息缺失、窄宽度和系统字体缩放。
8. 验证 Back/外部点击/入口再次点击只执行一次 dismiss，反向动画完成前不卸载内容。

**退出门：** 面板位置/动效与 Task 8 全局 popup 一致，信息精简两行且无裁切；旧大详情 dialog 已被替换。

## Batch 5 — Slider 交互与读回确认

**目标：** 让 DAC 面板 Slider 能安全修改远端 MPD 硬件音量。

**预期代码范围：**
- DAC panel composable / player presentation
- HardwareVolumeCommandGateway
- focused UI/unit tests

**步骤：**
1. AVAILABLE fresh 且值有效时 Slider 启用；其余状态禁用。
2. 拖动期间只显示 local preview，不改变 SnapshotStore、不写入网络。
3. 手指释放后将一个整数 0..100 绝对目标交给共用 command gateway；不得持续每帧 PUT。
4. pending/syncing 时展示进行中状态，不让预览值冒充 confirmed；其他按键/滑块请求按 gateway 顺序排队。
5. 成功后必须等待完整状态同步完成，再用服务端 readback 更新 Slider/数值。
6. 若拖动期间 volume-specific canonical tuple（epoch、connection generation、status、value、freshness）发生变化，取消当前 draft，以最新 server value 为准。
7. FAILED 恢复到 canonical 值并展示错误；UNKNOWN 保留 unknown activity 并要求原 intent/key 的显式恢复，不生成新目标自动重发。
8. Slider 辅助功能增减也必须通过同一 gateway 和状态门槛。

**退出门：** Slider 所有可见确认值均来自服务端快照；一轮拖动仅一个新 intent；状态失效时不会提交旧草稿。

## Batch 6 — App 前台任意页面的音量键与全局提示浮窗

**目标：** App 在前台任意位置时，Volume Up/Down 都控制远端硬件音量；同时显示跟随按键变化的顶部横条纯数字浮窗。不在各屏幕散布事件处理。

**预期代码范围：**
- MainActivity.kt / application-scoped input adapter
- AppRuntime.kt / HardwareVolumeCommandGateway
- 如 Dialog window 的 key dispatch 确有缺口，只在应用自有窗口范围提供统一适配，不在各屏幕重复注册 listener
- application foreground overlay owner / window-level placement adapter
- activity/key handling 与 overlay UI/device instrumentation tests

**步骤：**
1. 先用 instrumentation 在普通 Activity、Compose Dialog/Popup、文本输入焦点和各导航页验证 Volume key 事件实际分发位置。
2. 在应用级 foreground boundary 建立一个 adapter，接收 Volume Up/Down 并调用同一 gateway；不在 Player/Library/Settings/Queue composable 各加处理。
3. 仅在 App RESUMED/应用自有窗口前台时接管；覆盖当前页面及应用自有弹窗，但不拦截系统权限 UI、锁屏或其他 App。
4. DOWN 和系统 repeat 事件各产生一个 ±1 步进；UP 不额外提交；避免 Activity 与 Compose 重复响应同一事件。
5. 远端状态不 fresh/不可用/未知时不发送 mutation，显示简短不可用反馈；前台事件不调用系统 AudioManager 改本机媒体流。
6. 输入路由与 command gateway 分离；当前只实现 foreground adapter，为未来单独批准的 media-session input source 留出调用入口。
7. 不修改 Task 7 MediaSession/Service 生命周期，不增加后台服务、FGS、跨应用 overlay 权限或 Accessibility Service。
8. 每次 Volume key DOWN/repeat 立即使全局浮窗可见并更新纯数字预览；最后一次按键后 2 秒无新按键事件自动隐藏。网络响应不得重置/延长计时或在隐藏后重新弹出。
9. 浮窗采用顶部横条，在状态环实际边界下方保留 8dp 间距；优先复用 StatusRingPlacement / StatusRingWindowGeometry 与安全 Insets，禁止硬编码坐标。
10. 浮窗必须覆盖所有 App route，并通过真实 z-order 测试证明在应用自有 MotionAlertDialog/Popup 上方；如果现有 Activity decor overlay 被 Dialog window 遮挡，应选择仍属于应用窗口的顶层承载方式，不申请 SYSTEM_ALERT_WINDOW。
11. 该浮窗数字来自服务端确认值加未确认的输入意图预览；服务端 readback 后转为 canonical 数值。UNKNOWN/失败/状态 stale 时回退并标明状态，不能把 pending target 冒充成功。
12. DAC 弹窗与全局浮窗中的音量数值统一只显示整数数字（如 73），不显示 %、单位或“百分比”。

**测试重点：** 连续上/下键、方向反转、DOWN/repeat/UP；任意页面与 Dialog window 覆盖层级；最后一次按键后 2 秒自动隐藏、重复按键重置计时；浮窗即时 pending preview 与 server readback 对账；stale/unknown/unavailable 时不伪造数值；状态环/挖孔下方间距与窗口安全区；本机 AudioManager 无写入副作用。

**退出门：** 前台所有路由只有一个 volume key owner；提示浮窗在页面和应用自有 popup 之上，按键实时更新纯数字、2 秒后隐藏；DAC 面板同样不显示百分号；后台/其他 App 不被本任务捕获；两种输入源使用同一 gateway。

## Batch 7 — 集成回归、NAS 与 Android 实机验收

**目标：** 证明状态权威、UI/输入、服务端读回和播放保护在真实部署组合中成立。

**步骤：**
1. clean offline build、unit tests、Lint、APK build/signature/package check。
2. 运行全部 realtime/snapshot/mutation/volume policy/UI tests 与 Task 4/5/7/8 相关回归。
3. 使用真实 Android 设备连接已部署 MPD-Server；初始读取音量并记录 MPD/输出状态、当前歌曲、播放状态、Queue occurrence/order。
4. 通过面板 Slider 将值改变一个小幅度，检查 PUT/Idempotency-Key、server-side MPD readback 和 Android 完整 snapshot 更新；恢复原值。
5. 在 Player、Queue、Home、Search、Library、Settings 和弹窗内按 Volume Up/Down，检查服务端值逐步变化、本机媒体音量未被调用、页面无关性成立。
6. 测试 UNSUPPORTED/UNAVAILABLE/stale/null/断线/重连/origin change；不可控时不写入，重连后只接受新完整 snapshot。
7. 每轮写入完成后恢复 NAS 原始值并再次 GET 确认；若恢复失败，立即停止其他写测试并报告。
8. 对比前后歌曲、播放状态、Queue occurrence、顺序和历史，证明无播放副作用。
9. 记录设备/API 版本、App build/signature、server commit/deployment、音量原值/测试值/恢复值、命令 exit code、测试数量、截图和遗留项。
10. 更新 README、Task 9 plan checkboxes 和最终验证报告；不要替 Task 7/8 的独立退出门打勾。

**退出门：** T9-01 至 T9-21 全部有证据；NAS 原值已恢复；所有残余限制明确记录。

## 总退出条件

- Task 9 合同矩阵全部覆盖，无 Critical/Important 边界问题。
- 音量状态只来自服务端完整快照；WS invalidate 触发完整 read；不使用 Android 本机音量作为远端替代值。
- Slider 和全局音量键串行进入唯一 mutation owner，并正确处理幂等、unknown 和 origin 变化。
- UI 与现有 PopupMotion 完全一致，实机可用且状态无误导。
- 真实 NAS 和真实 Android 设备验证通过，且音量写入没有播放/队列副作用。
- 代码、测试、Lint、构建、文档和 Git diff 证据闭环。
