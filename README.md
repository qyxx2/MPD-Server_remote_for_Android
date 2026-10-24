# MPD-Server_remote_for_Android

MPD-Server 的原生 Android 遥控与状态展示客户端，使用 Kotlin + Jetpack Compose，不使用 WebView 套壳。

## 权威文档

- [Android 客户端文档索引](docs/superpowers/README.md)
- [Android 客户端产品与工程总规格](docs/superpowers/specs/2026-10-24-android-client-product-and-engineering-spec.md)
- [后端接口与实时协议规格](docs/superpowers/specs/2026-10-23-android-backend-api-contract.md)
- [Web 行为继承规格](docs/superpowers/specs/2026-10-23-android-web-behavior-contract.md)
- [客户端权威边界与架构规格](docs/superpowers/specs/2026-10-23-android-client-boundary-and-architecture.md)
- [Android 客户端主计划](docs/superpowers/plans/2026-10-24-android-client-master-plan.md)
- [初步实现路线图（背景参考）](docs/superpowers/plans/2026-10-23-android-client-roadmap.md)

**MPD-Server 是唯一业务状态来源。** Android 只负责通过现有 API 遥控、接收状态通知、读取权威快照和用原生 UI 展示状态；不另建业务数据库、播放核心或 Queue/AutoPlay/History/MPD 管理逻辑。

当前文档按 qyxx2/MPD-Server/main commit 2ad84454ceb70817bd996c92b590cfa2d9e9b128 核对。上游基线变化时，先核对服务端接口、DTO、Web 行为与测试，再更新客户端权威规格。