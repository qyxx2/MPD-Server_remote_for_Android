# Task 9 — DAC 硬件音量控制合同矩阵

> **状态：** 规格已冻结；Batch 0–6 已完成；Batch 7 集成验收已由用户于 2026-11-05 确认通过。本次提交不补写未提供的现场验收数据。Batch 6 证据见 [验证报告](../reports/2026-11-05-task-9-batch6-verification.md)，Batch 5 证据见 [验证报告](../reports/2026-11-04-task-9-batch5-verification.md)，Batch 4 证据见 [验证报告](../reports/2026-11-04-task-9-batch4-verification.md)。
> **基线：** Android `main` commit `c6d9450534c179ce4cc80e6a15f1fd70fb5bb0ef`。
> **分支：** `feature/task-9-hardware-volume-control`。
> **主设计：** [Task 9 硬件音量控制规格](2026-11-04-task-9-hardware-volume-control-design.md)。
> **上游依据：** B0 已核对 MPD-Server `feature/hardware-volume-control` HEAD `b2f318bc69e828d4b17ecbfa1acffa3381cf9dae`，NAS 部署代码为其祖先 `7194191b27ad117b593d1cd302cf78bb11792457`；完整 DTO / error / realtime wire contract 已更新到 [Android 后端接口合同](2026-10-23-android-backend-api-contract.md)，证据见 [Task 9 Batch 0 报告](../reports/2026-11-04-task-9-batch0-verification.md)。

## 1. 上位合同与优先级

1. Android 产品与工程总规格、客户端边界与架构规格、后端 API 合同、Web 行为继承合同仍为上位合同。
2. MPD-Server 是唯一业务/硬件音量状态权威。Task 9 只增加 Android 的远端控制入口与状态投影。
3. AppRuntime 的 SnapshotStore、RealtimeController、StateReadCoordinator 和 MutationCoordinator 是唯一共享基础设施；不创建并行状态或网络 owner。
4. Task 8 已交付的 PopupMotionHost / PopupMotionAnchorState 是本次 DAC 面板的唯一弹窗动效基础。
5. Task 7 MediaSession/Service 当前行为不修改；仅保留以后可增加音量输入 adapter 的结构边界。
6. 本矩阵只冻结 Task 9 范围，不代表 Task 7 Batch 4、Task 8 全局验收或应用整体交付已完成。

## 2. 全局不变量

- `hardware_volume` 与 `hardware_volume_observation` 的服务端快照是唯一可展示的确认状态。
- WebSocket 的 `hardware_volume` domain 是 invalidate，不是音量 patch；必须重读完整 `/api/state`。
- 新写入使用 PUT 和 `Idempotency-Key`；unknown 只允许按原 intent/key/payload 显式恢复。
- `null` 不是 0；stale last-known 不是当前可写值；未知/过期/不可用时绝不开放写入。
- 音量键和 Slider 共用一条远端命令入口；禁止并行 PUT、旧值覆盖新值或跨 origin 自动补发。
- 硬件音量是 MPD 实例级，不是 output-ID 级；不触碰本机 AudioManager、不直接连接 MPD/ALSA/USB。
- 音量调整不得改变曲目、播放状态、Queue occurrence、Queue 顺序或历史。
- DAC 状态的 ACTIVE 仅表示服务端 output 状态，不证明实体 DAC 有声或 bit-perfect。

## 3. 合同矩阵

| ID | 冻结需求与可观察结果 | 实现边界 | 自动化 / 人工验收证据 |
|---|---|---|---|
| T9-01 | 只在新分支实现，基于 Android main 的冻结 commit；不修改 main、Task 8 分支或无关文件。 | Git 基线与本 Task 文件边界。 | `git merge-base`、分支名、工作区状态和每批 diff 检查。 |
| T9-02 | MPD-Server 是唯一音量状态来源；Android 不建本地 canonical volume state、数据库、持久缓存或第二个 SnapshotStore。 | AppRuntime SnapshotStore 是唯一状态投影；组件仅持有手势草稿/短暂未发送输入。 | 架构审查；搜索无 DataStore/Room/SQLite 音量持久化、无第二 WS/poller。 |
| T9-03 | Android 正确使用 `GET /api/state` 中的 `hardware_volume` 与 `hardware_volume_observation`；专用 GET 路径的 DTO 与错误按真实上游代码解析。 | StateSnapshot DTO/resource decoding 与薄 API 层。 | 上游 schema 对账；DTO fixture 覆盖 null、缺字段、合法范围、未知枚举。 |
| T9-04 | `AVAILABLE` + fresh + `stale=false` + 非 null 0–100 才可控制；UNSUPPORTED/UNAVAILABLE/stale/null/未知全部 fail-closed。 | 独立的硬件音量资格判断；不得复用依赖 current song 的 Player controls gate。 | 状态矩阵单测：各 status × freshness × null × connection generation。 |
| T9-05 | `domains` 含 `hardware_volume` 后触发完整 `GET /api/state`；绝不把 invalidate 帧作为值更新。 | 现有 RealtimeController / StateReadCoordinator / SnapshotStore。 | Realtime decoder 与状态读协调单测；确认仅一个 WebSocket owner。 |
| T9-06 | PUT body 使用服务端合同的 `volumePercent` 范围 0–100，携带 Idempotency-Key；服务端负责 MPD 写入后读回一致性确认。 | 现有 HttpTransport / MutationCoordinator；不自建 HTTP client。 | Request contract test 检查 method/path/body/header；NAS 实机 reversible write/readback。 |
| T9-07 | 新意图用新 key；同一 unknown intent 只允许复用原 key/payload/origin 显式重试；不根据 slider 显示值自动生成新 intent。 | MutationCoordinator 的原有 request identity / unknown 语义。 | 超时、5xx、解码失败、origin 改变、同 key retry 测试。 |
| T9-08 | Slider 与 Volume Up/Down 串行化；同一时间最多一个硬件音量写入；连续键步进按前一次权威读回值计算，不会因旧 snapshot 丢步或倒退。 | 瞬时输入仲裁 adapter；唯一 mutation owner 仍是 MutationCoordinator。 | 连续按键、反向按键、key repeat、slider/key 交错、失败/断线场景单测。 |
| T9-09 | 控制不依赖当前播放歌曲；无 current、paused、stopped 时，只要硬件音量能力 fresh AVAILABLE，仍可控制。 | 硬件音量资格规则与 PlayerPlaybackControlEligibility 分离。 | 无曲目、暂停、播放、空 Queue 状态下命令资格单测。 |
| T9-10 | 音量写入不改变当前曲目、播放/暂停、Queue occurrence/顺序和 History。 | 不调用任何 playback/queue mutation。 | Mock contract tests + NAS 实机前后快照字段对比。 |
| T9-11 | 全屏正在播放页面 DAC 图标打开唯一的紧凑面板；再次点击、外部点击、Back 均可关闭。 | 替换现有 Output Details 大对话框，不保留竞争入口。 | Compose UI tests：点击/重复点击/outside/Back/关闭单次回调。 |
| T9-12 | 面板只有两行：第一行 DAC 信息+简略输出状态；第二行音量图标+Slider+纯数字音量值（不显示 %）。两行均不换行，缺字段省略/省略号处理。 | FullScreenPlayerScreen 与 DAC 面板 presentation。 | 布局语义/尺寸/窄屏/字体缩放/缺字段 UI tests。 |
| T9-13 | 面板按信息量自然展开，min 200dp/max 92% 可用宽度或 520dp，水平安全边距至少 16dp；锚定 DAC 按钮上方并尽量对齐按钮左边缘，空间不足时才回退到下方。 | 复用 PopupMotionHost 与 screen-space trigger/host geometry；遵守安全 Insets，不写死坐标。 | 信息长短自适应宽度、屏幕坐标锚定、边距和字体缩放 UI tests；目标设备无越界/裁切。 |
| T9-14 | 使用现有 PopupMotionHost：180ms、scale 0.82→1、alpha 0→1，反向关闭、transform origin 来自实测 anchor；DAC 面板使用与 QueueRowMenu 三点菜单相同的 attached Popup 行为，不暗化背景、不创建全窗口透明拦截层。 | 复用 Task 8 共享 PopupMotionHost、AnchoredDropdownPositionProvider 与 PopupProperties，不新增 Dialog 或动画系统。 | Popup motion UI tests 验证开关曲线、锚点、外部点击/Back 关闭及关闭后 dispose。 |
| T9-15 | Slider 拖动只产生局部 preview；释放时提交一个绝对目标；pending/unknown/syncing 不能显示成服务端已确认值。 | 局部 gesture state + MutationCoordinator。 | Preview/commit/readback、失败恢复、重复拖动、外部 invalidate during drag tests。 |
| T9-16 | App RESUMED 时所有应用页面及应用自有 popup/dialog 的 Volume Up/Down 都路由到同一远端命令路径；DOWN/repeat 每次 ±1 个百分点，UP 不重复发送。 | Application/Activity 级输入 adapter；页面内不添加 listener。 | Player/Home/Library/Search/Settings/Queue/dialog 参数化 instrumentation。 |
| T9-17 | 前台 volume key 不触发本机 AudioManager；远端未知/不可用时不写入，只显示简短不可用反馈。后台、锁屏、其他 App 不拦截。 | 当前只接入前台输入 adapter；无 Accessibility/overlay/FGS。 | 远端 available/unavailable 按键测试；后台/切应用后系统默认行为测试；AudioManager 无写入审计。 |
| T9-18 | 输入 adapter 与 gateway 解耦，未来可增加经批准的系统媒体输入来源，但不在本任务启用后台按键捕获。 | 单一 HardwareVolumeCommandGateway 与多输入 adapter 的职责边界。 | 架构审查；无 Task 7 session 行为变更。 |
| T9-19 | 断网、重连、进程重建、server origin 变化均 fail-closed；等待中的旧意图不自动跨代执行。 | RealtimeController generation 与 MutationCoordinator origin 归属规则。 | reconnect/origin-switch/late-response instrumentation 与单测。 |
| T9-20 | 最终 clean build、单测、Lint、connected instrumentation 和 NAS/Android 实机证据齐全。Task 7/8 退出门仍按各自合同评定。 | 最终报告、README 与计划状态更新。 | 命令/exit code/通过失败数、真实安装签名、NAS 可恢复测试、设备截图/交互证据。 |
| T9-21 | App 前台收到 Volume Up/Down 时显示应用级顶部横条浮窗，数字随每次 DOWN/repeat 立即变化；最后一次按键后 2 秒隐藏。浮窗位于状态环实际外接边界下方 8dp，状态环视觉隐藏后仍沿用原几何位置；覆盖应用自有页面和 Popup/Dialog，不跨应用。数字只显示整数、不显示 %；DAC 面板音量数值同样不显示 %。 | 单一 AppRuntime/foreground overlay owner；以真实 StatusRingPlacement/WindowGeometry 和系统安全 Insets 定位；浮窗数字是 pending preview，不是第二份 canonical state。无 SYSTEM_ALERT_WINDOW。 | 参数化页面/弹窗 instrumentation、连续/反向按键测试、立即更新断言、1,999ms 仍可见/2,000ms 隐藏、重复按键重置计时、迟到网络响应不重新显示、z-order 与挖孔间距实机验证、纯数字语义断言。 |

## 4. Batch 与合同映射

| 批次 | 交付边界 | 合同 |
|---|---|---|
| B0 — 上游接口核对与集成预检 | 锁定 MPD-Server 实际 HEAD、DTO/错误/幂等/WS 语义；核实部署能力和客户端接缝；不改 Android 生产代码。 | T9-01、T9-03、T9-06 |
| B1 — Hardware Volume DTO 与共享快照 | 加入可兼容旧 server 的 typed DTO 与完整快照投影；建立状态/freshness 资格规则。 | T9-02 至 T9-04、T9-09 |
| B2 — WebSocket invalidate 与快照同步 | 接通 hardware_volume domain 的现有实时失效/完整重读；验证水位、重连、origin/generation 和迟到响应。 | T9-02、T9-05、T9-19 |
| B3 — Mutation 与命令串行化 | PUT/幂等/读回；Slider absolute intent 与 key delta 输入仲裁；unknown/failed/retry 语义。实现与自动化验证已完成，详见 [Batch 3 验证报告](../reports/2026-11-04-task-9-batch3-verification.md)。 | T9-06 至 T9-10、T9-19 |
| B4 — DAC 紧凑弹窗展示 | **已完成。** 替换大型详情对话框；完成两行信息布局、fresh 输出字段、服务端音量投影、几何锚定和现有弹窗动效。证据见 [Batch 4 验证报告](../reports/2026-11-04-task-9-batch4-verification.md)。 | T9-11 至 T9-14 |
| B5 — Slider 交互控制 | **已完成。** 手势本地 preview、释放单次绝对提交、pending/完整快照 readback、canonical tuple 失效、FAILED/UNKNOWN、无障碍 SetProgress 同路径；证据见 [Batch 5 验证报告](../reports/2026-11-04-task-9-batch5-verification.md)。 | T9-04、T9-07、T9-08、T9-15 |
| B6 — App 前台全局音量键与提示浮窗 | **已完成。** Activity 统一输入适配器、应用自有 Popup/Dialog 按键转发、同一 gateway FIFO 预览、状态环下方 8dp 纯数字提示、2 秒隐藏与 Dialog-local z-order；SM-G986N Android 13 instrumentation 5/5、545 项单测、Lint/Debug APK 通过。详见 [Batch 6 验证报告](../reports/2026-11-05-task-9-batch6-verification.md)。 | T9-08、T9-09、T9-16 至 T9-18、T9-21 |
| B7 — 集成回归与 NAS/Android 实机验收 | **验收已通过（用户于 2026-11-05 确认）。** 现场验收的详细命令、设备/服务端参数及音量恢复数值未随本次提交提供，本次不作独立复测声明。 | T9-01 至 T9-21 |

## 5. 每批统一交付规则

- 执行前先读本矩阵、Task 9 设计规格、Implementation Plan 与上位合同；只完成当前授权 Batch。
- 逻辑行为先加 failing unit/UI test 并验证 RED，再做最小实现，跑 focused tests 后执行相关回归。
- 每批检查 `git diff --check`、完整 diff、无关文件、状态权威边界、Idempotency-Key 和连接世代。
- 真实服务写入只在明确记录原始值、准备恢复值并可读回的情况下进行；测试后必须恢复原值，严禁改变输出路由或播放队列来制造测试条件。
- 每批记录命令、exit code、实际测试数量、设备/服务端环境和未覆盖项；没有实测的行为不得标为通过。
- 每批结束给出本地提交建议；没有单独授权不 push、不 merge。

## 6. Task 9 退出门

全部 T9-01 至 T9-21 必须有测试/审查证据。核心用户验收必须同时证明：
1. DAC 面板内容、位置和全局既有弹窗动效正确，音量数值只显示纯数字；
2. 音量键浮窗位置在状态环下方且保留安全间距，位于页面和应用自有弹窗之上，按键数字即时更新、2 秒无操作自动消失；
3. Slider 松手后只显示服务端读回确认的音量；
4. App 前台任意页面按音量键可调整远端值，且不改变手机本地音量；
5. stale/unknown/unavailable/unsupported/断线时不发送无效写入、不伪造数值；
6. 音量调整前后播放曲目、播放状态、Queue 与历史保持不变；
7. NAS 原始音量在测试后完全恢复。

Task 9 退出不代表 MPD-Server 已验证的外部 mixer 观察限制消失，也不关闭 Task 7 Batch 4 或 Task 8 全项目集成退出门。
