# Task 3：原生应用壳、导航、主题与通用状态 UI 合同

> 状态：**Task 3 实施前冻结合同草案，待用户审阅本轮交付结果**。本 Task 仅定义/实现原生 UI 壳与其对 Task 2 共享运行时的投影，不替代 Task 2 的未完成实机验收。
>
> Android 仓库实施基线：Task 2 分支最终证据提交 `6ced024`；对应本地分支 `feature/task-2-network-state-coordinator`。
>
> 固定服务端规格基线：`qyxx2/MPD-Server/main` commit `2ad84454ceb70817bd996c92b590cfa2d9e9b128`。按现行项目决定，不逐 Batch 重新查询上游 HEAD；只有用户要求同步或告知服务端协议变化时才核对受影响合同。

## 1. 目标

用 Jetpack Compose 建立可运行、可测试的原生 App Shell：主页、搜索、音乐库、设置四个固定根入口；设置页之外共享同一个迷你播放器；独立全屏播放器占位目的地；应用内全局状态环与统一活动详情；跟随系统/浅色/深色主题和本地非业务外观偏好。所有实时连接、canonical snapshot 和 mutation owner 均复用 Task 2 的单一应用级 `AppRuntime`。

本 Task 是 UI 结构和通用展示层，不实现完整播放器、曲库/搜索数据流程、歌单收藏、完整服务设置表单或 Android 系统媒体会话。

## 2. 前置判断：Task 2 的残余验收如何处理

Task 2 已具备可编译的网络 transport、严格 DTO、snapshot store、realtime controller、mutation coordinator 与 app-scoped runtime。当前未完成项是：
- 对真实部署进行安全可逆的 mutation/readback；
- 将实际 origin 配置给 Android APK 并完成 APK 级 live-origin 联调。

以上均是 Task 2 **最终集成退出门**，并非 Task 3 UI 壳的硬性技术前置。Task 3 可基于已存在的类型/接口和测试替身推进，不触发真实写操作，不依赖 localhost 占位地址能够连通，也不把 fake/empty 内容表现为真实业务数据。Task 3 首轮根导航、主题、空状态及状态 UI 必须能在无可用服务端时运行。

为避免绕过验收，设置以下门控：
1. Task 3 开始门要求 Task 2 的代码接口、应用级 owner 与机器测试基线存在；此条件当前满足。
2. Task 3 的 UI-only Batch 可按本合同继续完成。不得借机实现真实播放操作、配置服务地址流程、完整业务页面或媒体会话。
3. Task 3 最终退出门要求 Compose/UI 自动测试、构建/Lint、静态边界检查、目标设备的 UI 人工验收完成；Task 2 仍需单独保留 BLOCKED 状态。
4. 任何 UI 动作一旦需要执行写 API，必须留待对应业务 Task；不能因 App Shell 有播放/暂停图标就发起真实 mutation。
5. 完成 Task 3 后，Task 4/6 依赖真实 API 的工作仍以 Task 2 未完成的 live-origin/mutation readback 为风险/集成门。到首次真实写场景之前必须确定安全、可逆并有读回证据的测试动作；不可选择破坏曲库或造成意外播放的操作。

## 3. 权威合同矩阵

| 需求 / 不变量 | 权威来源 | Task 3 冻结合同 | 机器验证 | 人工验证 |
|---|---|---|---|---|
| 四个根入口、顺序、原生布局 | 产品总规格 §§5、7；架构合同 §5 | 主页、搜索、音乐库、设置；原生 Compose，无 WebView；选中项与导航状态一致 | Compose UI test 验证入口标签/选择/切换/返回 | 真机检查点击区域、旋转不在范围内、短屏和窄屏不遮挡 |
| App Shell 层级与迷你播放器显隐 | 产品总规格 §5.2、§6；架构合同 §5 | 紧凑 AppBar/全局状态入口、页面正文、迷你播放器（设置隐藏）、底部导航；mini 位于导航上方且参与正常布局流 | 验证主页/搜索/库显示、设置隐藏，正文没有被覆盖 | 真机检查安全区、软键盘、大字体下几何与触摸 |
| 迷你播放器内容边界 | 产品总规格 §6；Web 行为合同 §§3–5 | 仅封面/占位、标题、艺术家、播放/暂停单按钮；本 Task 只渲染，不执行播放写操作；无 current / stale 时按合同显示占位并禁用 | 已知/空/stale/unknown 快照的呈现和按钮 enabled 测试 | 真机检查空状态清楚、按钮确实无法误触执行 |
| 全屏播放目的地 | 产品总规格 §5.2、§7.5 | 独立 route 可进/退，隐藏根 mini 和根导航；仅建结构占位，不包含完整播放功能 | route 导航、返回恢复根入口状态；无 runtime 重建 | 真机验证进/退手势/返回按钮，未实现控件无虚假可用状态 |
| 主题与偏好 | 产品总规格 §8；架构合同 §2 | Follow System / Light / Dark；使用本地 UI 偏好存储，不写后端；品牌色与语义对比度按总规格 | 主题选择状态、重建恢复、色彩 token 配置测试 | 真机/系统检查浅深主题、状态栏图标、可读性 |
| 状态环/统一活动详情 | 产品总规格 §5.3；Web 行为合同 §9；架构合同 §7 | 只作共享 runtime 连接/快照/活动事实的视觉投影；单一入口/详情，不创建页面专属活动 owner；本 Batch 不实现写操作重试 | 连接状态与 empty activity 映射；打开/关闭详情；旋转/导航不重复 owner | 真机检查文字可读、状态不只靠颜色表达、详情可关闭 |
| 共享运行时所有权 | Task 2 合同 §§2、5.2、5.3；架构合同 §§4、6 | 仅 Application 提供的唯一 `AppRuntime`；页面/ViewModel/Composable 只能收取/观察引用，不得 new `AppRuntime`/Socket/Store/Coordinator | UI 导航过程 runtime identity 不变；源代码边界检查 | 页面反复切换时连接/活动状态不丢失 |
| 本地偏好与业务边界 | 产品总规格 §§4.1、7.4、8；架构合同 §§2–3 | 只存主题及本 Task 明确实现的 UI 外观参数；无 Room/SQLite，无业务 snapshot 持久化，无伪造歌曲/推荐数据 | 依赖和代码扫描，偏好读写单测 | 重启后本地主题保持，业务事实仍显示为空/未连接而非假数据 |
| 可访问性与屏幕适配 | 产品总规格 §§3、8.4 | 触控目标以 48dp 为设计基线；文字尊重 sp/系统字体缩放；insets 正确；窄屏/短屏允许滚动 | 语义节点与关键导航测试、窗口尺寸/字体配置可测用例 | 目标设备检查大字体、短屏、窄屏、键盘、安全区 |

## 4. 文件与模块边界

预计新增/修改均落在 `app/src/main/java/io/github/qyxx2/mpdserver/remote` 下，沿用单一 `app` 模块：
- `MainActivity.kt`：应用 UI 启动入口，取得 Application 提供的 runtime，不负责构造 network owner。
- `ui/navigation/`：根 routes、导航宿主、全屏目的地和路由恢复。
- `ui/shell/`：共享 Scaffold/AppBar、迷你播放器、底部导航与全局状态入口。
- `ui/screens/`：主页/搜索/音乐库/设置空状态和全屏播放器占位页面。非本 Task 功能必须明确为占位/未连接，不展示模拟的歌曲或服务端结果。
- `ui/components/`：活动详情、状态环、加载/空/错误语义组件。
- `ui/theme/`：颜色语义、主题映射、Theme composable。
- `ui/preferences/`（或同等最小封装）：只保存非业务 UI 偏好。若现有版本目录没有可用的 DataStore，先评估与当前依赖兼容性并更新合同，不准偷偷添加无关库。
- `app/src/test`：Compose UI/navigation test、主题/偏好测试与必要的共享状态投影单测。

具体包路径和公开接口需在 Batch 0 映射后确定；不得为追求抽象提前拆 Gradle 模块。

## 5. 非目标与硬约束

- 不实现 Task 4 完整播放器/歌词/进度/Queue，不实现 Task 5 主页真实数据、搜索/曲库/歌单/收藏业务，不实现 Task 6 完整服务器设置表单/配置 origin 流程，不实现 Task 7 MediaSession/后台控制。
- 不创建假的曲目、封面、播放队列、历史、推荐结果或伪成功提示。未实现页面使用清晰的空/占位说明。
- 本 Task mini 播放/暂停按钮不可调用真实 mutation；若快照表明状态可操作，可明确禁用并使用“播放控制将在播放器阶段接入”的语义说明。不得制造会成功的假按钮。
- 不直接在 Compose 页面中发 HTTP/WS；不添加新的 socket、轮询 owner 或页面级 store；不把 Task 2 实机未验证写成 Task 3 已验证。
- 不新增业务数据库、WebView、本地播放引擎、服务端接口、CI workflow 或远端构建。
- 不改动服务端仓库，不重新核对固定上游 HEAD。

## 6. 关键 UI/状态规则

- 根入口选中态与内容目的地一致，重新选择当前入口不得创建新的 runtime。
- 全屏播放目的地与根导航分开：进入后隐藏 mini 与底部导航；返回时恢复原根入口/本地滚动状态（本 Task 测试只需验证导航 back stack 不被重置）。
- 设置页隐藏 mini，但全局状态入口仍可使用。
- mini 区域固定参与 layout；不得覆盖正文或 bottom navigation。AppBar 紧凑，不通过过高头部挤压正文。
- 主题默认 Follow System；浅色采用柔和近白底、强调色 `#DE7622`；深色使用深色中性表面和 `#BE7E4A` 或经对比验证的暗化橙色。禁止仅靠颜色表达 error/stale/disabled。
- 没有已接纳的 snapshot、快照 stale/unknown 或当前播放观察未确认时，展示状态必须如实表达，不可从业务 `playback.state` 单独推断真实播放。
- 任何待实现入口/按钮都应是明确占位且不可误触提交，或者在 UI 中隐藏；不得伪装为已交付能力。
- 本地偏好不得覆盖服务端事实，也不得在新进程恢复旧快照作为 canonical state。

## 7. Batch 计划及依赖

- Batch 0：合同/源码映射与 UI 导航/主题/状态投影接口冻结（不写产品功能代码）。
- Batch 1：主题 token、主题入口和本地主题偏好/测试。
- Batch 2：四入口路由、导航宿主与返回栈/状态保存。
- Batch 3：共享 App Shell（AppBar、根正文布局、底部导航、安全区）。
- Batch 4：迷你播放器纯展示投影、空/stale 状态、不可执行的预留控制与测试。
- Batch 5：全局状态环、统一活动详情与 runtime observation 集成。
- Batch 6：全屏播放器占位目的地、壳集成与导航恢复回归。
- Batch 7：完整机器回归、代码边界审查、目标设备人工 UI 验收记录。

每个 Batch 必须独立测试、写入 Task 专用进度账本、形成单独提交；本轮只冻结计划，不开始实施 UI 功能。

## 8. 验证与退出门

### 机器验证
- 使用冻结 Gradle Wrapper 本地执行 `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`；需要时最终 clean build。
- Compose UI 测试覆盖四项入口、设置页 mini 显隐、全屏目的地进入/返回、主题切换、状态详情打开/关闭。
- 通过 runtime identity/DI 测试与边界扫描证明导航不重新构建 `AppRuntime`、WebSocket、Store 或 MutationCoordinator。
- 主题偏好只写本地非业务存储；没有服务端连接时 UI 启动仍成功。
- `git diff --check` 通过；无 Room/SQLite、WebView、Media3/ExoPlayer、第二个 realtime controller、服务端写入行为、伪造业务数据或 CI workflow。

### 人工/实机验证
- 在目标 Android 真机上安装并启动 Task 3 APK。
- 检查四项根入口的视觉顺序/选择态、mini 显隐、播放器占位目的地进退、系统返回、浅色/深色/跟随系统。
- 检查安全区/状态栏、常见短屏和窄屏、大字体、48dp 触摸目标、键盘出现时布局、状态环和活动详情可读性。
- 只验收 UI 与导航，不以缺少真实 MPD-Server 联调判失败，也不把 UI 展示正常当作 Task 2 API/mutation 通过证据。

### Task 3 退出门
- [ ] 合同矩阵均映射到 Batch、测试和验收证据。
- [ ] 自动化测试、Lint、构建、边界扫描和 diff review 通过。
- [ ] 用户报告真实设备 UI/主题/导航人工验收通过，结果写入文档。
- [ ] Task 2 仍以单独状态记录：真实 mutation/readback 与 APK live-origin 未完成时保持 BLOCKED；不把此项重命名为 Task 3 的 UI 失败，也不据此声称整个客户端可真实遥控服务端。
- [ ] Task 4/6 开始实际服务集成前，完成 Task 2 遗留真实联调，或在该业务 task 合同中明确安全可逆的补验操作及退出门；不得无记录地跳过。

## 9. 执行约定

- 工作分支：`feature/task-3-native-app-shell`。
- 唯一实施工作区：`/home/Gold/WorkSpace/MS_remote_for_Android/.worktrees/task-3-native-app-shell`；主工作区留在 Task 2 分支且不作 Task 3 代码更改。
- 每个 Batch 开始时记录本 Android task 分支 HEAD；遵守固定上游基线规则，不重复查询上游。
- Batch 只做计划中指定范围；测试先行；每个 Batch 独立提交；记录机器验证命令和结果。不得执行下一个 Batch 的 UI 实现，直至用户另行明确要求。
