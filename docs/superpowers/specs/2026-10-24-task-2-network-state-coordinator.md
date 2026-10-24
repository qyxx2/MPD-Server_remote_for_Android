# Task 2：网络协议、共享状态 Store 与操作协调器合同

> 状态：**Task 2 实施前冻结合同**。本文只定义客户端网络/协议/状态/操作基础，不实现任何产品功能代码。与四份上位权威规格冲突时以上位规格和当前 `MPD-Server/main` 实际代码/测试为准。
>
> Android 仓库基线：`qyxx2/MPD-Server_remote_for_Android/main` commit `28ac7fc134d6248ae0c9720b0ebd1c426e27356e`（2026-10-24）。
>
> 服务端基线：`qyxx2/MPD-Server/main` commit `2ad84454ceb70817bd996c92b590cfa2d9e9b128`（本轮 `git ls-remote` 核对，2026-10-24）。Task 2 实施前若该 HEAD 前进，必须先按本合同 §3 做差异核验；不得静默沿用旧 DTO/端点。

## 1. 目标与范围

为后续 UI、播放器和系统媒体适配提供单一、可测试的客户端运行时基础：薄 HTTP repositories、严格 wire/DTO 解码、完整快照 Store、单一 WebSocket realtime controller，以及统一 mutation/operation coordinator 和活动/错误呈现模型。

Task 2 只建立可独立验证的网络和状态基础，不实现根导航、页面 UI、播放器 UI、曲库浏览、媒体会话、业务数据库或本地音频播放。目标是忠实消费现有 MPD-Server，不复制服务端业务规则。

## 2. 不可变边界

- MPD-Server 是唯一业务与状态权威；Android 不直连 MPD TCP，不建立第二套 Queue/AutoPlay/History/Collection/Playlist/Favorites/reconciliation 逻辑。
- 使用 Kotlin、现有 Gradle/Compose 工程和已冻结依赖基线；网络层沿用 OkHttp、Kotlin Serialization、Coroutines/StateFlow 的原生方向。若需要增加库或升级版本，先给出兼容性证据并更新合同，不以实现方便为由临时引入。
- 单一应用级运行时拥有一个 canonical snapshot Store、一个长期 realtime controller、一个 mutation coordinator。页面/ViewModel 不得各自创建 WebSocket、canonical playback state 或重试 owner。
- HTTP 和 WebSocket 都从同一个用户配置的 origin 派生：HTTP(S) `/api/...`、WS(S) `/api/realtime`。不得硬编码 NAS 地址或服务端口监听，不添加登录 API 假设。
- 不使用 Room/SQLite 或其他本地业务数据库；不将持久化快照当作启动后的权威状态。当前进程内 last-known 只用于 stale/unknown 展示。
- mutation owner 的生命周期长于页面；本 Task 不引入跨进程持久化 mutation journal，也不在进程重启后自动重放未确认写入。
- 不更改服务端、服务端 API、数据库、Web 前端或部署配置；不添加 CI/远端构建。所有实现与验证只在 `/home/Gold/WorkSpace/MS_remote_for_Android` 唯一工作区完成。

## 3. 实施前上游基线门

本轮规格核对的服务端 HEAD 与文档快照一致：`2ad84454ceb70817bd996c92b590cfa2d9e9b128`。开始 Task 2 第一个实现 Batch 前，重新读取实际 `MPD-Server/main` HEAD；如果变化，检查：

1. `server/app/api/realtime.py`、`schemas.py`、`realtime_schemas.py` 及相关 API 路由；
2. `server/app/main.py` 的统一错误与 Idempotency-Key 处理；
3. `web/src/services/api.ts`、`realtime.ts`、`wire.ts`、`web/src/types/api.ts`；
4. `web/src/stores/player.ts`、`web/src/stores/notices.ts`、`web/src/runtime/taskActions.ts`、`web/src/runtime/playerSession.ts`；
5. 对应服务端/Web 测试和四份 Android 权威规格。

只在证据表明端点、DTO、协议或行为有变化时更新受影响的 Android 合同。若发现服务端当前实现与既有合同矛盾，暂停相关实现并记录具体文件/测试证据；不由客户端猜测并创造第三种协议。

## 4. 合同矩阵

| 需求/不变量 | 权威来源 | 本 Task 冻结合同 | 机器验证 | 人工/实机验证 |
|---|---|---|---|---|
| Origin 与传输 | 产品总规格 §§10、12；后端合同 §§1–2 | 单一配置 origin 派生 HTTP/WS；连接世代变更后旧端点结果不得写回或转移 mutation | origin/path 构造、HTTP/WS scheme、切换世代的单测 | 实际部署地址连通及切换地址后重新取快照 |
| HTTP 错误与 body | 后端合同 §§2、5 | 区分业务错误 DTO、网络/超时结果未知、解码/协议错误、204 空响应和 202 activity 响应 | 正常/缺失字段/null/未知 enum/错误 body/204/202/非 JSON 响应测试 | 观察真实服务错误和资源响应，核对错误码及提示 |
| WebSocket 初始帧 | 后端合同 §3；Web 行为合同 §2 | 每条新连接第一条有效帧必须为 protocol v1 完整 snapshot，外层/内层 epoch、sequence 一致；无有效 snapshot 前不开放 fresh 写操作 | 首帧缺失/错误类型/版本不支持/epoch 或 sequence 不一致测试 | 真实服务连接后确认完整快照被接纳 |
| Invalidate 与权威重读 | 后端合同 §3；Web 行为合同 §2 | invalidate 是重读义务不是 patch；按 epoch/sequence 过滤，合并并发相同 GET；读取期间水位上升且快照未覆盖时再次读 | 重复/乱序 invalidate、并发 GET、读期间提高水位测试 | 播放或输出变化后确认 UI 状态来自后端完整快照 |
| Epoch/sequence/revisions | Web 行为合同 §§2–3；架构合同 §6 | 旧 epoch、旧 sequence、旧连接、旧 revision 或迟到响应不得覆盖当前 canonical snapshot；资源缓存不能跨 epoch 当权威 | 模型/Store 状态转换测试、旧连接迟到 GET/WS 测试 | 断线重连、服务重启后确认新快照取代旧状态 |
| Fresh/stale/unknown 与观察 | 后端合同 §3；Web 行为合同 §§3、5；产品总规格 §12 | 保留 last-known 时必须标 stale/unknown；HTTP 200 不代表播放/输出 observation fresh；不得用 `playback.state` 单独判定实际播放 | stale/unknown、字段缺失、observation 不匹配、controls eligibility 输入测试 | 真实服务检查初始播放、暂停、断线和恢复状态 |
| Mutation intent/幂等 | 后端合同 §§2、4；Web 行为合同 §4；架构合同 §6 | 每个用户意图一个 key；传输重试复用同 key/method/path/payload；pending 冲突操作受控；超时/断线/解码失败可能 unknown，不以新 key 自动重发 | key/payload 保持、重复点击、明确失败、timeout unknown、同 key 重试测试 | 对真实服务执行一项可安全验证的写操作并读取权威结果 |
| 写后同步与 204 | 后端合同 §§2、4；Web 行为合同 §4 | 204 不解析 JSON、不拼造状态；写确认后读取失败表示“已确认、同步失败”，不得改判写入失败 | 204、写成功后 GET 失败、业务错误 DTO 测试 | 对真实服务确认写回执与重读状态分开显示 |
| Origin 变更/进程重启 | 产品总规格 §12；Web 行为合同 §2、4 | 端点切换使旧连接世代失效，旧 mutation 不迁移、不用新 key 重发；进程重启先完整快照，不自动恢复/重放旧 mutation | 端点切换中迟到响应、重启恢复策略的单测 | 修改服务地址、断网恢复并确认新端点快照 |
| 活动与错误归属 | Web 行为合同 §9；产品总规格 §§5、12 | pending/syncing/unknown/failed/retired/success/info 等活动由共享 owner 管理；UI 后续只投影，不自行维护 retry owner | coordinator 状态转换、活动结束与重试同 intent 测试 | 检查错误/未知活动信息清楚且不会因导航消失 |
| 本地持久化边界 | 架构合同 §§2–3、6；产品总规格 §12 | 不持久化业务快照/业务实体/mutation journal；只在内存保留 stale last-known；不自动重放进程死亡前未确认操作 | 依赖/源代码边界检查、重启行为测试 | 杀进程重启后确认重新读取权威快照 |

## 5. 关键协议与状态规则

### 5.1 HTTP/DTO

- DTO 对必需字段、可空字段、enum、snake_case/camelCase 映射、时间/数值类型执行明确验证；不能把缺失字段默认为空列表、0、STOPPED 或 success。
- 统一错误外形按 `error.code/message/details` 解码。错误响应无法解码时保留 HTTP 状态和协议错误上下文，不泄漏凭据或原始敏感请求内容。
- 二进制封面不属于 Task 2 的 UI 资源实现；网络抽象不得把非 JSON 响应一律送入 JSON 解码。完整封面加载/缓存行为留给播放器/曲库 Task 冻结。
- 所有写 API 的幂等规则按当前后端合同处理，唯一已记录豁免为 `POST /api/library/collections`。本 Task 不实施业务操作菜单。

### 5.2 Snapshot Store 与 Realtime Controller

- canonical 状态是服务端 `FullStateSnapshot`；本地状态另行表达连接状态、snapshot freshness、错误和未解决操作，不合并为伪造的服务端字段。
- 新连接必须先接纳完整 snapshot；随后 invalidate 触发完整状态读取。不得从 `domains` 局部拼接业务状态。
- 以 `(epoch, sequence)` 及快照内的 revisions、library_sync generation、explicit_stop_generation 维护已接受水位。旧数据不得倒退状态；水位提高而覆盖不足则继续读取。
- 连接失败、超时、协议错误或重连期间可以展示最后已知数据，但必须标 stale/unknown，禁用依赖 fresh 状态/target 的操作。新连接快照之前不恢复 fresh。
- realtime controller 的 owner 在应用级运行时，不能随页面/ViewModel 重建；连接关闭、取消、重连退避和旧连接响应隔离必须可测试。

### 5.3 Mutation Coordinator

- Coordinator 接收明确的操作 intent，记录原始 method/path/payload/key、状态和恢复动作；UI 不直接实现传输重试。
- 同一 intent 的网络重试复用 key 与完全相同 payload；新用户意图才创建新 key。明确业务错误是 failed；传输或解析无法判断提交结果是 unknown。
- unknown 只允许重试原 intent/key 或重新读取权威状态；不自动生成新 key，不在端点变更后迁移 intent，不在进程重启后重放。
- 写请求成功但后续状态/资源读取失败时，保留成功回执并将同步错误独立表示；不得让读取失败覆盖写成功事实。
- 操作记录与活动状态由共享 coordinator 管理；导航、Composable 销毁和 ViewModel 重建不得丢失 owner。跨进程持久化日志不在本 Task 范围。

## 6. 建议实现模块边界

这是 Task 2 的最小职责边界；实际包路径以 Task 1 工程为准，第一批实现开始前需在 Batch 计划中映射到精确文件。

- `network`：origin/URL 解析、OkHttp HTTP/WebSocket 适配与连接生命周期；不包含业务规则。
- `protocol` / `dto`：wire frame、API DTO、严格解码和统一错误模型。
- `data` / repositories：薄 endpoint 调用、状态/资源读取和 mutation 请求构造；不实现服务端算法。
- `runtime`：单一 realtime controller、snapshot Store、水位/世代防护、共享操作 coordinator 与活动状态。
- `test`：协议 fixture、fake transport/clock/dispatcher（若需要）、状态转换和竞态测试。

不要为“架构整洁”提前拆多模块 Gradle 工程；Task 1 已冻结单一 `app` 模块。

## 7. Batch 拆分前置条件

在 Task 2 实现前必须确认：

1. 本合同矩阵与第 5 节不变量已冻结；
2. 实际 `MPD-Server/main` HEAD 已记录，若有差异已完成证据核对并更新合同；
3. 精确目录/类型映射完成，明确 public/internal 接口和测试 seam；
4. 每个 Batch 的输入、输出、依赖、负向测试、验收命令与提交边界明确；
5. 不需要新增服务端 API、数据库、CI 或依赖大版本升级；若发现必须新增，暂停并先重新评估合同。

## 8. 机器验证与人工验证

### 机器验证

- `./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug` 持续通过。
- DTO/wire：必需字段缺失、显式 null、未知 enum、协议版本不支持、错误 DTO、204 空 body、非 JSON body。
- Realtime：初始完整 snapshot、epoch/sequence mismatch、重复/乱序 invalidate、断线/重连、旧连接迟到帧/响应、并发 GET 合并、读期间水位提升。
- Store：旧 epoch/sequence/revision/generation 不回写，fresh/stale/unknown 转换正确，缺少有效 snapshot 时 controls eligibility 为 false。
- Mutation：Idempotency-Key 稳定、重复意图去重/阻止、明确失败与 unknown 区分、同 key 原 payload 重试、写成功后读失败、端点切换时不重放、进程重启不自动重放。
- `git diff --check`、源代码/依赖边界检查，确认无 Room/SQLite、Media3/ExoPlayer、本地业务引擎、服务端改动或 CI workflow。

### 人工/实机验证

- 使用用户现有的真实 MPD-Server 部署和配置 origin；检查初始完整快照、播放/暂停/输出 observation 的实际字段和 freshness。
- 在可恢复网络条件下断开再恢复，确认旧状态只作 stale 展示，新连接完整快照接管。
- 验证一项可控 mutation 的确认、重新读取及错误呈现；不得只以 `/api/health` 成功作为验收。
- 实机测试前须确认不会造成不可逆或扰动性较大的播放/曲库操作；不进行破坏性测试。

## 9. Task 2 退出门

- [ ] 所有合同矩阵条目均映射到实现、自动测试和可追溯证据。
- [ ] 核心 DTO/wire、snapshot 水位、重连、并发读、旧响应隔离、幂等与 unknown mutation 负向测试通过。
- [ ] 完整本地构建、单元测试、Lint 通过；警告有说明，不以增量编译替代完整验证。
- [ ] 真实服务基本连通和完整快照读取有记录，至少完成一轮断线/恢复观察。
- [ ] Diff 复审确认只涉及 Task 2，未引入业务 UI、业务数据库、客户端业务算法、CI 或上游改动。
- [ ] 更新主计划 Task 2 状态与本计划结果；只有机器与人工门都通过才可进入 Task 3。
