# Task 6 设置结构与 MPD 状态合同矩阵

> 状态：Task 6 新授权范围；取代主计划中原 Task 6 的完整服务器系统配置范围。当前阶段只冻结合同与分批计划，不实现产品代码。

## 1. 目标与边界

将现有设置入口统一为四个一级分类：**状态、服务器、外观、关于**。保留现有本地偏好与活动数据来源；将已存在的 MPD 状态读取能力接入服务器分类；改善状态环参数密度与真实位置预览。

MPD-Server 仍是唯一服务端状态权威。Android 只读取现有 API 并显示，不复制 MPD 管理、连接恢复、播放或输出逻辑。Android 配置的 MS origin 与 MS 服务端连接 MPD 的状态必须明确区分。

### 本次包含

- 设置根页面显示「状态」「服务器」「外观」「关于」四个入口。
- 状态页面承载当前 AppBar「活动」入口原本展示的连接状态、快照新鲜度、活动列表和未知结果重试能力。
- 服务器页面保留单行 MS 地址输入和「保存服务器地址」；输入框的感叹号图标切换显示/隐藏已有地址格式提示。
- 服务器页面读取现有 `GET /api/system/mpd` 并展示 MPD 版本、连接、统计、运行时长、数据库更新时间、更新状态及错误。
- 外观页面承载现有主题选择和全部状态环设置。
- 状态环四项原设置均保留；控件布局浓缩；实时预览使用真实窗口坐标和生产环境同一套定位/绘制逻辑。
- 关于页面直接显示软件简介、仓库链接和作者，不再进入二级设置列表。

### 明确不做

- 不实现旧 Task 6 计划中的 MPD 连接目标编辑/保存、输出模式设置或输出启停操作。
- 不增加服务端 API、数据库、MPD TCP 连接、第二个 WebSocket、第二个 AppRuntime/OkHttpClient 或第二份业务状态。
- 不改变现有播放、Queue、Library、History、Playlist、Favorites 行为。
- 不改变包名、签名身份、DataStore 文件名或现有偏好 key；不清除应用数据。
- 不做持续后台轮询；MPD 状态在进入服务器页时读取，并提供页面内手动刷新。

## 2. 现状核对与证据

- 当前工作区为 `main`，HEAD 与 `origin/main` 一致，Task 6 分支原先不存在；本阶段已从该基线创建 `task6`。
- 当前 `StatusRingSettingsScreen` 将 MS origin、主题和状态环参数放在同一纵向页面；MS 输入下方常驻提示、当前配置文本和保存结果文本均存在。
- `AppBar` 在所有根页面显示「活动」按钮；`AppNavHost` 已经集中构造 `ActivityPresentation`，并复用唯一的 `runtime.mutations.activities`。
- `ActivityStatusSheet` 已显示连接状态、快照新鲜度、最近活动、错误和 UNKNOWN 重试；重构必须继续复用同一 `ActivityPresentation` 与原重试回调。
- `StatusRingPreferences` 的四个 DataStore key 已持久化：尺寸、线宽、水平偏移、垂直偏移。当前位置范围为 ±24dp，步进 2dp；必须兼容已存值。
- `StatusRingPlacementCalculator` 和 `GlobalStatusRingOverlay` 已用于真实状态环。当前 `StatusRingSettingsPreview` 被限制在 92dp 高的设置卡片中，并将整窗几何缩放到该卡片，因此不对应真实窗口中的实际位置。
- `SystemRepository.getMpd()` 已指向 `GET /api/system/mpd`，但当前没有调用点，返回类型仍为 `JsonElement`。应复用该现有端点。
- 已对当前安装包保存的 MS origin 执行只读 GET：`/api/system/mpd` 返回 HTTP 200。观察到的 wire fields 为 `version`、`version_source`、`stats.artists/albums/songs/uptime/db_playtime/db_update/playtime`、`database_update_status.updating/job_id`、`connected`、`errors`、`observed_at`。本报告不固化任何设备地址或动态值。

## 3. 设置导航与页面合同

### T6-01 — 分类树与导航

| 项目 | 合同 |
|---|---|
| 根路由 | 保留现有 `settings` 根入口及底部「设置」选中态。 |
| 分类入口 | 根页面按「状态」「服务器」「外观」「关于」显示四个分类入口。 |
| 子页面路由 | 使用现有 Compose Navigation/NavController 增加设置子路由；分类子页通过系统返回/边缘返回回到设置根页面。 |
| AppBar | 全局移除右上角「活动」入口。AppBar 标题保留；设置子页标题与分类一致，并提供明确的返回入口。 |
| 关于 | 点击后直接进入关于内容页，不再显示二级分类列表。 |

### T6-02 — 状态页面

- 移除 AppBar 的「活动」按钮，不再使用弹出的活动 BottomSheet 作为入口。
- 状态页面内显示原活动面板的信息：连接状态、快照新鲜度/确认状态、读取错误、最近操作活动、活动状态及同步错误。
- 对可安全重试的 UNKNOWN 活动仍显示「使用原操作重试」，必须调用现有 Player/Home/Playlist/Favorites/MutationCoordinator 对应的原 retry handler。
- 活动为空时显示明确空态。
- 页面不得创建自己的 Activity/Mutation Store，不得将本地预览写入活动历史。

### T6-03 — 服务器地址输入

- 保留单行地址输入框和「保存服务器地址」按钮两个主要控件；删除独立的「当前配置」常驻行和默认展开的长提示。
- 输入框 trailing area 放置小型「!」提示图标。点击一次显示既有格式提示，再次点击隐藏；图标不覆盖输入、光标或保存操作。
- 提示文案继续说明 IPv4、IPv6、域名、完整 HTTP(S) origin、缺省 HTTP、默认端口 18000，以及 IPv6 指定端口的 `[地址]:端口` 写法。
- 保存仍调用现有 `ServerOrigin.parseAddress` 和应用级 `saveAndApplyServerOrigin`/共享 Runtime 流程；不新增连接对象。
- 地址错误、保存中、保存成功/失败反馈必须保留，避免无反馈提交；成功文案不得暗示已验证网络连通性。
- 不显示第二个服务器地址输入项；不编辑服务端保存的 MPD 连接目标。

### T6-04 — 外观与主题

- 跟随系统、浅色、深色三种主题选择全部保留；仍写入现有 DataStore。
- 状态环保留尺寸、线宽、水平偏移、垂直偏移和恢复默认五项能力。
- 控件使用紧凑布局：建议两列设置卡片，每项标题/当前值同一行，滑杆紧凑排列；删除重复的逐项范围说明文字，但保留可访问语义、当前数值和足够触控高度。
- 不移除或合并任何已有的状态环参数；保存与恢复默认仍持久化到原有四个 key。
- 水平偏移暂时保持现有 ±24dp、2dp 步进；尺寸和线宽范围/步进保持现有合同。
- 垂直偏移调整为 **-12dp 至 +12dp，1dp 步进**。这是对现有 ±24dp/2dp 的缩窄；归一化时将旧 DataStore 值夹取到新范围，旧 key 不变。
- 预览移出 92dp 示意卡片，改为在外观页面打开期间显示于真实窗口坐标中的状态环。它必须调用与生产覆盖层相同的 `StatusRingPlacementCalculator`、真实 `StatusRingWindowGeometry` 和绘制组件，并实时响应当前未保存的 draft 参数。
- 预览只使用本地 INFO 示例状态，不调用 API、不改写 Runtime/活动列表；离开外观页面立即停止预览。非外观页面仍严格遵守既有状态环活动触发和 5 秒隐藏规则。
- 真实几何不可用时仍走现有手动回退定位，不伪称识别到挖孔。

### T6-05 — 关于页面

显示：
- 软件名称：MPD-Server Remote for Android。
- 简介：MPD-Server 的原生 Android 遥控与状态展示客户端，使用 Kotlin + Jetpack Compose，不使用 WebView 套壳。
- 仓库链接：`https://github.com/qyxx2/MPD-Server_remote_for_Android`，可点击打开系统浏览器。
- 作者：`qyxx2`。
- 可显示当前应用版本；版本值来自现有 BuildConfig，不硬编码。

## 4. MPD 状态读取与展示合同

### T6-06 — 数据源和生命周期

- 唯一数据源为现有 `GET /api/system/mpd`；不从本地播放状态、WebSocket Queue 或 Android 网络连接状态推断 MPD 连接状态。
- 优先将读取能力并入现有 Application-owned `AppRuntime.reads` / `RuntimeReadApi`，使用其现有 OkHttpClient、动态 origin provider 和 bounded-read 协调能力；不为设置页创建独立网络 owner。
- 进入服务器分类时读取一次；提供明确的手动刷新入口。不启用后台轮询。
- 请求开始时捕获 origin；若请求完成前 origin 已切换，丢弃旧 origin 的结果，不得将旧服务器状态显示为新服务器状态。
- 同一 origin 的刷新失败时，可以保留最近一次成功数据，但必须标记为过期并展示错误；切换 origin 后不复用旧 origin 的数据。
- 仅 GET；不触发任何 MPD 配置写入、输出操作、播放或 Queue mutation。

### T6-07 — Wire fields 与显示映射

| Wire field | UI 显示 | 规则 |
|---|---|---|
| `version` | MPD 版本 | 原样显示；缺失显示未知。 |
| `connected` | MPD 连接状态 | 明确区分「已连接/未连接/未知」；这是 MS 服务端到 MPD 的连接，不是 Android 到 MS 的连接。 |
| `stats.artists` | 艺术家数量 | 整数计数。 |
| `stats.albums` | 专辑数量 | 整数计数。 |
| `stats.songs` | 歌曲数量 | 整数计数。 |
| `stats.uptime` | MPD 运行时间 | 秒转为天/时/分/秒。 |
| `stats.db_playtime` | 曲库总时长 | 秒转为天/时/分/秒。 |
| `stats.playtime` | 累计播放时长 | 秒转为天/时/分/秒。 |
| `stats.db_update` | 曲库更新时间 | Unix epoch 秒，格式化为本地日期时间；不能误当作时长。 |
| `database_update_status.updating` | 数据库更新状态 | true 显示更新中，false 显示未在更新；缺失显示未知。 |
| `database_update_status.job_id` | 更新任务标识 | 非 null 时可作为辅助信息展示；null 不显示占位任务。 |
| `errors` | MPD 相关错误 | 保留服务端返回的动态 key/value；不得把非空错误对象隐藏成普通成功。 |
| `observed_at` | 状态观测时间 | 按 ISO-8601 解析并显示本地日期时间；解析失败显示原值或未知。 |
| `version_source` | 版本来源（辅助信息） | 可作为次要诊断文案；不影响版本主字段。 |

### T6-07a — DTO 类型与缺失字段

- `MpdStatusDto`：可空 `version:String?`、`version_source:String?`、`stats:MpdStatsDto?`、`database_update_status:MpdDatabaseUpdateStatusDto?`、`connected:Boolean?`、`errors:Map<String, JsonElement>?`、`observed_at:String?`。
- `MpdStatsDto`：计数 `artists/albums/songs:Long?`；时长 `uptime/db_playtime/playtime:Double?`；时间点 `db_update:Long?`（Unix epoch 秒）。
- `MpdDatabaseUpdateStatusDto`：`updating:Boolean?`，`job_id:JsonElement?`，确保服务端未来将 job id 作为字符串或数值返回时不被错误强制转换。
- 所有字段保留缺失/null；不得通过 DTO 默认值把未知状态转为 false、0 或空成功结果。错误 map 按动态 key 迭代展示，值以 JSON 形式保留。

### T6-08 — 秒数格式化

- 适用于所有明确以秒为单位的数值字段：`uptime`、`db_playtime`、`playtime`，以及后续同一 DTO 中明确标注为 duration-seconds 的字段。
- 先向下取整，忽略小数，不四舍五入；再拆分为天、小时、分钟、秒，格式为「X天 X小时 X分 X秒」。
- 所有单位固定显示，避免同一页面因数量级改变而难以比较。
- null、缺失、非有限或负值显示「未知」，不得伪装成 0 秒。
- `db_update` 和 `observed_at` 是时间点，不参与 duration 格式化。

## 5. 单一权威与数据兼容约束

- `AppRuntime`、`SnapshotStore`、`RealtimeController`、`MutationCoordinator` 仍只有 Application 级一个实例。
- 状态页面使用现有 `ActivityPresentation` 和 activities Flow；没有自己的活动数据副本。
- MPD 状态虽然经独立 HTTP endpoint 读取，但来源仍是 MPD-Server；页面状态仅是可丢弃的读取投影，不写入 DataStore。
- 所有本地偏好仍使用现有 `UiPreferencesRepository` 与 DataStore；服务器 origin、theme、status-ring keys 及文件名不变。
- 垂直偏移旧值通过新范围归一化兼容；不新增迁移文件，不清除用户数据。
- Web 与 Android 的业务行为无需复制；本 Task 仅展示上游现有系统状态，不增加服务端业务规则。

## 6. Task 6 合同矩阵

| ID | 冻结合同 | 实现归属 | 自动化证据 | 设备/联调证据 |
|---|---|---|---|---|
| T6-01 | 四分类、子路由、关于直达、返回行为 | B1 | 导航 UI tests | 真机逐类打开/返回，Settings tab 保持选中 |
| T6-02 | 活动状态从 AppBar 移入状态页，原重试语义不变 | B1 | Activity content/controller tests | 空活动、进行中、UNKNOWN retry 显示正确 |
| T6-03 | 单行服务器地址输入、可切换提示、保存反馈 | B1 | Compose 输入/保存/提示测试 | 保存后现有地址保留，覆盖安装后仍可连接 |
| T6-04 | 主题和所有环参数保留；紧凑控件；真实位置预览；垂直 -12..12/1dp | B1 | Preferences/geometry tests | 挖孔处预览跟随 draft，恢复默认并重新进入仍生效 |
| T6-05 | About 文案、作者、仓库链接、版本来源 | B1 | Compose content/link intent tests | 真机点击仓库链接 |
| T6-06 | 只读 GET、共享 Runtime 网络所有权、origin 防迟到 | B2 | RuntimeReadApi/mock server tests | 当前已配置 server 返回实时状态 |
| T6-07 | MPD 状态 fields 显示及 null/error 语义 | B2 | DTO parser + UI state tests | connected/disconnected、错误和更新时间展示 |
| T6-08 | 秒数向下取整并转换为天时分秒 | B2 | formatter boundary tests | 使用实时返回值核对三项时长 |
| T6-09 | 不更改播放/Queue/Library/数据源权威 | B1–B2 | Task 4/5 regression + mutation-count assertions | 服务器状态读取期间不发起写请求 |
| T6-10 | APK 覆盖安装且保留用户数据 | B1–B2 | 包名/签名构建检查 | `adb install -r` 后核对 origin/theme/ring 偏好，禁止卸载或清数据 |

## 7. 分批决策

本 Task 应拆为 **两个实现 Batch**，不建议一个 Batch 全部实现：

1. 设置导航与本地设置重构、状态页迁移、关于页面、状态环紧凑布局及真实位置预览，彼此共享设置路由/页面结构，可以形成完整可验收的设置信息架构。
2. MPD 状态 DTO、共享读取接口、时长转换与服务器页展示，属于独立的数据读取链，可在设置页骨架稳定后单独验收。

将状态环另拆为第三批会重复承担设置导航/UI 集成成本；把 MPD 读取放进第一批则会把不同故障面（导航/偏好与网络 DTO/迟到响应）耦合在同一次验收中。故采用两个 Batch。

## 8. 退出门

Task 6 只有同时满足下列条件才可标记完成：

- T6-01 至 T6-10 均有自动化或真机证据。
- 现有服务器 origin、主题和状态环偏好在覆盖安装后保留。
- AppBar 不再出现「活动」按钮；状态分类显示原有活动数据及重试能力。
- 服务器页清楚区分 Android→MS 的配置/连接与 MS→MPD 的 `connected` 状态。
- `/api/system/mpd` 只读，秒数格式化正确，时间点字段没有误转成持续时长。
- 预览在真实窗口/挖孔位置显示，使用生产几何和绘制实现，不改变 Runtime。
- Task 4/5 页面、播放器、Queue、realtime、缓存与 mutation 回归通过。
- 完成 Debug APK 覆盖安装测试；未卸载应用、未清除数据、未更换签名或包名。
- 不合并、不推送，除非用户另行明确授权。
