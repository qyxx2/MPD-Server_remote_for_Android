# Task 1：原生工程骨架与本地构建基线合同

> 状态：**Task 1 验收通过。** 工程、机器验证以及用户报告的真实设备安装/启动和同签名覆盖安装均已通过。签名密钥加密离线备份仍须按 Task 0 安全约定妥善完成，但不阻塞本 Task 的工程验收。本文细化主计划 Task 1；与上位规格冲突时以上位权威规格及 Task 0 冻结合同为准。

## 1. 目标与非目标

建立标准、可重复的 Kotlin + Jetpack Compose Android 工程，落实 Task 0 已冻结的 applicationId、namespace、工具链、统一安装签名和本地构建交付方式，并提供仅用于验证工程启动的原生占位界面。

本 Task 不实现业务 API、WebSocket、共享状态 Store、导航、播放器、曲库、业务数据库、WebView、MPD TCP 连接或本地音频引擎；不配置 GitHub Actions 或远端构建。只在 `/home/Gold/WorkSpace/MS_remote_for_Android` 工作区实施，不创建第二个 Git worktree。

## 2. 合同矩阵

| 需求 | 权威来源 | Task 1 冻结合同 | 机器验证 | 人工验证 |
|---|---|---|---|---|
| 工程和 UI | 产品总规格 §§3–4、13；架构合同 §4 | Kotlin + Compose 原生工程；仅有启动占位页 | clean assemble、单元测试、Lint | 真机安装启动、系统主题/状态栏可用 |
| 工具链 | Task 0 合同 §§4、7–8 | JDK 21、Wrapper 8.14.6、AGP 8.10.1、Kotlin/Compose plugin 2.0.21、Compose BOM 2025.06.00 | `./gradlew --version`、完整构建 | 不适用 |
| SDK | Task 0 合同 §4 | compileSdk 36、targetSdk 36、minSdk 26 | APK badging、Lint | 目标 Android 设备启动 |
| 应用身份 | Task 0 合同 §5.1 | applicationId/namespace `io.github.qyxx2.mpdserver.remote`；名称 `MPD-Server Remote`；版本初始为 `0.1.0` / versionCode `1` | APK badging、Manifest/Gradle 检查 | 系统应用信息显示正确身份 |
| 统一签名 | Task 0 合同 §5.2 | 所有可安装 variant 使用同一个用户控制的内部 keystore；禁止自动 Debug key；密钥和口令必须在仓库外 | `apksigner verify`、证书 SHA-256 对比、仓库秘密扫描 | 连续安装同包名/同证书 APK，验证可覆盖更新且保留数据 |
| 网络安全基线 | 产品总规格 §10；后端合同 | 允许 HTTP/WS 由用户配置的 origin 派生；不硬编码服务器地址；不添加明文流量全局豁免或额外权限 | Manifest/资源检查 | 占位页无需服务器即可启动 |
| 构建交付 | Task 0 合同 §6 | 仅本地 Wrapper 命令；不增加 CI workflow | clean build、test、lint、APK 产物检查 | 本地 APK 安装 |

## 3. 工程结构与实现边界

- 使用根 `settings.gradle.kts`、根 `build.gradle.kts`、`gradle/libs.versions.toml`、提交到仓库的 Gradle Wrapper 和单一 `app` 模块。
- 使用 `io.github.qyxx2.mpdserver.remote` 作为 namespace/applicationId 和 Kotlin 根包。
- `MainActivity` 仅承载 Compose Material 3 的基础占位页面，显示应用名称和“Task 1 原生工程验证”；不得伪造业务状态。
- 采用 AndroidX、Material 3、Compose Activity；仅配置完成基础启动、JUnit 单元测试和 Android Lint 所需依赖。
- Manifest 只声明启动 Activity 和必要的应用主题/标签；本 Task 不添加 INTERNET、通知、媒体服务等后续功能权限。
- 应用启动/主题配置不得依赖网络。不得提交本机代理、AAPT2 override 或其他主机专属绝对路径。

## 4. 统一签名与秘密管理

- keystore 建议路径：`/home/Gold/.config/mpd-server-remote/installer-signing.jks`；本地 Gradle 签名属性存放在同目录的 `signing.properties`，二者均在仓库外，权限限制为当前用户。
- 别名为 `mpd-server-remote-install`。签名属性仅包含 keystore 路径、alias、store password 和 key password；不写入版本控制、构建日志、测试报告或普通 artifact。
- 所有可安装 build variant 使用相同 signingConfig。为保持同包名 APK 可覆盖安装，Debug 和 Release 不得使用不同证书。Debug 可保留调试能力，但 APK 只供用户控制的内部侧载，不公开分发。
- Gradle 配置在签名文件缺失或不完整时必须明确失败，不能静默退回 Android 默认 Debug key。
- keystore 和密码不得打印到日志；仓库忽略规则覆盖签名文件、local properties、APK/AAB、构建输出及 IDE 本机文件。
- 用户仍需将 keystore 与恢复所需密码安全地加密离线备份；不得把明文密码备份到仓库或聊天。

## 5. 本地构建入口

- Wrapper 是唯一构建入口，禁止依赖 PATH 中的 Gradle 4.4.1。
- 主要验证命令：`./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug`。
- APK 位置：`app/build/outputs/apk/debug/app-debug.apk`。APK 元数据、签名验证和证书摘要需单独检查。
- 当前主机代理和 ARM64 AAPT2 适配属于用户级环境；不得把代理凭据或 AAPT2 绝对路径写入项目配置。

## 6. 退出门

- [x] 工程使用冻结的 Wrapper/插件/SDK 版本，完整 clean build 成功。
- [x] 基础单元测试存在并通过；`lintDebug` 无 error，剩余 2 个依赖升级 warning 已按 Task 0 合同记录。
- [x] Debug/Release APK 的包名、版本、min/target SDK 正确；签名有效且不是自动 Debug key。
- [x] Debug/Release APK 使用相同签名证书；无签名秘密或机器专属配置进入 Git。
- [x] 用户确认目标真机安装/启动正常；正式 APK 同签名覆盖安装成功。
- [x] 最终 diff 复审、提交和推送完成；主计划 Task 1 状态及证据已更新。

用户人工反馈可作为真机验收证据，并与机器签名一致性检查相互印证；ADB 未连接不推翻用户已完成的手动安装/覆盖安装结果。不要将签名密钥备份状态与 APK 工程/设备验收混为同一门槛。
