# MPD-Server Android 客户端文档索引

这里维护 Android 原生客户端合同。客户端是 MPD-Server 的遥控器与状态展示端，不是第二套播放服务器。

## 权威规格

以下规格均以核对时的 qyxx2/MPD-Server/main commit 2ad84454ceb70817bd996c92b590cfa2d9e9b128（2026-10-18）为源基线；总规格另记录了其 Android 仓库核对点。

1. [Android 客户端产品与工程总规格](specs/2026-10-24-android-client-product-and-engineering-spec.md)：Android 产品定位、平台范围、技术栈方向、四个一级入口、页面树、主题、迷你播放器与系统媒体控制。这是 Android 产品和工程的总入口，不重复定义接口细节。
2. [后端接口与实时协议规格](specs/2026-10-23-android-backend-api-contract.md)：当前路由、DTO、HTTP/WebSocket 协议、错误和幂等约定。
3. [Web 行为继承规格](specs/2026-10-23-android-web-behavior-contract.md)：实际播放判断、实时状态恢复、mutation、Queue occurrence、歌词/时钟和活动通知行为。
4. [客户端权威边界与架构规格](specs/2026-10-23-android-client-boundary-and-architecture.md)：MPD-Server 与 Android 的职责、禁止复制的服务端能力、共享状态架构方向及应用内状态环范围。

上述文档按职责共同构成 Android 客户端权威合同：
- 产品与工程总规格决定 Android 端的产品范围、顶层 UI 结构和技术栈方向。
- 后端接口合同决定客户端如何消费现有 server API 和实时协议。
- Web 行为继承合同决定需要保留的业务操作与状态恢复语义。
- 客户端边界与架构合同决定 server 与 Android 的责任边界及单一状态来源。

它们不取代 MPD-Server 本身的代码和测试；上游服务端是业务及状态唯一权威。若上游 main 前进，先比较实际代码、测试和规格差异，更新受影响合同，再开始相关客户端工作。阶段级 UI/交互规格可以细化实现参数，但不得推翻总规格或服务端合同。

## Task 0 环境、签名与本地交付合同

- [Task 0 环境、构建、签名与本地交付合同](specs/2026-10-24-task-0-environment-build-signing-ci.md)：冻结本地工具链、正式 applicationId、统一安装签名身份与远端构建的重新评估条件；当前不配置 CI。
- [Task 0 执行计划](plans/2026-10-24-task-0-environment-build-signing-ci.md)：记录机器验证、用户决策、真机人工验收和 Task 0 退出门。

## 主计划与实现路线

- [Android 客户端主计划](plans/2026-10-24-android-client-master-plan.md)：Task 0–8 的顺序、每个 Task 的合同矩阵、机器/人工验证、前置阻塞与退出门。后续工作以此作为阶段门控主入口。
- [Task 1 原生工程骨架合同](specs/2026-10-24-task-1-native-project-baseline.md)：冻结工程身份、工具链、统一签名、范围边界与退出门。
- [Task 1 执行计划](plans/2026-10-24-task-1-native-project-baseline.md)：本地原生工程实施步骤、TDD、验证和交付记录。
- [Task 2 网络协议、共享状态 Store 与操作协调器合同](specs/2026-10-24-task-2-network-state-coordinator.md)：冻结服务端 origin/HTTP/WS、严格 DTO、完整快照、单一 realtime owner、mutation 幂等/unknown 恢复边界与合同矩阵。
- [Task 2 分批实施计划](plans/2026-10-24-task-2-network-state-coordinator.md)：按固定服务端基线、HTTP/DTO、Realtime、Snapshot Store、单一 Runtime、Mutation Coordinator 和整体验收拆分 Batch；实现/机器测试已完成，但真实 mutation/readback 与 APK live-origin 仍为 BLOCKED。
- [Task 3 原生应用壳合同](specs/2026-10-25-task-3-native-app-shell.md)：冻结四入口、mini 显隐、主题、状态环/活动详情、runtime 所有权、Task 2 残余门槛与退出条件。
- [Task 3 分批实施计划](plans/2026-10-25-task-3-native-app-shell.md)：拆分为合同映射、主题、导航、App Shell、mini 投影、活动状态、播放器占位和集成验收共 8 个 Batch。
- [初步实现路线图](plans/2026-10-23-android-client-roadmap.md)：早期阶段概览，仅作背景参考；若与主计划的 Task 顺序或退出门冲突，以主计划为准。
- 每个 Task 开始时，另行细化该 Task 的权威合同、冻结边界、验收标准和 Batch 执行计划；精确 UI 尺寸、动画、主页模块数据规则和依赖版本在对应阶段冻结。

## 上游核对基线

- [qyxx2/MPD-Server](https://github.com/qyxx2/MPD-Server)
- [main commit 2ad8445](https://github.com/qyxx2/MPD-Server/commit/2ad84454ceb70817bd996c92b590cfa2d9e9b128)
- 核对范围：server/app/api 路由与 schemas、server/app/main.py 的幂等/错误入口，以及 Web services、stores、runtime、播放器/队列/布局组件和现行领域/UI 规格。
