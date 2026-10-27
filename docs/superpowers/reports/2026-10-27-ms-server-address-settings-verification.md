# MS 服务器地址设置入口验证报告

日期：2026-10-27
工作区：`/home/Gold/WorkSpace/MS_remote_for_Android`
分支：`task4`
范围：仅完成用户授权的 Task 4 Batch 1 前置任务；不实现 Task 4 播放器/Queue、不触发真实播放或 Queue 写操作。

## 实现结果

- 在现有设置页新增“MS 服务器”分组，提供地址输入、保存/应用、格式错误和当前配置提示；原有主题和状态环设置保留。
- 输入支持裸 IPv4、裸 IPv6、域名，以及完整 HTTP(S) origin；裸地址默认 HTTP，端口默认 18000。指定 IPv6 端口时使用 `[IPv6]:port`。标准化地址写入现有 Preferences DataStore 的 `server_origin`。
- Application 启动时读取并校验已保存地址，应用到唯一共享 `AppRuntime`；在启动恢复完成前，保存动作会等待，避免异步恢复覆盖刚提交的新值。
- 保存操作经由现有 Runtime 的 `changeOrigin` 同步切换 HTTP/WebSocket 所使用的 origin，没有创建新的客户端、WebSocket、Runtime、MutationCoordinator 或后端 API。
- 页面明确提示保存/应用并不表示服务器已连接；没有添加网络探测，也没有启动额外连接副作用。

## 自动化测试

- Parser RED：`./gradlew --offline --no-daemon testDebugUnitTest --tests 'io.github.qyxx2.mpdserver.remote.network.ServerOriginAddressTest'`，首次按预期因 `parseAddress` 尚不存在而失败。
- Parser GREEN：同一测试命令通过，覆盖 IPv4、IPv6、域名、默认端口、HTTP(S)、显式端口和非法输入。
- Preferences RED：定向 `UiPreferencesRepositoryTest` 首次因 `serverOrigin/setServerOrigin` 尚不存在而失败。
- Preferences GREEN：`./gradlew --offline --no-daemon testDebugUnitTest --tests 'io.github.qyxx2.mpdserver.remote.ui.preferences.UiPreferencesRepositoryTest'` 通过，覆盖缺省 origin 与 DataStore/Repository 重建后的持久化。
- 最终验证命令：`./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin` — **BUILD SUCCESSFUL**。结果为 30 suites / 130 tests / 0 failures / 0 errors / 0 skipped；Lint、Debug APK 构建及 AndroidTest Kotlin 编译均通过。
- `git diff --check` 通过。
- 日志保存在工作区忽略目录：`.superpowers/sdd/2026-10-27-ms-server-address-settings/parser-red-offline.log`、`parser-green.log`、`preferences-red.log`、`preferences-green.log`、`finalverify.log`、`device-ui-test.log`。

## 实机测试与未覆盖验收

定向 Compose UI instrumentation 测试在 SM-G986N / Android 13 上通过，1/1。它验证了设置页入口、非法输入不修改偏好/Runtime、合法 IPv4 地址持久化并应用到共享 Runtime。测试通过 `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.navigation.AppNavigationUiTest#serverOriginSettingsRejectsInvalidInputAndAppliesValidAddressToSharedRuntime` 运行。

该测试通过 Compose 测试 Activity 挂载 AppNavHost，不等同于人工打开生产 MainActivity 后的视觉验收。没有使用实际 MS 地址执行 HTTP/WebSocket 连通性验证。设置页会在保存后显示“尚未检测服务器连通性”，避免把配置成功误报为连接成功。

## 边界与文档

- 未修改 MPD-Server/Web 后端或新增 API。
- 未执行真实播放、seek、Queue 或其他服务器写操作。
- 已更新 Task 4 实施计划/合同矩阵、Task 2 网络状态合同与 Superpowers README，声明这项独立前置任务的范围和边界。
- 本报告生成时未提交、合并或推送分支；后续按用户明确要求，与 Task 4 Batch 2 实现及相关合同/验证文档一并进行本地提交，不推送远端。

## 复审

作者自审：输入校验、持久化、唯一 Runtime 所有权、启动恢复顺序和“未验证连通性”的 UI 文案均已检查。未使用独立 reviewer/编程代理；这是自审，强度低于独立审查。
