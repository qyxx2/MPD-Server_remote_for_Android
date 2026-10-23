# MPD-Server Android 客户端文档索引

这里维护 Android 原生客户端合同。客户端是 MPD-Server 的遥控器与状态展示端，不是第二套播放服务器。

## 权威规格

以下规格均以核对时的 `qyxx2/MPD-Server/main` commit `2ad84454ceb70817bd996c92b590cfa2d9e9b128`（2026-10-18）为源基线。

1. [后端接口与实时协议规格](specs/2026-10-23-android-backend-api-contract.md)：当前路由、DTO、HTTP/WebSocket 协议、错误和幂等约定。
2. [Web 行为继承规格](specs/2026-10-23-android-web-behavior-contract.md)：实际播放判断、实时状态恢复、mutation、Queue occurrence、歌词/时钟和活动通知行为。
3. [客户端权威边界与架构规格](specs/2026-10-23-android-client-boundary-and-architecture.md)：MPD-Server 与 Android 的职责、禁止复制的服务端能力、Compose 结构方向及应用内状态环范围。

以上是 Android 客户端实现的权威合同。它们不取代 MPD-Server 本身的代码和测试；上游服务端是业务及状态唯一权威。`MPD-Server/main` 前进后，先比较实际代码、测试和规格差异，更新这里的合同，再开始受影响的客户端工作。

## 实现路线图

- [初步实现路线图](plans/2026-10-23-android-client-roadmap.md)：阶段与依赖方向。它不是逐步可执行的 Batch 计划。
- 精确 UI/动效参数、详细依赖版本、逐批范围与验收门槛，将在对应阶段开始前另行细化。

## 上游核对基线

- [qyxx2/MPD-Server](https://github.com/qyxx2/MPD-Server)
- [main commit 2ad8445](https://github.com/qyxx2/MPD-Server/commit/2ad84454ceb70817bd996c92b590cfa2d9e9b128)
- 核对范围：`server/app/api` 路由与 schemas、`server/app/main.py` 的幂等/错误入口，以及 Web `services`、`stores`、`runtime`、播放器/队列/布局组件和现行领域/UI 规格。