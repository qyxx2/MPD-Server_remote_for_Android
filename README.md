# MPD-Server Remote

MPD-Server 的原生 Android 遥控客户端，使用 Kotlin + Jetpack Compose 构建，不使用 WebView。应用负责操作与展示，播放状态、队列和曲库等业务数据由 MPD-Server 统一管理。

## 获取与安装

- **最新正式版：** [GitHub Releases](https://github.com/qyxx2/MPD-Server_remote_for_Android/releases/latest)
- **当前版本：** 1.0.2
- **最低系统：** Android 8.0（API 26）
- **应用包名：** `io.github.qyxx2.mpdserver.remote`

从 Releases 下载 APK，可直接覆盖安装已有版本。正式 APK 使用项目统一签名，升级时无需卸载应用，也不会主动清除现有配置和数据。本项目目前通过 APK 分发，不属于 Google Play 版本。

## 使用前提

1. 部署并启动 [MPD-Server](https://github.com/qyxx2/MPD-Server)。
2. 确保 Android 设备能够访问服务器提供的 HTTP/WebSocket 服务。
3. 首次打开客户端后，在 **Settings** 中填写 MPD-Server 地址并保存。

客户端连接服务端后读取实时状态并发送控制请求；不会在 Android 客户端另行维护一套独立的播放队列或播放状态。

## 主要功能

- 播放控制与实时状态同步，包括播放/暂停、上一首/下一首和进度调整。
- Now Playing 页面、专辑封面、歌词展示及居中歌词视图。
- 队列查看与管理，支持调整顺序、立即播放、下一首播放、清空和保存为歌单。
- 曲库浏览、搜索、歌单与收藏管理。
- 最近更新、播放历史和推荐等首页内容。
- Android 媒体会话、系统通知控制，以及 MPD DAC 音量状态观察与硬件按键控制。

## 文档

- [Android 客户端文档索引](docs/superpowers/README.md)
- [产品与工程总规格](docs/superpowers/specs/2026-10-24-android-client-product-and-engineering-spec.md)
- [后端接口与实时协议规格](docs/superpowers/specs/2026-10-23-android-backend-api-contract.md)
- [Web 行为继承规格](docs/superpowers/specs/2026-10-23-android-web-behavior-contract.md)
- [客户端权威边界与架构规格](docs/superpowers/specs/2026-10-23-android-client-boundary-and-architecture.md)
- [Android 客户端主计划](docs/superpowers/plans/2026-10-24-android-client-master-plan.md)
- [版本发布记录](docs/releases/)

## 上游项目

- 服务端：[qyxx2/MPD-Server](https://github.com/qyxx2/MPD-Server)
- Android 客户端：[qyxx2/MPD-Server_remote_for_Android](https://github.com/qyxx2/MPD-Server_remote_for_Android)
