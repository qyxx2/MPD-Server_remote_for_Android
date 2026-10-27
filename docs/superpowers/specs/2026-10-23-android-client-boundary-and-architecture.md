# Android 客户端权威边界与架构规格

> **状态：权威架构与范围合同。** 本文与 [Android 客户端产品与工程总规格](2026-10-24-android-client-product-and-engineering-spec.md)、后端接口合同和 Web 行为继承合同共同构成 Android 客户端基线。产品与工程总规格决定 Android 产品范围和顶层 UI；本文决定服务端与客户端职责边界。精确视觉规格、依赖版本和批次验收门槛可继续细化，但不得改变此责任边界。
> 上游核对基线：`qyxx2/MPD-Server/main` commit `2ad84454ceb70817bd996c92b590cfa2d9e9b128`（2026-10-18）。

## 1. 产品定位

建立独立的 Kotlin + Jetpack Compose 原生 Android 客户端，作为 MPD-Server 的遥控和状态展示端，不使用 WebView 套壳。尽可能复用现有 HTTP API、WebSocket、DTO 与 Web 行为，不无故增加后端复杂度。

## 2. 唯一权威与职责边界

### MPD-Server 负责，Android 不复制

- 后端数据库、schema/migration、曲库索引、歌曲元数据和文件可用性。
- Queue、Played、Playback Context、History、AutoPlay 和播放编排。
- 业务 Queue occurrence 与 MPD 执行队列绑定、MPD ID 映射、recovery/reconciliation 和执行回执。
- 真实播放观察、epoch/sequence/revision、输出观察、曲库同步状态。
- Collection 查询/排序/随机选择、搜索、Playlist/Favorites 业务规则。
- MPD 连接与输出设备控制、事务、错误码、幂等及最终 mutation 结果。

### Android 负责

- Compose 原生 UI、导航、可访问性、排版与动画。
- 经 HTTP 发出用户明确发起的已有操作，经 WebSocket 接收 invalidate 并读取权威快照/资源。
- DTO 校验、内存中的规范化快照、fresh/stale/unknown 展示、操作 pending/unknown/failed 状态与恢复交互。
- 暂时性 UI 状态：路由、封面/歌词模式、搜索输入、列表滚动、seek/drag 草稿、弹窗与状态环动画。
- 只持久化非业务偏好，如服务地址和状态环尺寸/位置偏移/线宽。业务实体不得靠本地持久层成为另一数据来源。

## 3. 明确非目标

- 不新增 Room/SQLite 本地业务数据库，不镜像歌曲库、Queue、History、Playlist 或 Favorites 并离线维护一份权威副本。
- 不把持久化的 FullStateSnapshot 作为下一次启动时的权威状态。last-known 可在内存中供说明，但必须标 stale/unknown，写操作等完整新快照后再开放。
- 不直接操作 MPD、不使用 ExoPlayer/Media3 作为本项目播放引擎、不本地解码 NAS 音频、不处理服务端文件或数据库。
- 不在客户端重新生成 AutoPlay、决定下一首、写 History、猜 actual current、构造后端缺失业务状态或代替服务器完成 reconciliation。
- 不为安卓专属 UI 偏好新增后端 endpoint，除非已确认现有 API 确实无法满足新增的业务需求。

## 4. 建议原生结构

```text
Compose Screens
    ↓
ViewModel / StateFlow
    ↓
Shared Snapshot Store + Operation Coordinator
    ├── Realtime Controller
    ├── Playback API repository
    ├── Library/Playlist API repositories
    └── Settings API repository
    ↓
HTTP + WebSocket
    ↓
MPD-Server（唯一业务状态来源）
```

只保持一个长期 realtime controller、一个完整状态 store 和一个共享的操作协调器。Repository 是薄网络/DTO 层，不放置与服务端并行的领域规则。可采用 ViewModel、Coroutines/StateFlow、OkHttp、Kotlin Serialization、Navigation Compose、Coil、DataStore 等原生实现；具体版本留待 Android 项目骨架阶段核验。

## 5. 页面和共享状态

| 页面/区域 | 数据源和责任 |
|---|---|
| 主页 | 完整快照及其状态，显示服务端确认的播放事实和入口 |
| 迷你播放器 | 共享状态 store 的紧凑视图；不得自行轮询或维护 current |
| 全屏正在播放 | 当前歌曲、观察、封面、歌词、展示时钟和可用控制能力 |
| 全屏队列 | 完整服务端 Queue 及现有 play/reorder/delete/save API |
| 搜索 | 现有 Library search/result API |
| 库 | Library、Collection、Playlist、Favorites API |
| 设置 | `/api/system/*`；安卓专属可视偏好由本地保存 |
| 应用内状态环 | 共享活动/连接/freshness 的视觉投影，不是数据来源 |

一级导航为主页、搜索、库、设置。主页/搜索/库显示同一个迷你播放器，位于底部导航上方；设置页不显示。迷你播放器点击进入独立全屏正在播放页，该页提供“正在播放/队列”切换。页面可被销毁/重建，但共享连接与未解决操作不得因此重建或丢失。

## 6. 缓存与操作边界

- 以服务端 FullStateSnapshot 为 canonical state，按 epoch、sequence、resource revisions、library_sync generation、explicit_stop_generation 处理更新。
- 缓存只优化重复 GET。资源缓存必须绑定当前会话/对应 revision；不能跨 epoch 将旧业务数据当作事实。
- invalidate 触发权威重读，不从 domains 自行拼凑本地 state patch。
- 连接失效或 observation stale/unknown 时可以保留 last-known 展示，但必须显式区分过期事实与当前事实，并禁用依赖 fresh target 的 controls。
- 共享操作协调器保留 key/payload/状态/恢复动作，统一展示 unknown 与 read-failure；UI 的成功动画不能越过服务端确认。

## 7. 应用内全局状态环

- Android 窗口采用 edge-to-edge：应用内容绘制到透明状态栏背景下，不额外为整条状态栏预留顶部布局空间；必须通过 WindowInsets 正确处理系统栏和显示挖孔。
- 状态环只在 MPD-Server 自身应用窗口中显示，不实现跨应用悬浮窗，不申请“显示在其他应用上层”权限。
- 状态环优先以系统报告的居中摄像头挖孔几何作为自动定位锚点，围绕挖孔显示轻量动态弧；不得仅凭固定屏幕坐标猜测自动定位结果。若 DisplayCutout 缺失、不适用或不可靠，必须进入安全的手动定位/校准降级路径，并保留设置中的尺寸、线宽、水平偏移和垂直偏移自定义能力；不得仅因无法取得挖孔几何就直接隐藏状态环。任何定位方式均须遵守系统安全 Insets、系统图标避让和摄像头区域安全边界。
- 环由共享活动、连接状态和服务端 observation/freshness 驱动；有活动时显示，活动结束后延迟 5 秒隐藏，新活动重置计时。未解决的 UNKNOWN/FAILED 不得被计时器伪装成已解决。
- 蓝色表示一般进行中，橙色表示本应用待处理/品牌操作，红色表示失败/结果未知；颜色之外仍需提供语义标签/详情。环的本地尺寸、线宽和安全偏移不得写入 MPD-Server，也不能侵入挖孔、系统状态图标或系统手势交互区域。
- 活动详情必须有独立可访问入口，不能依赖自动隐藏的圆环作为唯一入口；重试只能执行原 intent/key，重新读取不能伪造成功。状态环本身不决定 controls 能力。

## 8. 基线更新与关键验收

若目标 `MPD-Server/main` 前进到新 HEAD，先核对 `server/app/api`、相关 schema、`server/app/main.py`、Web services/stores/runtime/player/queue/layout 代码、现行规格与测试的差异，再更新本仓库所有受影响的权威合同（产品与工程总规格、后端接口与实时协议、Web 行为继承、客户端边界与架构）；未受影响的合同不必机械修改。

首轮集成至少验证：完整初始快照和重连、旧 epoch/sequence 拒绝、读请求中收到更高水位、unknown mutation 同 key 重试、current target 变化、重复歌曲 occurrence、Queue clear 不等于 Stop、歌词普通文本/时钟 stale、输出及曲库状态错误。

## 9. 上游引用

- [MPD-Server main baseline](https://github.com/qyxx2/MPD-Server/commit/2ad84454ceb70817bd996c92b590cfa2d9e9b128)
- [Web realtime client](https://github.com/qyxx2/MPD-Server/blob/2ad84454ceb70817bd996c92b590cfa2d9e9b128/web/src/services/realtime.ts)
- [Web playback actions](https://github.com/qyxx2/MPD-Server/blob/2ad84454ceb70817bd996c92b590cfa2d9e9b128/web/src/components/player/playerActions.ts)
- [Web activities](https://github.com/qyxx2/MPD-Server/blob/2ad84454ceb70817bd996c92b590cfa2d9e9b128/web/src/stores/notices.ts)