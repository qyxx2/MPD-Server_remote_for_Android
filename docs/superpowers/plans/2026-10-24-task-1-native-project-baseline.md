# Task 1：原生工程骨架与本地构建基线 Implementation Plan

> **For agentic workers:** 使用 `superpowers:executing-plans` 逐步实施；每项检查按测试先行、观察失败、最小实现、重新验证执行。本计划仅覆盖 Task 1，不启动 Task 2。

**Goal:** 建立可重复构建、可测试、可安装并使用统一签名身份的最小原生 Android 应用。

**Architecture:** 单一 `app` 模块，Kotlin + Jetpack Compose / Material 3；首屏仅用于证明工程可启动。签名凭据保存在仓库外的本地用户配置中，所有可安装变体共享同一签名身份。本阶段只做本地构建，不配置 CI。

**Tech Stack:** JDK 21、Gradle Wrapper 8.14.6、AGP 8.10.1、Kotlin/Compose plugin 2.0.21、Compose BOM 2025.06.00、compileSdk/targetSdk 36、minSdk 26。

**Spec:** `docs/superpowers/specs/2026-10-24-task-1-native-project-baseline.md`；上位规格为产品与工程总规格、后端 API 合同、Web 行为合同、客户端边界与架构合同及 Task 0 环境/签名合同。

## Global Constraints

- 正式 `applicationId` 与 `namespace` 固定为 `io.github.qyxx2.mpdserver.remote`；显示名为 `MPD-Server Remote`。
- 所有可安装 variant 使用 `/home/Gold/.config/mpd-server-remote/installer-signing.jks` 对应的同一内部签名身份；密钥和密码不得入库、打印或写入 artifact。
- `minSdk = 26`；原生 Compose；不使用 WebView、不连接 MPD TCP、不新增业务数据库/本地播放引擎。
- 不配置 GitHub Actions/远端构建；只在指定 Ubuntu 工作区构建。
- 不将本机代理配置、AAPT2 绝对路径或其他主机专属配置写入仓库。
- 不开始 Task 2 的网络协议、状态 Store、WebSocket 或操作协调器实现。

## Review Focus

- 缺失签名属性或 keystore 时，构建必须明确失败，不可回退默认 Debug key。
- 重复构建、Debug/Release 变体不得改变包名或签名证书身份。
- ARM64 主机的 AAPT2 适配只能来自本机用户配置，不能硬编码到项目。
- APK 生成成功不等于目标真机安装/覆盖安装通过；人工验收须单独记录。
- 构建/测试/Lint 不得引入 Task 2 及后续业务能力或未经规格授权的权限。

---

### Task 1 Batch 1：工程骨架、依赖版本与启动占位页

**Files:** 根 Gradle 配置、`gradle/libs.versions.toml`、Wrapper、`app` 模块、Manifest、主题资源、`MainActivity`、最小单元测试。

**Interfaces:** 仅提供 Android 启动入口和工程验证页面；不暴露后续业务接口。

- [x] 创建验证 Gradle `BuildConfig` 应用身份的单元测试，并观察实现前因 `BuildConfig` 未生成而失败。
- [x] 添加标准 Gradle Wrapper、版本目录、单一 `app` 模块和原生 Compose 占位页。
- [x] 配置冻结的 `applicationId`、`namespace`、版本元数据、SDK 下限、应用名称和最小 Manifest。
- [x] 运行目标测试、`assembleDebug` 与 `lintDebug`；记录完整结果和 warning。
- [x] 检查生成 APK 的 package/version/minSdk/targetSdk 元数据。

### Task 1 Batch 2：仓库外统一签名与本地构建合同

**Files:** 仓库 `.gitignore`、Gradle signing configuration、Task 1 合同/计划证据。

**Interfaces:** 所有可安装 variants 读取同一个仓库外 keystore 与受限权限的本地签名属性。

- [x] 验证签名配置/keystore 缺失时构建明确失败；错误未暴露秘密且未回退默认 Debug key。
- [x] 在仓库外创建受限权限的 keystore 和本地签名属性；签名密码由本机生成并仅写入权限为 600 的本地文件，不回显。
- [x] 为 Debug/Release 配置同一 signingConfig；缺少配置时显式报错，禁止 fallback 到默认 Debug key。
- [x] 更新 `.gitignore`，覆盖签名/本机属性/构建产物；确认 `git status` 不暴露秘密。
- [x] 运行完整 clean build、unit tests、Lint；验证 `apksigner` 及证书 SHA-256，Debug/Release 证书摘要一致。
- [x] 用户确认真实设备已安装并启动 APK，且同签名覆盖安装无问题；ADB 不可用时采信用户的实际设备验证反馈。

### Task 1 Batch 3：整体验证、审查与同步

- [x] 对照 Task 1 合同逐项检查退出门及主计划，记录机器验证、人工验证和剩余阻塞。
- [x] 审查完整 diff，确认无 CI、无业务功能、无签名秘密或主机专属构建路径进入 Task 1 代码；规格中对 Task 0 主机环境的既有说明保留为文档事实。
- [x] 运行完整 clean 构建、单元测试、Lint、Debug/Release APK 生成与签名验证；读取完整结果。
- [x] 更新主计划 Task 1 状态及本计划验收记录；只提交 Task 1 范围。
- [x] 提交并推送 `feature/task-1-native-project-baseline`；不合并 main、不启动 Task 2。

## 当前实测结果

- 完整命令：`./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease` → `BUILD SUCCESSFUL`，退出码 `0`，103 actionable tasks（最终一轮 65 executed、37 from cache、1 up-to-date）。
- 单元测试：`AppIdentityTest`，1 test、0 failures、0 errors。
- Lint：0 errors、2 warnings，分别为 Compose BOM `2025.06.00` 与 Activity Compose `1.10.1` 有更新版本提示。Task 0 已记录新 BOM 在现有 AGP/SDK 组合下不兼容，因此保留经验证的版本。
- 构建警告：`libandroidx.graphics.path.so` 无法由当前工具链 strip，Gradle 将其原样打包；APK 正常生成。最终构建另有 Gradle 9.0 deprecation 提示，未阻塞当前 Wrapper 8.14.6。
- Debug 与 Release APK 均为 `io.github.qyxx2.mpdserver.remote`，versionCode `1`、versionName `0.1.0`、compile/target SDK `36`、min SDK `26`、应用标签 `MPD-Server Remote`。
- 两个 APK 均通过 `apksigner verify`；证书 SHA-256 相同：`36c589d3e947dee7f36204d1d19f4e96275159a6a7ff9cd058e5cdd448a2c89f`。签名由仓库外的内部 keystore 提供，不是自动 Debug key。
- 密钥文件和本地签名属性分别位于 `/home/Gold/.config/mpd-server-remote/installer-signing.jks` 与 `/home/Gold/.config/mpd-server-remote/signing.properties`，权限均为 `600`；秘密未写入仓库或日志。用户仍需完成加密离线备份。
- `git diff --check` 通过；ADB 当前没有连接设备，真机安装、启动、覆盖安装和数据保留验证尚未完成。

## 验收结果记录

- 最终验证命令：`./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug` → `BUILD SUCCESSFUL`，退出码 `0`，54 actionable tasks（26 executed、28 from cache）。单元测试任务通过；Lint 无 error。构建输出保留了 ARM64 主机 AAPT2 override 的实验性提示、`libandroidx.graphics.path.so` 无法 strip 的警告，以及 Gradle 9.0 deprecation 提示。
- 最终生成的 Debug APK 元数据：applicationId `io.github.qyxx2.mpdserver.remote`，versionCode `1`，versionName `0.1.0`，compileSdk/targetSdk `36`，标签 `MPD-Server Remote`；签名验证通过，证书 SHA-256 为 `36c589d3e947dee7f36204d1d19f4e96275159a6a7ff9cd058e5cdd448a2c89f`。此前 Debug/Release 证书一致性验证记录仍有效。
- `git diff --cached --check` 通过；Task 1 变更不包含 CI workflow、业务功能、签名秘密或主机专属 AAPT2/代理配置。
- 远端分支基线确认：`origin/docs/android-client-roadmap` 的提交 `8cfc123` 已是 `main` 的祖先；Task 0 文档与合同已随 `efa2a5a` 快进并推送到 `origin/main`，没有待合并的文档分支内容。
- 用户人工验收确认：Debug APK 安装并启动正常；正式 APK 覆盖安装成功。结合 Debug/Release 使用相同签名证书的机器验证，Task 1 的真机安装与覆盖安装退出条件通过。ADB 当前未连接不影响已完成的手动验证反馈。签名 keystore 与恢复口令的加密离线备份仍由用户按 Task 0 安全约定保管；这是持续的凭据保管责任，不再作为本 Task 的工程验收阻塞项。
- 验收结论：**Task 1 通过。** 随文档更新提交合并至 `main` 并推送远端后，补记最终集成提交 SHA。
