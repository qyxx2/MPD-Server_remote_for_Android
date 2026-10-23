# MPD-Server 安卓原生客户端初步实现路线图

> **状态：阶段路线图，非逐批执行计划。** 本文概括实现顺序；接口与 Web 行为以 `docs/superpowers/specs/` 下三份权威合同为准。开始每个具体阶段前，仍需制定对应详细计划和验收条件。

## 目标与核心边界

使用 Kotlin + Jetpack Compose 开发独立原生 Android 客户端，不使用 WebView。MPD-Server 是唯一业务和状态权威。Android 尽量作为遥控与状态展示端，复用已有 HTTP API、WebSocket、DTO 和 Web 操作语义，不另建业务数据库，不复制 Queue、AutoPlay、History、MPD 绑定/恢复或服务端事务。

## 推荐实现阶段

### 0. 核对合同与工具链

- 阅读 [Android 文档索引](../README.md) 中的三份权威规格；实施前重新检查目标 `MPD-Server/main` 的实际 HEAD 和差异。
- 确定 Android 最低系统版本、构建环境、依赖版本以及本地调试用的服务地址。
- 将详细 UI 比例、动效和各阶段验收门槛单独细化，不在路线图中预先冻结。

### 1. 原生工程与基础 UI

- 建立 Kotlin/Compose 工程、主题、导航和基本测试入口。
- 先确定网络、共享状态、操作协调、ViewModel 与页面之间的边界。
- 此阶段不创建本地业务数据库，也不接入第二个音频播放引擎。

### 2. API、DTO 与实时状态

- 对接 `GET /api/state`、`WS /api/realtime` 和页面所需资源 API。
- 实现严格 DTO 校验、完整初始快照、invalidate 后重新读取、断线重连和错误表达。
- 保留 epoch、sequence、revision、fresh/stale/unknown 和控制目标语义；旧状态不能覆盖新状态。
- 建立共享 mutation intent：遵守 `Idempotency-Key`；结果未知时保留原 key/payload，禁止自动换 key 重发。
- 通过模拟协议场景验证重连、乱序、超时和操作未知结果，再接入真实服务。

### 3. 播放核心与队列

- 实现全屏正在播放/队列界面、播放控制、进度、歌曲信息、封面与原生歌词。
- 使用服务端的 actual playback observation 和 target；请求发出或 HTTP 成功不等于实际播放已经确认。
- 队列交互按 occurrence 身份操作；保留 Play Now/Next/Queue、排序、删除、清除待播与保存队列等现有语义。
- seek、resume、拖动草稿和未知结果遵守行为合同，不在客户端重新编排播放。

### 4. 应用壳与四页导航

- 实现主页、搜索、库、设置四个一级入口。
- 主页/搜索/库常驻显示迷你播放器并放在底部导航上方；设置页不显示。
- 点击迷你播放器进入独立全屏正在播放页面，该页面提供“正在播放/队列”切换入口。
- 页面切换只改变展示区域，不重复建立 WebSocket、不丢失尚未解决的操作。

### 5. 搜索、曲库、歌单、收藏与设置

- 复用现有 Library/Search/Collection/Playlist/Favorites/System API，展示服务端资源和错误。
- 集合筛选、播放顺序、AutoPlay、收藏与歌单成员规则全部由服务器解释与保存。
- 安卓专属 UI 偏好使用本地轻量存储；不为本地显示偏好新增服务端接口。

### 6. 应用内全局状态环

- 状态环只在 MPD-Server 自身应用的所有页面中显示，不实现跨应用浮层、不申请悬浮窗权限。
- 由共享活动 store、连接状态和服务端 observation/freshness 驱动颜色与动画；状态环不是另一份状态来源。
- 设置可配置圆环尺寸、位置偏移和线宽，以适应不同手机的挖孔/状态栏布局。
- 点击状态环打开统一活动详情；重试只使用原操作 intent/key，重新读取不冒充操作成功。

### 7. 联调、回归与界面打磨

- 对照当前 Web 行为验证播放/暂停、切歌、seek、Queue、歌词、搜索/曲库、收藏/歌单和系统设置。
- 覆盖重连、旧 epoch/sequence、操作结果未知、current target 变化、重复歌曲 occurrence、歌词不可同步、输出与曲库异常。
- 在实际 Android 设备上调整屏幕比例、系统安全区、迷你播放器、导航、动画和状态环位置。
- 完成构建、核心单元测试、DTO/协议测试及真实服务人工验收后再进入发布准备。

## 权威规格入口

- [后端接口与实时协议](../specs/2026-10-23-android-backend-api-contract.md)
- [Web 行为继承合同](../specs/2026-10-23-android-web-behavior-contract.md)
- [客户端权威边界与架构](../specs/2026-10-23-android-client-boundary-and-architecture.md)

## 上游基线

本路线图撰写时核对的上游为 [MPD-Server/main commit `2ad84454ceb70817bd996c92b590cfa2d9e9b128`](https://github.com/qyxx2/MPD-Server/commit/2ad84454ceb70817bd996c92b590cfa2d9e9b128)（2026-10-18）。实现开始或上游推进后必须重新核对实际基线；此 SHA 是文档核对点，不是锁定上游分支。