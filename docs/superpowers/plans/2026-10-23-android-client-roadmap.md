# MPD-Server 安卓原生客户端初步实现路线图

> **状态：阶段路线图，非逐批执行计划。** 本文概括实现顺序；Android 产品范围和 UI 结构以产品与工程总规格为准，接口与 Web 行为以同目录下相应权威合同为准。开始每个具体阶段前，仍需制定详细计划和验收条件。

## 目标与核心边界

使用 Kotlin + Jetpack Compose 开发独立原生 Android 客户端，不使用 WebView。MPD-Server 是唯一业务和状态权威。Android 尽量作为遥控与状态展示端，复用已有 HTTP API、WebSocket、DTO 和 Web 操作语义，不另建业务数据库，不复制 Queue、AutoPlay、History、MPD 绑定/恢复或服务端事务。

## 推荐实现阶段

### 0. 核对合同与工具链

- 先阅读 [Android 客户端产品与工程总规格](../specs/2026-10-24-android-client-product-and-engineering-spec.md)，再阅读 [Android 文档索引](../README.md) 中其余三份权威合同。
- 实施前重新检查目标 MPD-Server/main 的实际 HEAD、相关接口/DTO/Web 行为和测试的差异；必要时先修订对应合同。
- 按总规格确定工程范围：最低 Android 版本为 API 26，采用标准 Gradle Android 工程；具体工具链和依赖版本在骨架阶段选择当时兼容的稳定版本。
- 对照总规格核对本地调试服务器地址；客户端默认端口为 18000，实际 host 由用户配置。标准部署约定为 NAS:18000 → 容器:8000；上游 Compose 默认值可能仍是 8000，不能假定部署已经变更。
- 精确 UI 尺寸、间距、动画曲线、主页模块数据规则和阶段验收门槛在对应阶段前单独细化。

### 1. 原生工程与基础 UI

- 建立标准 Kotlin/Compose Gradle 工程、主题、四项根导航、迷你播放器/设置页例外、状态环和基本测试入口。
- 按总规格建立唯一共享状态 store、单一实时连接、共享操作协调器、薄 API repositories 与 ViewModel 之间的边界。
- 此阶段不创建本地业务数据库，也不接入第二个音频播放引擎。

### 2. API、DTO 与实时状态

- 对接 GET /api/state、WS /api/realtime 和页面所需资源 API。
- 实现严格 DTO 校验、完整初始快照、invalidate 后重新读取、断线重连和错误表达。
- 保留 epoch、sequence、revision、fresh/stale/unknown 和控制目标语义；旧状态不能覆盖新状态。
- 建立共享 mutation intent：遵守 Idempotency-Key；结果未知时保留原 key/payload，禁止自动换 key 重发。
- 通过模拟协议场景验证重连、乱序、超时和操作未知结果，再接入真实服务。

### 3. 播放核心与队列

- 实现全屏正在播放/队列界面、播放控制、进度、歌曲信息、封面与原生歌词。
- 使用服务端的 actual playback observation 和 target；请求发出或 HTTP 成功不等于实际播放已经确认。
- 队列交互按 occurrence 身份操作；保留 Play Now/Next/Queue、排序、删除、清除待播与保存队列等现有语义。
- seek、resume、拖动草稿和未知结果遵守行为合同，不在客户端重新编排播放。

### 4. 应用壳与四页导航

- 实现主页、搜索、音乐库、设置四个一级入口。
- 主页/搜索/音乐库常驻显示迷你播放器并放在底部导航上方；设置页不显示。
- 点击迷你播放器进入独立全屏正在播放页面，该页面提供“正在播放/队列”切换入口。
- 页面切换只改变展示区域，不重复建立 WebSocket、不丢失尚未解决的操作。

### 5. 搜索、曲库、歌单、收藏与设置

- 复用现有 Library/Search/Collection/Playlist/Favorites/System API，展示服务端资源和错误。
- 集合筛选、播放顺序、AutoPlay、收藏与歌单成员规则全部由服务器解释与保存。
- 安卓专属 UI 偏好使用本地轻量存储；不为本地显示偏好新增服务端接口。
- 主页先落实服务端已经可支撑的发现模块；任何推荐算法或接口需求必须先单独分析，不得用假数据替代。

### 6. 应用内全局状态环与系统媒体会话

- 状态环只在 MPD-Server 自身应用的页面中显示，不实现跨应用浮层、不申请悬浮窗权限。
- 由共享活动 store、连接状态和服务端 observation/freshness 驱动颜色与动画；状态环不是另一份状态来源。
- 设置可配置圆环尺寸、位置偏移和线宽，以适应不同手机的挖孔/状态栏布局。
- 实现 Android 系统 MediaSession/媒体通知，提供锁屏和通知栏的上一首、播放/暂停、下一首；不实现本地音频播放或耳机专用功能。
- 系统媒体会话复用共享状态与操作协调器，不额外建立状态源或 WebSocket。

### 7. 联调、回归与界面打磨

- 对照当前服务端合同与 Web 行为验证播放/暂停、切歌、seek、Queue、歌词、搜索/曲库、收藏/歌单和系统设置。
- 覆盖重连、旧 epoch/sequence、操作结果未知、current target 变化、重复歌曲 occurrence、歌词不可同步、输出与曲库异常。
- 在 392dp 参考宽度和其他手机竖屏上验证系统安全区、迷你播放器、导航、主题、动画和状态环位置。
- 完成构建、核心单元测试、DTO/协议测试、MediaSession 状态验证及真实 Android 设备人工验收后，再进入发布准备。

## 权威规格入口

- [Android 客户端产品与工程总规格](../specs/2026-10-24-android-client-product-and-engineering-spec.md)
- [后端接口与实时协议](../specs/2026-10-23-android-backend-api-contract.md)
- [Web 行为继承合同](../specs/2026-10-23-android-web-behavior-contract.md)
- [客户端权威边界与架构](../specs/2026-10-23-android-client-boundary-and-architecture.md)

## 上游基线

本路线图撰写时核对的上游为 [MPD-Server/main commit 2ad84454ceb70817bd996c92b590cfa2d9e9b128](https://github.com/qyxx2/MPD-Server/commit/2ad84454ceb70817bd996c92b590cfa2d9e9b128)（2026-10-18）。实现开始或上游推进后必须重新核对实际基线；此 SHA 是文档核对点，不是锁定上游分支。