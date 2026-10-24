# Task 0：开发环境、统一签名与本地构建合同

> 状态：**Task 0 退出门已通过。工具链机器验证通过；用户已确认本地构建、内部侧载、统一包名与统一签名策略；用户已在真机安装并成功显示验证文本。正式应用代码与密钥创建仍属于 Task 1 的工作。
> 工作基线：`qyxx2/MPD-Server_remote_for_Android` 文档分支 `docs/android-client-roadmap`，HEAD `8cfc123610b712315043177dbed67b2a9949fefb`（2026-10-24）。
> 本合同受 [Android 客户端主计划](../plans/2026-10-24-android-client-master-plan.md) Task 0 和四份权威规格约束。

## 1. 目标与非目标

Task 0 在建立产品工程骨架前，以可复现证据冻结开发/构建环境、工具版本、统一 APK 身份和本地交付流程。此 Task 不创建正式 Android 产品模块、Manifest、正式 Gradle 构建脚本、GitHub Actions 工作流或签名密钥。当前不配置远端 CI，也不在远端仓库运行构建；只有本地因 APK 体积/资源限制无法构建，或构建耗时过长时，才在用户重新批准后评估远端构建。用于探测的 Compose 工程只能存在于 /tmp 等临时目录，不得合入本仓库。

全局不变约束：原生 Kotlin + Compose；`minSdk = 26`；MPD-Server 是唯一业务与状态来源；不使用 WebView、不连接 MPD TCP、不引入本地播放引擎或业务数据库。Android 默认服务器端口 `18000`。任何密钥、口令和凭据都不得进入 Git。

## 2. 合同矩阵

| 需求/决策 | 权威来源 | 本轮方案/证据 | 验证方式 | 状态 |
|---|---|---|---|---|
| 原生工程与 API 下限 | 产品与工程总规格 §§4、13；架构合同 §§1–4 | Kotlin + Compose；`minSdk 26`；正式产品骨架留到 Task 1 | 临时 Compose 应用构建与 APK 元数据 | 已通过机器验证 |
| JDK / Gradle / AGP | 主计划 Task 0；产品规格 §13.2 | JDK 21、Gradle Wrapper 8.14.6、AGP 8.10.1 | 临时 Compose 工程完整构建通过 | 已通过机器验证 |
| Kotlin / Compose | 产品规格 §4、§13.2 | Kotlin Android / Compose plugin 2.0.21；Compose BOM 2025.06.00；`activity-compose` 1.10.1 | 源码编译通过；Lint 0 errors、2 个依赖版本提示 | 已通过；保留升级提示 |
| Android SDK | 产品规格 §4；主计划 Task 0 | `compileSdk 36`、`targetSdk 36`、`minSdk 26`；AGP 有效 Build Tools `35.0.0` | SDK 安装列表、Gradle extension 诊断、APK badging 和 Lint | 已通过机器验证 |
| 正式应用身份 / 发布范围 | 产品规格 §4.2；主计划 Task 0 | applicationId 与 namespace 固定为 io.github.qyxx2.mpdserver.remote；显示名 MPD-Server Remote；仅内部侧载，不做应用商店发行 | Task 1 在正式 APK 中核对包名/namespace | 已冻结 |
| APK 签名身份 | 产品规格 §4.2；用户于 2026-10-24 确认 | 所有机器、所有后续构建变体/位置生成的可安装 APK 必须使用同一个由用户控制的内部签名密钥；不使用 AGP 自动 Debug 密钥作为正式 APK 身份 | Task 1 用同一 keystore 签署所有 APK，并用 apksigner 验证证书摘要一致 | 策略已冻结；实际密钥由 Task 1 安全创建/接入 |
| 签名密钥所有权与存放 | 用户于 2026-10-24 确认 | 统一安装签名 keystore 由用户持有、加密离线备份；密钥/口令不进 Git。当前只在本地可信 Ubuntu 主机使用；若以后获批远端构建，只能安全临时注入可信构建任务 | Task 1 验证 keystore 位于仓库外、权限受限，且不同构建生成相同签名证书摘要 | 策略已冻结；本 Task 不创建密钥 |
| 构建执行地点 | 用户于 2026-10-24 的明确决定 | 当前所有构建、测试、Lint、APK 产出只在指定 Ubuntu 本地工作区执行；远端仓库只同步源代码分支，不配置 workflow、不承担构建 | Task 1 验证本地 Wrapper 全量 build/test/lint 与 APK 生成；需要远端构建时先重新批准 | 已冻结 |
| 网络、代理、缓存 | 主计划 Task 0 | 此机器代理由 `HTTP_PROXY`/`HTTPS_PROXY` 环境变量提供；Gradle/JVM 不自动继承该代理，需要在本地构建脚本中显式配置或通过受支持的网络配置提供 | 临时构建日志、依赖访问测试；避免把代理凭据写入仓库 | 已确认存在代理适配需求 |
| 安装与运行人工验收 | 用户于 2026-10-24 明确反馈 | 用户已在真实 Android 设备安装临时 Compose Probe，并成功显示验证文本 | 用户真实设备的安装/启动结果（人工确认） | 已通过 |

## 3. 远程开发环境盘点

盘点设备：Remote Desktop Commander 设备 `ubuntu`，Linux ARM64。

| 项目 | 实测值 |
|---|---|
| OS | Ubuntu 26.04.1 LTS，ARM64 (`aarch64`) |
| Kernel | Linux `4.19.113` |
| Java | OpenJDK `21.0.12.1` |
| `JAVA_HOME` | 当前 shell 未设置；Task 1 应在本地构建脚本中明确配置或由工具安装动作设置 |
| PATH 中的 `gradle` | Gradle 4.4.1，过旧；**禁止作为本项目构建入口** |
| 可用 Gradle 安装 | `/opt/gradle/gradle-8.10.2/bin/gradle`；用户级缓存含 Gradle Wrapper 8.11.1、8.14.6 和 9.5.0 分发包 |
| Android SDK | `/home/Gold/Android/Sdk` |
| Build Tools | 34.0.0、35.0.0、36.0.0、37.0.0 |
| SDK Platforms | Android 35、36、37.0 |
| SDK Manager | 19.0；运行时出现 SDK XML v4 高于工具支持 v3 的警告。当前已安装包可枚举，但正式 Task 1 前应确认/更新 command-line tools |
| `adb` | 存在；`adb devices -l` 当前为空，没有连接的 Android 设备 |
| GitHub CLI | `gh auth status` 确认已登录账号 `qyxx2`；不在文档记录/输出 token |
| Proxy | shell 环境提供本机 HTTP(S) 代理 `127.0.0.1:7897`；Gradle/JVM 需显式代理配置。不要修改全局代理设置或把含凭据的代理 URL 提交到仓库 |
| User-wide Gradle customization | `/home/Gold/.gradle/gradle.properties` sets `android.aapt2FromMavenOverride=/home/Gold/Android/Sdk/build-tools/37.0.0/aapt2`; `~/.gradle/init.d/arm64-only.gradle` forces native ABI filters to `arm64-v8a` | 这是本主机 ARM64 专属配置，不得复制进项目的通用 `gradle.properties` 或 CI；未来如用户重新批准远端构建，runner 应使用其自身兼容的 AAPT2；当前 Task 1 本地构建不得依赖此全局路径以外的硬编码配置 |
| 当前产品仓库 | 文档分支 HEAD `8cfc123610b712315043177dbed67b2a9949fefb`；当前工作分支 `feature/task-0-environment-build-signing-ci`；初始工作树干净 |
| 上游 MPD-Server | `qyxx2/MPD-Server/main` 实际最新 commit `2ad84454ceb70817bd996c92b590cfa2d9e9b128`，与权威规格记录相符 |

## 4. 候选工具链冻结

候选版本组合已通过临时工程完整构建、Lint、签名和元数据检查，建议按下表冻结；正式版本定义写入 Task 1 工程文件，而非在本 Task 建立产品 Gradle 工程。

| 组件 | 候选版本/设置 | 说明 |
|---|---|---|
| JDK | 21.0.12 系列；Task 1 本地使用 JDK 21 | 避免依赖机器上过旧的 PATH Gradle；JDK 21 也用于正式本地构建 |
| Gradle | Wrapper 8.14.6 | 构建必须从 `./gradlew` 进入，不使用系统 Gradle 4.4.1 |
| Android Gradle Plugin | 8.10.1 | 在临时工程实测后再冻结 |
| Kotlin Android | 2.0.21 | 与 Compose Compiler Gradle Plugin 版本一致 |
| Compose Compiler Gradle Plugin | 2.0.21 | 通过 Kotlin 2.x Compose 插件配置 |
| Compose BOM | 2025.06.00 | 管理 Compose 依赖版本 |
| Activity Compose | 1.10.1 | 原生 Compose Activity 接入 |
| `compileSdk` / `targetSdk` / `minSdk` | 36 / 36 / 26 | API 26 下限不可变；SDK 36 平台已安装 |
| Android Build Tools | 主机装有 34.0.0 / 35.0.0 / 36.0.0 / 37.0.0；AGP 8.10.1 实际使用 `35.0.0` | 主机的 AAPT2 通过用户级 override 使用 ARM64 `build-tools/37.0.0/aapt2`；SDK 中 Build Tools 36 的 `aapt` / `aapt2` 为 x86-64，元数据读取使用 ARM64 AAPT2 37.0.0 |
| 构建检查 | `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug` | 正式工程建立后在本地执行；Task 0 临时工程至少运行 `:app:assembleDebug :app:lintDebug` |

版本选择是可复现的 Task 0 候选，不代表未来永远不升级。Lint 对 Compose BOM 2025.06.00 与 Activity Compose 1.10.1 各报告 1 条“有较新稳定版本”的 warning，但 BOM 2026.09.00 在临时探测时引入 Runtime 1.12.1，要求 AGP 9.1.0+ 和 compileSdk 37，因此不能只升级 BOM。升级时必须协调升级 JDK、Gradle、AGP、Kotlin/Compose 插件、SDK 并重新完整验证本地工程。

## 5. 正式应用身份与统一签名合同（已冻结）

### 5.1 正式应用身份

- applicationId：io.github.qyxx2.mpdserver.remote
- Kotlin/Gradle namespace：io.github.qyxx2.mpdserver.remote
- 应用显示名：MPD-Server Remote
- 发布范围：仅内部使用与侧载，不规划应用商店发行。
- dev.mpdserver.task0probe 仅是临时探针的测试包名，不得复用为正式应用 ID。
- 此 ID 依据 GitHub 仓库所有者标识 qyxx2 和产品名称选择，不声称拥有相应互联网域名。正式 APK 一旦建立，不得为了构建方便更改包名；更换包名会被 Android 视为另一个应用，无法作为原安装的覆盖更新。

### 5.2 统一签名身份

用户要求所有位置构建的 APK 都可以直接覆盖安装而无需卸载。为满足 Android 的安装更新规则，正式应用的全部可安装 APK 必须同时满足：
1. 相同 applicationId；
2. 相同签名证书/签名身份；
3. 版本号按 Android 更新规则递增。

因此本项目不采用“AGP 默认 Debug 密钥签 Debug、另一把 Release 密钥签 Release”的双密钥策略。Task 1 应建立一个统一内部安装签名密钥并将同一 signingConfig 用于所有会交付给用户安装的 build variants；本地构建与未来获批的任何其他构建位置都必须使用这把密钥。正式密钥由用户控制并加密离线备份；别名建议 mpd-server-remote-install。Task 0 不创建密钥，也不记录密钥密码。

- 密钥文件必须放在 Git 工作区外；Ubuntu 主机的建议路径为 /home/Gold/.config/mpd-server-remote/installer-signing.jks，仅作为路径约定，尚未创建文件。
- 密钥文件权限应仅允许所有者读取；密码通过本地未提交配置或进程环境安全传入，不得回显到日志。
- 禁止将 keystore、密码、私钥、token 或包含凭据的配置提交到 Git、上传为普通 artifact 或放入聊天记录。
- 在当前无远端构建的阶段，密钥只用于可信本地构建。未来如重新批准远端构建，必须安全注入同一密钥，并严格限制为可信分支/手动发布任务；不得在不可信 PR 代码上暴露签名凭据。
- 统一签名便于所有安装包互相覆盖更新，但也意味着调试 APK 与正式 APK 共用同一签名身份。因此 APK 仅用于用户控制的内部侧载，不可将带调试能力的 APK 公开分发；任何签名密钥轮换都必须单独规划更新迁移。
- 本 Task 不生成密钥；Task 1 在生成第一个正式 APK 前建立/接入密钥、设置忽略规则，并验证所有 APK 的签名证书摘要完全一致。

## 6. 本地构建交付合同（当前不实施 CI）

- 所有构建、测试、Lint 和 APK 生成只在指定 Ubuntu 设备的本地工作区 /home/Gold/WorkSpace/MS_remote_for_Android 执行，统一使用 Gradle Wrapper，不使用系统 Gradle。
- GitHub 远端仓库只用于同步代码分支；当前不添加或运行 GitHub Actions workflow，不配置远端构建，不依赖远端 APK artifact。
- Task 1 应提供清晰且可复现的本地命令，至少覆盖干净构建、单元测试、Lint 与 Debug/内部 APK 产出；最终 APK 仍需由用户复制到手机并人工验证。
- 只有在本地因为 APK 体积或设备资源限制无法构建，或构建耗时过长时，才重新评估远端构建。远端构建/CI 需要用户再次明确批准；批准前不得添加 workflow、secret 或自动触发器。
- 若未来启用远端构建，输出 APK 必须使用冻结的 applicationId 和统一安装签名密钥，且本地/远端 APK 的签名证书摘要需逐一比对。构建位置变更不允许改变包名、签名或安装更新行为。

## 7. 机器验证记录

- [x] 环境/版本实测表已记录。
- [x] 临时 Compose 工程通过完整 `:app:clean :app:assembleDebug`（Gradle Wrapper 8.14.6；`BUILD SUCCESSFUL`）。
- [x] 临时工程 `lintDebug` 通过并阅读结果：0 errors、2 个 `GradleDependency` warnings（已在本合同解释）。
- [x] APK 存在（10,057,658 bytes）；`apksigner verify --verbose --print-certs` 成功，使用 Android Debug RSA-2048 证书与 v2 签名。
- [x] AAPT2 badging 已验证临时包 `dev.mpdserver.task0probe`、`compileSdk 36`、`minSdk 26`、`targetSdk 36`。
- [x] 本 Task 未创建/加入任何密钥或凭据；仓库变更为文档、索引和 `.gitignore`，未配置 Release secrets。
- [x] 检查当前仓库没有 workflow/Actions secrets/variables；这是当前无 CI 决策的预期状态，不向仓库添加 workflow 或远端构建。

## 8. 机器验证证据明细

- 运行命令：`JAVA_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897" ./gradlew --no-daemon --max-workers=2 --stacktrace --console=plain :app:clean :app:assembleDebug :app:lintDebug`（仅临时工程，日志 `/tmp/mpd-android-task0-build-final.log`）。
- 结果：Gradle Wrapper `8.14.6`，`BUILD SUCCESSFUL in 2m 53s`；47 actionable tasks；APK 已生成。临时工程代理属性放在 `/tmp` 的工程级 `gradle.properties`；Wrapper 首次下载同时使用 `JAVA_OPTS`，不更改任何全局配置。
- 有效 Android Build Tools 值：通过临时 Gradle 诊断 task 读到 `35.0.0`；`compileSdkVersion=android-36`。
- APK SHA-256：`f46e0327b85e10cec87108df36b4b687f5ca7396dd2d39b84e668a6178c4580b`。输出 APK 与 `/storage/emulated/0/Download/mpd-task0-compose-probe.apk` 的 SHA-256 一致。
- `apksigner`：`Verifies`；v2 signature scheme=true；signer `C=US, O=Android, CN=Android Debug`，RSA 2048；证书 SHA-256 `e20229b88ec5bca088f8ef82e85857ebc8cd8f99ca2af5b39f7c5b99b3930b0f`。这是临时 Probe 的 Debug 密钥，不是正式统一安装签名密钥。
- AAPT2 badging：`package=dev.mpdserver.task0probe`、`compileSdkVersion=36`、`minSdkVersion=26`、`targetSdkVersion=36`。这是测试包名，不是最终 `applicationId`。
- Lint：`0 errors, 2 warnings`，两项都是当前版本较旧提示（Compose BOM 2025.06.00 可见更新至 2026.09.00；Activity Compose 1.10.1 可见更新至 1.13.0）。已实测 BOM 2026.09.00 在现有 AGP/SDK 组合下不兼容，故当前保留经验证的版本，不用不兼容依赖消除提示。构建另显示 `androidx.graphics.path.so` debug symbol strip warning：构建工具将该库原样打包；本轮 APK 仍生成且签名通过，Task 1 需核对正式 APK 的 ABI 与产物一致性。
- 首次 Wrapper URL 预检曾因 JVM 未自动继承 shell 代理而失败；根因已定位为 `Wrapper.validateDistributionUrl` 的直接 HTTPS HEAD。最终验证通过明确的 Wrapper JVM 代理参数完成下载与构建。

## 9. 人工验证与退出门

用户已在 2026-10-24 明确确认以下决策：

- [x] 开发/构建/测试只在指定 Ubuntu 设备的本地工作区进行；远端仓库只同步代码分支。
- [x] 当前不配置 CI 或远端构建；只有本地因包体/资源限制无法构建或耗时过长时，才重新提交方案并等待用户批准。
- [x] 首期仅内部使用与侧载，不考虑应用商店发行。
- [x] 正式 applicationId / namespace 采用 io.github.qyxx2.mpdserver.remote，显示名为 MPD-Server Remote。
- [x] 所有位置及所有交付构建使用同一个签名身份，保证正式 APK 可以直接覆盖安装而不需卸载。
- [x] 统一签名密钥由用户持有并加密离线备份，不入库；实际密钥创建和构建脚本接入在 Task 1 完成，不在 Task 0 创建/读取。
- [x] 用户已在真实 Android 设备安装临时 Compose Probe，且成功显示验证文本。
- [x] 工具链实际构建、Lint、APK 签名和元数据检查均有记录；Lint 0 errors、2 个兼容性已解释的依赖升级提示；没有把探针包名或 Debug key 当成正式身份。

**Task 0 退出结论：已通过。** 本任务冻结的是正式包名、构建地点和签名政策；Task 1 仍须将这些政策落实为正式 Gradle 工程和用户控制的 keystore 配置，并验证正式 APK 的包名、签名和覆盖安装。Task 0 的通过不表示正式产品 APK、统一密钥或远端 CI 已经创建/验证；当前没有远端 CI 要求。
