# Task 9 — MPD DAC 硬件音量控制与状态观察规格

**日期：** 2026-11-04
**Android 基线：** `main` commit `c6d9450534c179ce4cc80e6a15f1fd70fb5bb0ef`
**工作分支：** `feature/task-9-hardware-volume-control`
**状态：** 规格、边界、合同矩阵与分批计划已冻结；尚未开始 Android 代码实现。
**上游能力依据：** B0 已核对 MPD-Server `feature/hardware-volume-control` HEAD `b2f318bc69e828d4b17ecbfa1acffa3381cf9dae`，NAS 部署代码为其祖先 `7194191b27ad117b593d1cd302cf78bb11792457`。wire DTO、错误语义和 realtime invalidate 已锁定，详见 [Task 9 Batch 0 核对报告](../reports/2026-11-04-task-9-batch0-verification.md) 与 Android 后端接口合同。

## 1. 目标与范围

为 Android 遥控端接入 MPD-Server 已提供的硬件音量能力。Android 提供两种输入方式：

1. 在全屏正在播放页面点击 DAC 图标，打开紧凑的 DAC/硬件音量弹窗，并直接拖动音量条。
2. App 处于前台时，在任意 App 页面或应用自有弹窗内按手机音量加/减键，调整远端 MPD 硬件音量。

两种输入必须汇入同一条远端音量命令路径。音量状态始终从 MPD-Server 的完整权威快照投影，界面和命令输入不得创建第二套音量状态。

本任务只涉及 MPD 实例级硬件音量控制及现有 DAC 输出信息的展示整合，不扩展输出切换、独立输出设备音量或本机音频播放。

## 2. 权威边界与禁止事项

### 2.1 MPD-Server 唯一权威

MPD-Server 是硬件音量的唯一权威来源，负责：
- MPD 实例级音量的读取与写入；
- 对 MPD 写入结果进行再次读取确认，只有读回值与目标值一致才算写入确认；
- `AVAILABLE`、`UNSUPPORTED`、`UNAVAILABLE` 等能力/状态判断，以及 stale/fresh 观察信息；
- 将音量变化作为 WebSocket invalidate 广播；
- 统一的错误、幂等和最终结果语义。

Android 只负责：
- 从唯一共享快照读取并展示服务端确认的音量与 DAC 输出信息；
- 将用户显式的绝对音量目标或音量键步进作为 HTTP mutation 发送到服务端；
- 接收现有 WebSocket invalidate，并要求现有共享状态协调器重读完整快照；
- 展示 pending、unknown、failed、stale 和 unavailable 等状态。

### 2.2 明确禁止

- 不直接连接 MPD TCP 端口，不使用 MPD 协议或 Android `AudioManager` 直接控制音量。
- 不调用 `setStreamVolume`、不改变手机本地媒体音量来模拟远端硬件音量。
- 不在 Android 保存可编辑或可恢复的 hardware-volume canonical state；不使用 DataStore、Room、SQLite 或文件持久化音量值。
- 不从滑块草稿、音量键步进、PUT payload 或写入动画推断写入成功。
- 不建立第二个 WebSocket、独立轮询器、第二个 SnapshotStore 或与 AppRuntime 并行的音量仓库状态。
- 不在 Android 推断/读取 ALSA mixer、USB DAC 实体旋钮或物理增益；不把 MPD 输出 ACTIVE 等同于实体 DAC 正在发声或 bit-perfect。
- 不把 MPD 实例级音量描述为针对某一个 output ID 的独立音量。

## 3. 服务端接口与状态语义

### 3.1 已知接口

| 能力 | 服务端接口 | Android 语义 |
|---|---|---|
| 完整状态 | `GET /api/state` | 唯一正常状态读取入口；快照含 `hardware_volume` 与 `hardware_volume_observation` |
| 专用读取 | `GET /api/system/hardware-volume` | 服务端诊断/专项读取接口；常规 UI 不另建独立状态流 |
| 设置音量 | `PUT /api/system/hardware-volume` | body 含 `volumePercent`，范围 0–100；必须携带 `Idempotency-Key` |
| 状态变更通知 | `WS /api/realtime` | `domains` 包含 `hardware_volume` 时只表示失效，必须重读完整状态 |

服务端写入路径已验证：MPD 写入后重新读取，必须与目标值完全一致。NAS 实机已完成从 80 到 77 再恢复到 80 的可恢复测试，并验证稳定时不会重复广播。Android 不重复实现服务端校验。

**Wire 基线约束：** 上述字段名、路径、状态枚举和语义来自 2026-11-04 上游交接。响应对象的完整嵌套层级、时间戳/错误字段、HTTP 错误码和确切 DTO 仍须在 B0 根据上游实际代码及测试记录；未核实前不得臆造响应 JSON 或固定未提供的枚举值。

### 3.2 状态解释

| 服务端状态/字段 | 展示及控制规则 |
|---|---|
| `AVAILABLE` 且观察 fresh、`stale=false`、`volumePercent` 在 0–100 | 显示当前整数音量值，允许滑块和前台音量键控制 |
| `UNSUPPORTED` | 显示服务端不支持；音量值未知时显示 “—”，禁用滑块及远端写入 |
| `UNAVAILABLE` | 显示当前不可用；若服务端提供 last-known 值可展示并标出不可用/过期，不能开放写入 |
| `stale=true` 或 observation freshness 非 fresh | 可展示服务端最后确认值并明确标记过期；禁用所有音量写入 |
| `volumePercent=null`、字段缺失或 DTO 无法解析 | 表示未知，不得替换成 0；禁用控制并报告未知/协议错误 |
| 未知的未来状态枚举 | 安全降级为 UNKNOWN，不能按 AVAILABLE 处理，也不能使整个播放快照崩溃 |

控制资格必须同时满足：当前连接已连接、快照属于当前 connection generation、共享快照 fresh、音量能力明确为 AVAILABLE、观察 fresh、`stale=false` 且值非 null 且位于 0–100。不能使用 Player playback controls 的当前歌曲/播放状态作为硬件音量资格门槛；没有正在播放的歌曲也可以控制服务端音量。

### 3.3 WebSocket 失效通知

收到 `domains=["hardware_volume"]` 时：
- 不从 WebSocket 帧提取音量值，也不执行本地加减；
- 通过现有 RealtimeController / StateReadCoordinator 触发并合并 `GET /api/state`；
- 仍按 epoch、sequence、connection generation 过滤旧帧、迟到响应和旧端点结果；
- 只有完整快照被 SnapshotStore 接受后才更新 UI；
- 快照读取失败时保留 last-known 展示但标 stale/unknown，并禁用写入。

不得因 hardware-volume 新增第二个 WebSocket 或专属 polling loop。

## 4. 写入、幂等与并发

### 4.1 统一 mutation 路径

所有设置请求使用 `PUT /api/system/hardware-volume`，payload 的目标字段为 `volumePercent`，并由现有 `MutationCoordinator` 生成并持有 `Idempotency-Key`。正常成功后复用现有 post-write 完整状态同步；如实时 invalidate 与 mutation readback 并发，继续使用既有水位合并规则。

- 每次新用户绝对目标产生一个新 intent/key。
- 同一 intent 的重试必须复用原 method、path、payload、origin 和 key。
- Transport timeout、5xx、响应解码错误等可能意味着服务端已执行，进入 UNKNOWN；不得用新 key 自动重发，也不得用滑块草稿恢复 payload。
- 已确认 HTTP 失败保持失败，不覆盖服务端 last-known canonical state。
- 写入确认依赖服务端成功响应及随后的完整权威快照同步；Android 不以 PUT payload 乐观改写 SnapshotStore。

### 4.2 多输入源仲裁

滑块和音量键共用同一个硬件音量命令入口，禁止并行写入导致旧值覆盖新值。

- 滑块拖动只保留当前手势的临时展示草稿；手指释放时形成一个绝对目标 intent，不在每个像素位移上持续发 PUT。
- 若拖动期间服务端确认值、能力状态、freshness、epoch 或 connection generation 发生变化，立即取消本地草稿并以最新权威快照为准，不得用旧拖动目标覆盖外部音量变化。
- 音量键每个有效 DOWN/系统 repeat 代表一个 1 个百分点的相对步进：Volume Up 为 +1，Volume Down 为 -1，范围钳制在 0–100；UP 事件不再重复发送。
- 同一时间最多有一个硬件音量写入处于 PENDING/SYNCING。等待期间的连续按键步进可合并为有符号临时 delta，在前一个 intent 完成权威 readback 后，基于当时最新的 server-confirmed 值计算下一目标。
- 如果绝对滑块目标与等待中的按键步进交错，按用户输入顺序串行执行；相邻按键步进可合并，但不得跨越绝对目标合并。
- 上述 delta/队列仅是未完成的瞬时用户意图，不是音量状态，不持久化、不对 UI 宣称已确认。连接断开、origin/generation 改变或 fresh 条件失效时，丢弃尚未发送的排队意图；禁止在重连后自动补发。
- 任何时候都不能从陈旧快照计算目标；没有 fresh canonical value 时停止远端调整。

### 4.3 播放保护

音量 mutation 不应改变 current song、播放/暂停状态、Queue occurrence、Queue 顺序或播放历史。Android 侧不发送任何播放/队列命令。实机验收需验证这些字段保持不变。

## 5. DAC 弹窗交互与视觉

### 5.1 入口

- 入口为全屏正在播放页面底部导航中的 DAC/输出图标（现有 `player-bottom-output`）。
- 点击图标打开弹窗；再次点击、点击弹窗外部或系统 Back 关闭。
- 不把音量滑块放到 Home、Library、Search、Settings、mini-player 或 Queue 列表行；音量键远端输入不受当前页面限制。
- 移除当前仅展示详情的独立大尺寸 Output Details 对话框，改为一个同时展示 DAC 信息和硬件音量的紧凑面板；不得并排保留两个互相竞争的 DAC 弹窗。

### 5.2 面板结构

设计为扁宽、低高度、横向展开的两行面板，无独立标题区和底部确认按钮：

**第一行 — DAC 信息与简略输出状态**
- 左侧 DAC/输出图标；
- 中间显示服务端当前可用的 DAC 输出信息，例如 “DAC · 44.1 kHz · 16 bit · 2 ch”。只显示快照实际提供且 fresh 的字段，缺字段省略，长文本单行省略；
- 右侧用短标签/状态点表示 “已启用”“未启用”“不可用”“已过期”或“未知”。
- “已启用”只代表服务端输出状态为 ACTIVE，不得文案暗示已确认实体 DAC 正在出声。

**第二行 — MPD 硬件音量**
- 左侧音量图标；
- 中间为可拖动的单条音量 Slider；
- 右侧只显示整数数值，例如 `72`；不显示百分号或“百分比”后缀。未知值展示 “—”，不展示 `0`。
- Slider 反映服务端确认值；拖动期间允许用临时 thumb/value 预览，必须以 pending 样式与确认值区分。释放后提交一个绝对值 mutation，直到读回确认前不能将预览值显示为已确认。
- 非 AVAILABLE、stale、unknown、null 或连接失效时保留该行但禁用 Slider，按实际状态呈现数值/占位与状态说明。

### 5.3 尺寸、摆放与动效

- 面板宽度按内容自然展开：最小 200dp，最大不超过可用窗口的 92% / 520dp，并保留至少 16dp 水平安全边距；只含 DAC 名称/状态时应明显短于带完整采样格式信息的面板，长文本在最大宽度内单行省略。
- 内容仅两行，每行保持单行布局；面板目标总高度约 76–90dp，按字体缩放和无障碍需求允许自然增加，不允许信息被裁切。
- 音量条采用精简进度条外观，约 4dp 轨道、圆角填充，不使用传统大圆形 thumb；B4 只读/禁用，后续启用拖动时继续沿用精简轨道视觉。
- 表面采用现有主题语义色、弹窗 surface/elevation 体系和圆角语言；避免厚重卡片边框。浅色/深色主题都要保持文字、状态点、图标和音量轨道可读。
- 面板锚定 DAC 按钮上方，优先对齐按钮起始边缘；空间不足时按共享 AnchoredDropdownPositionProvider 回退为末端对齐；依据按钮与弹窗宿主的实测屏幕坐标计算位置，遵守安全 Insets 和 16dp 边距。上方空间确实不足时才回退到按钮下方，不得放回屏幕中央。
- 弹窗宿主与队列条目三点菜单保持一致：使用 attached `Popup`、共享 `AnchoredDropdownPositionProvider`、`PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)`；不得采用全窗口 Dialog，也不以清空待播确认弹窗为参考。使用 Task 8 已交付的共享 `PopupMotionHost` / `PopupMotionAnchorState`：180ms、scale 0.82→1、alpha 0→1，关闭时反向播放，origin 由实测触发控件/面板 bounds 计算。Popup 不暗化背景，不添加全窗口透明拦截层；其他 MotionAlertDialog 调用保持原状。
- 动画结束前保留内容宿主；Back、外部点击、再次点击入口和无障碍关闭动作都只触发一次 dismiss。

### 5.4 可访问性

- DAC 入口、音量图标、Slider、纯数字音量值和状态说明均有语义描述；Slider 可通过无障碍增减，并遵守相同 mutation/freshness 规则。无障碍数值同样只读数字，不追加百分号。
- 交互触控目标基线至少 48dp；系统字体放大时面板可增高但两行语义不变。
- 不能仅用颜色区分 ACTIVE、STALE、UNKNOWN、ERROR；必须有可读状态文本。

## 6. App 前台音量键

- 在应用级输入边界处理 Volume Up / Volume Down，不在 Home/Library/Player/Queue/Settings 各页面重复添加 key listener。
- App 处于 RESUMED/前台时，任何应用内路由和应用自有 popup/dialog 下均使用同一 HardwareVolumeCommandGateway；控制不依赖弹窗是否打开，也不要求当前有歌曲正在播放。
- 每个有效按键 DOWN/repeat 产生 1 个百分点步进；ACTION_UP 只结束按键，不额外写入。硬件按键长按必须按系统 repeat 语义稳定处理，不能一次 DOWN 同时被 Activity 与 Compose 重复消费。
- 前台按键事件由远端控制路径统一消费；不调用 Android 本机媒体流音量 API。若远端音量当前不可用/过期/未知，只显示简短不可用反馈，不提交写入，也不伪造音量变化。
- Activity 进入 STOPPED/后台、锁屏、切换到其他应用后，本任务不再拦截系统音量键；不做跨应用全局捕获、跨应用悬浮窗、Accessibility Service 或额外常驻服务。
- 输入源和命令 gateway 必须解耦：当前只接入前台 Activity/应用窗口按键适配器。架构应保留统一的 HardwareVolumeInputAdapter 接缝，未来经单独批准并完成 Android 平台可行性验证后，可增加播放期间跨页面/跨应用的系统媒体会话或其他合法输入适配器，继续复用同一命令入口、freshness 门槛与 SnapshotStore，而非另建音量状态。该未来扩展不属于本任务，也不代表当前已经能够在后台捕获音量键。

### 6.1 音量键全局浮窗

- 每次 App 前台收到有效 Volume Up / Volume Down 的 DOWN 或系统 repeat 事件时，立即显示同一个应用级音量浮窗；它不依赖 DAC 弹窗是否打开，也不因当前所在页面而改变。
- 浮窗显示当前有效音量数值并跟随每次按键实时变化：按上键递增、按下键递减；显示纯整数（例如 `73`），不显示百分号、单位或“百分比”文字。左侧配一个简洁音量图标，数字作为主要信息。
- 当远端状态满足 AVAILABLE/fresh 条件时，浮窗的即时数字可依据最新服务端确认值叠加当前串行命令队列中尚未确认的按键 delta 计算，以便跟随快速连按立即变化；该数字属于短暂的 pending preview，不得写入 SnapshotStore，也不得向其他页面伪装成服务端已确认值。可用轻量 pending 标记区分预览与确认状态。
- 每次新按键事件都立即刷新数字并重置隐藏计时；最后一次有效按键事件之后连续 2 秒没有新按键事件，浮窗自动消失。计时从按键事件计算，不从 HTTP 响应计算；网络响应迟到不得重新显示浮窗或延长显示时间。
- 当服务端读回与预览一致时，浮窗数字转为快照确认值并清除 pending 标记；明确失败时回退到最新服务端确认值。若写入结果 UNKNOWN、快照 stale 或连接失效，不得继续显示看似已确认的目标值；按实际可用性回退到最后确认数字并标示过期/未知，数值未知时显示 “—”。
- 浮窗是 App 前台窗口范围内的全局短暂提示层，必须覆盖所有应用页面，并在应用自有 Popup/Dialog 打开时仍显示在最上层；不跨越到其他 App，不申请 SYSTEM_ALERT_WINDOW，不实现跨应用悬浮窗、Accessibility Service 或后台常驻窗口。
- 位置位于顶部状态环的下方横条区域：以现有 StatusRingPlacement/StatusRingWindowGeometry 计算出的状态环实际外接边界为锚点，在其底边下方保留 8dp 视觉间距；不得覆盖挖孔、状态栏系统图标或状态环本身。状态环视觉隐藏后仍沿用其几何锚点，不能跳到屏幕顶部。
- 若设备没有可靠 DisplayCutout 几何，按现有状态环的手动校准/安全 Insets 降级路径计算位置；不能硬编码屏幕坐标。横条在可用窗口内水平居中，宽度按内容紧凑展开并设置合理最小宽度，保持圆角、半透明 surface 与主题色，与 App 现有状态环/弹窗视觉语言一致。
- 浮窗属于非模态提示：不拦截触摸、不影响导航/播放器/弹窗交互、不获取焦点、不改变 Back 行为；显示/隐藏使用短促淡入淡出，不增加新的动画或系统窗口依赖。
- 若当前值为 stale/unavailable/unsupported/null，音量键不发起写入；浮窗仍可短暂反馈状态，但不得将本地按键目标计算成当前音量。

## 7. 生命周期与故障恢复

- 首次进入 App、进程重建、切换 server origin、WebSocket 重连后，控制能力必须等待当前连接的新完整 snapshot。
- origin/generation 变化立即使旧快照和未发送的按键 delta/滑块草稿失效；旧端点 in-flight 操作遵循 MutationCoordinator 原有 UNKNOWN/归属规则，不得转投新端点。
- 断网时保留 last-known 值只能作为 stale 展示；重连后从新完整快照恢复，不能从 DataStore 或待发送队列还原音量。
- 服务端未验证实体旋钮或其他 ALSA 程序变化是否能被 MPD 感知。Android 只能展示 MPD 当前可确认值，不承诺实体旋钮变化实时同步；不得增加 Android 侧 mixer 轮询。
- MPD 网络断开后的服务端恢复尚未完成实测。Android 必须 fail-closed：恢复成功前不允许写入，恢复后以新的完整快照重新开放。

## 8. 非目标

1. 每个 MPD output ID 独立音量、多个 DAC 独立音量或音频路由切换。
2. Android 本机媒体流音量、本地播放、音频解码或系统 AudioManager 音量管理。
3. 在 App 后台或其他 App 前台全局捕获实体音量键。
4. DAC 型号/USB 描述符/支持采样率清单、bit-perfect 或实体出声验证。
5. 绕过 MPD-Server 直接访问 ALSA/USB mixer，或通过 Android 定期查询来补偿服务端未观测到的物理变化。
6. 新增持久化数据、独立网络 owner、独立状态 Store 或未经批准的服务端 API。

## 9. 验收基线

完成 Task 9 必须满足合同矩阵 T9-01 至 T9-21，并通过：
- DTO/快照兼容、状态/freshness、WS invalidate、水位/连接世代和 mutation 幂等测试；
- 多输入源串行化、连续音量键 repeat、滑块预览与确认值分离、断线/切换 origin 时丢弃未发送意图测试；
- DAC 面板布局/动效/Back/外部点击/深浅主题/字体缩放/无障碍测试；
- 前台所有页面和应用自有弹窗下的音量键测试，以及后台不拦截测试；
- 音量键浮窗在普通页面和应用自有弹窗上方均可见，跟随按键立即更新纯数字，并在最后一次按键后 2 秒隐藏；位置与状态环/挖孔保持安全间距；
- 在真实 Android 设备和已启用的 NAS USB DAC 上完成 slider 与硬件键写入/读回测试；每次 NAS 写入都记录原值并在验证后恢复；
- 对比 mutation 前后歌曲、播放状态、Queue occurrence 与 Queue 顺序，证明未被音量调整改变；
- clean build、unit tests、connected instrumentation、Lint 和最终报告。

Task 9 完成不自动关闭 Task 7 Batch 4 的锁屏验收或 Task 8 全项目端到端退出门。
