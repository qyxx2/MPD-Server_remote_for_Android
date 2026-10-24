# Task 0：开发环境、统一签名与本地构建 Implementation Plan

> **For agentic workers:** 使用 superpowers:executing-plans 按步骤实施。本任务是文档/环境验证，不建立正式产品工程。Task 0 退出门已通过；Task 1 仍须落实正式包名与统一签名策略。

**Goal:** 在正式工程骨架创建之前，以实测证据冻结 Android 开发/构建工具链、正式应用身份、统一安装签名策略和本地构建交付方案。

**Architecture:** 使用指定 Ubuntu 主机上的工具链，在临时目录创建独立且可丢弃的 Compose 探针工程。产品 Gradle 工程和统一签名配置留给 Task 1；当前不配置 GitHub Actions workflow 或远端构建，远端仓库只同步代码分支。

**Tech Stack:** JDK 21、Gradle Wrapper 8.14.6、AGP 8.10.1、Kotlin/Compose plugin 2.0.21、Compose BOM 2025.06.00、Android SDK 36 / AGP 有效 Build Tools 35.0.0；主机 ARM64 AAPT2 override 需记录、minSdk 26（以临时工程实际构建结果为准）。

**Spec:** `docs/superpowers/specs/2026-10-24-task-0-environment-build-signing-ci.md` 与 `docs/superpowers/plans/2026-10-24-android-client-master-plan.md` Task 0；上位约束为产品与工程总规格、后端 API 合同、Web 行为合同和客户端边界/架构合同。

## Global Constraints

- 最低系统版本保持 Android 8.0 / API 26。
- 原生 Compose；不使用 WebView、不实现本地音频引擎、不直接连接 MPD TCP 端口。
- MPD-Server 是唯一业务/状态权威；不新增 Room/SQLite 业务数据库或复制 Queue/AutoPlay/History/MPD recovery。
- Task 0 冻结并验证方案；产品骨架与正式签名配置属于 Task 1。当前不配置 CI/远端构建；只有本地因 APK 体积/资源限制无法构建或耗时过长时，才在用户再次批准后评估。
- 秘密、口令、keystore 不得提交到 Git 或写入日志。
- 唯一项目工作区：`/home/Gold/WorkSpace/MS_remote_for_Android`；临时 Compose 工程放 `/tmp`，不创建第二份 Git worktree。

## Review Focus

- PATH 中的 Gradle 4.4.1 不能被误当作项目构建工具；正式构建必须使用 Wrapper。
- 在 HTTP 代理只通过环境变量提供时，Gradle/JVM 不会自动继承；必须验证依赖请求路径而不改全局代理。
- SDK Manager 的 SDK XML v4 兼容警告必须被记录，不能误报为没有 SDK 或完全不可用。
- 临时 Probe 的 Debug 签名/包名不得被误用作正式应用身份；Task 1 必须使用冻结的 applicationId 和统一的用户控制签名密钥。
- ADB 未发现设备不能否定用户通过手机手动安装/启动的人工结果；Task 1 必须验证正式 APK 的包名、统一签名及覆盖安装。

---

### Task 0A：核验远程工作区和上游基线

**Files:** 当前代码不变；证据记入 Task 0 合同与 `.superpowers/sdd/2026-10-24-task-0-environment-build-signing-ci/progress.md`。

- [x] 检查工作区、文档分支 HEAD、远程跟踪状态和初始 Git 状态。
- [x] 记录 OS、架构、JDK、系统 Gradle、SDK platforms/build-tools、代理和 ADB 设备可见性。
- [x] 从 GitHub 核对文档分支最新提交和 MPD-Server/main 基线。

**预期结果：** 指定工作区是唯一仓库工作副本；基线提交与文档权威记录一致；初始工作树干净。

### Task 0B：验证候选工具链

**Files:** 临时文件仅位于 `/tmp/mpd-android-task0-compose-probe`；不进入 Git。

- [x] 创建最小原生 Compose Activity、Manifest 和 Android Gradle 配置。
- [x] 使用 Gradle Wrapper 8.14.6，配置 AGP 8.10.1、Kotlin/Compose plugin 2.0.21、Compose BOM 2025.06.00、SDK 36、minSdk 26。
- [x] 运行 `./gradlew --no-daemon --max-workers=2 --stacktrace --console=plain :app:clean :app:assembleDebug :app:lintDebug`，退出码 0；`BUILD SUCCESSFUL in 2m 53s`。
- [x] 验证生成 10,057,658 bytes APK、package/minSdk/targetSdk 元数据以及 Debug APK v2 签名证书；SHA-256 与 Download 目录副本一致。

**预期结果：** 编译与 Lint 命令成功；APK 元数据和签名验证与候选设置吻合。若失败，先查根因，不能把失败工具链写成已冻结。

### Task 0C：冻结签名和本地交付方案

**Files:** `docs/superpowers/specs/2026-10-24-task-0-environment-build-signing-ci.md`、`docs/superpowers/README.md`。

- [x] 建立 Task 0 环境、构建、统一签名与本地交付合同，区分冻结项和 Task 1 验收项。
- [x] 检查本 Task 变更未加入密钥/密码/token/Release 凭据；仓库当前无 Actions secrets、variables 或 workflow。
- [x] 完成合同矩阵、环境事实、签名边界、本地交付方案和退出门文档；相对链接解析、关键合同字段检查和 diff 检查均通过。
- [x] 记录临时 Debug 签名和正式统一签名身份的区别；未创建/提交正式密钥。

**预期结果：** 合同明确正式包名、统一签名身份、本地构建方式与远端构建限制；不添加产品源文件、workflow 或密钥。

### Task 0D：人工确认和退出门

**Files:** Task 0 合同、主计划和 ledger。

- [x] 用户确认实际环境与本地构建工作流。
- [x] 用户确认仅内部/侧载、正式 applicationId、统一签名身份和用户所有/加密备份密钥策略。
- [x] 用户在目标 Android 设备安装并启动临时 APK，确认显示验证文本。
- [x] 以记录的完整构建、Lint、APK 签名/元数据结果为机器验证证据。
- [x] 更新 Task 0 合同、主计划和进度 ledger。
- [ ] 完成最终 staged diff 复审后提交，并推送到对应远端功能分支。

**预期结果：** 用户决策有记录，机器验证证据可追溯，真机验证完成；Task 0 退出门通过。正式密钥创建与正式 APK 验证仍是 Task 1 的前置工作，不代表 Task 1 已开始。
