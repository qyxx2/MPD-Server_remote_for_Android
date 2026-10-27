# MS 服务器地址设置入口设计

## 意图

在 Android 设置页增加本地 MS 服务器地址入口，便于后续真实设备测试。用户可输入 IPv4、IPv6 或域名；保存后地址写入 Preferences DataStore，并更新现有应用级共享 `AppRuntime` 使用的 HTTP/WebSocket origin。应用重启后恢复已保存地址。

## 边界

- 仅更改 Android 客户端；不更改 MPD-Server/Web，不新增 API，不创建新的 OkHttp、WebSocket、Runtime 或 MutationCoordinator owner。
- 不自动探测/验证服务器在线，不发起播放或 Queue 写操作。
- 地址切换继续遵守既有 origin 世代规则：旧连接/迟到响应失效，旧 origin 未决 mutation 不迁移、不以新 key 重发。
- 此入口是为测试准备的最小连接设置，不扩展 Task 6 的完整服务器系统设置内容。

## 输入与验证

- 支持完整 origin，如 `http://192.168.1.20:18000`、`https://ms.example.com:18000`、`http://[2001:db8::20]:18000`。
- 为便于测试，也接受不带 scheme 的 host/address：`192.168.1.20:18000`、`ms.example.com:18000`、`[2001:db8::20]:18000`，以及纯 IPv4、域名或 IPv6 地址；省略 scheme 时使用 HTTP，省略端口时使用 18000。
- IPv6 可使用有/无方括号形式；需要显式指定 IPv6 端口时使用 `[IPv6]:port`。
- 只允许 HTTP/HTTPS。拒绝空值、凭据、query、fragment、路径后缀、非法端口或无法解析的 host；错误留在设置页，不更改保存值或 runtime。
- 保存时展示标准化 origin；不以“已连接”或“连接成功”误报网络连通性。

## 保存/恢复

- 复用现有 Preferences DataStore 新增 `server_origin` 键，缺省值沿用当前占位 origin `http://localhost:18000`。
- 保存前完成解析与校验；成功后持久化并调用既有 `AppRuntime.changeOrigin`，确保 HTTP、WS 共用同一个新 origin。
- Application 启动时异步恢复已保存 origin；保存动作等待恢复阶段完成，避免初始化读值覆盖用户刚提交的修改。

## 验收

- 单元测试覆盖 IPv4、IPv6、域名、默认端口、显式 HTTP/HTTPS 端口、非法输入/路径/凭据/query/fragment。
- DataStore 测试覆盖缺省值和跨 Repository/DataStore 重建后的地址持久化。
- Compose 测试验证设置入口可见、非法值不提交、合法地址保存并更新偏好。
- 运行完整 JVM 单测、Lint、Debug APK 构建及 AndroidTest Kotlin 编译；不把编译通过等同于真实服务器连通验收。

## 实现方式比较

1. 在现有设置页与 DataStore 上扩展：复用主题/状态环设置、共享 Runtime 与已装配依赖；没有新的状态所有者。**采用此方式。**
2. 创建独立服务器设置 destination/ViewModel/Repository：抽象和文件更多，不适合本次单字段前置任务。
3. 每次编辑即时连接或直接重建 network owner：容易产生未确认输入的连接切换，并违反单一 Runtime owner 约束。
