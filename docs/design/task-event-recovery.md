# 任务、操作权与事件恢复协议（任务 05）

本文定义工程底座的有效协议，不作为实现进度表；具体落地与完成证据见对应任务及验证记录。用户确认准备失败可重试/放弃、最终验收环境故障有限重试后结束，并同意以最小状态模型检查规则。决定时的模型证据见 [验证记录](../validation/task-event-recovery-05.md)。本文只定义工程底座，不将 PoC 状态机或模型能力带入正式面试流程。

## 所有者、标识与时间

- Java 持久化身份、资源归属、演练业务状态、业务操作、活动名额、页面控制权及浏览器事件；Go 持久化工作区版本/栅栏/冻结/文件回执、执行登记/结果/有界日志及清理状态。Go 不访问业务数据库。
- `exerciseId`、`workspaceInstanceId`、`taskId`、`operationId` 均为服务端生成的不可复用随机标识。准备重试使用新的工作区实例；不得以删除再创建同名目录接收旧调用。题目版本、包摘要、镜像 digest、受控目标及启动修订固定于任务。
- `revision`、`gateEpoch`、`stateVersion`、`sequence` 是不同计数器；JSON 使用十进制字符串，避免浏览器整数精度丢失。Go 的栅栏、回执不因重启归零。浏览器时间不作为授权依据。
- Java/Go 首期必须处于同一 Linux VM/宿主时钟域，使用该系统 UTC 时钟判定截止时间；日常开发和真实集成测试 JVM 同样遵循此边界。用户已确认 [ADR 0002](../adr/0002-java-go-shared-linux-clock.md)：同一物理电脑上的 Windows JVM 与 Docker Desktop Linux VM 不视为同一时钟域，不再作为截止时间敏感业务的支持基线。正式运行/集成测试入口已迁移至同一 Docker Linux daemon。用户通过 [ADR 0003](../adr/0003-accept-shared-system-clock-risk.md) 确认首期信任共享系统时间，不建设专门的时钟异常检测、准入锁定/人工恢复，也不为此新增单调计时授权改造；接受时间调整可能使实际截止提前或延后的风险，不保证偏差最多 1 分钟。运行与重启恢复均按持久化截止时间和当前系统时间判断，不重写既有授权期限、不增加宽限；正常 RPC 超时及栅栏机制保留。跨宿主时钟/共享存储不是本协议支持的部署方式。截止判断为 `now >= notAfter` 时拒绝。

## 业务状态与活动名额

### 工程底座演练

| 状态 | 允许的迁移与含义 |
|---|---|
| `PREPARING` | 已占活动名额，异步准备工作区；成功 → `READY`，确定失败 → `PREPARATION_FAILED`；未知结果先核对，不能同时重试 |
| `PREPARATION_FAILED` | 占名额；重试 → `PREPARING`（新 attempt/实例）；放弃 → `ENDED`，`endReason=PREPARATION_ABANDONED`，没有最终代码验收结果 |
| `READY` | 仅浏览/申请操作权；开始 → `ACTIVE`，同事务记录 `startedAt`、不可延长的 `deadlineAt`；可主动放弃结束 |
| `ACTIVE` | 保存、启动测试、停止；结束或到期 → `CLOSING` |
| `CLOSING` | 禁止新的作答写入/启动；完成栅栏、核对旧写入、发出停止意图并冻结（运行测试只持有独立副本，不阻塞文件冻结）；得到冻结回执后 → `FINAL_TESTING`，已有有效结果则直接 → `ENDED` |
| `FINAL_TESTING` | 只读；复用或运行冻结版本基础验收；结果或有限预算耗尽 → `ENDED` |
| `ENDED` | 不占活动名额；只读，报告固定 `UNAVAILABLE_NOT_IMPLEMENTED`；允许删除，不允许解冻/恢复作答 |

`cleanupState=PENDING/RUNNING/SUCCEEDED/FAILED` 与业务状态正交。删除先在 Java 事务内撤销资源访问、标记 `DELETING`，再异步清理，完成为 `DELETED`；删除墓碑继续拒绝旧实例的请求。清理失败不把 `ENDED` 改回活动状态。

任务 16 已确认 [保留与删除政策](workspace-execution.md#保留与删除)：已结束演练默认自结束起保留 30 天，到期与手动删除走同一撤销/清理协议；展示到期日期，配置变化不追溯缩短已有承诺。每用户最多 20 场未删除的已结束演练，达到上限拒绝新建，不自动提前淘汰；准入与并发创建/删除须协调，最多一场未结束演练的唯一约束仍保留。删除受理撤销访问后释放历史数量名额，但残留资源继续按原归属计量。

清理失败按 1 分钟、5 分钟、30 分钟，之后每小时重试；手动重试与 worker 幂等协调，不并行重复清理。持续失败超过 24 小时提示人工处理，后台仍可重试；重启不得丢失持续失败/重试进度并掩盖超时。本人最小删除进度在完成后保留 7 天，仅含演练标识、删除时间及状态；未完成进度不按该期限隐藏。进度期限不决定内部防重放记录的回收。

- PostgreSQL 对每用户最多一条非结束演练建立唯一约束，创建、释放名额与业务状态同事务。并发不同键创建一成功、一返回 409 `ACTIVE_EXERCISE_EXISTS`（仅返回本人现有标识）；同键重试返回原操作。不能只依靠按钮或 JVM 锁。
- 每演练最多一个未确认静默的执行任务：Java 事务预留槽位后派发，Go 也按演练检查独占槽位。未知执行或未确认进程退出保持槽位；不同启动请求返回 409 `TEST_SLOT_BUSY`，不隐式排队。最终验收复用同一槽位，不能与手动测试并行。
- 准备失败重试前封存旧 attempt，Go 持久化取消/删除墓碑后拒绝迟到准备 RPC；旧资源清理独立重试。放弃先永久撤销旧 attempt 的业务可见性，旧准备结果即使迟到也不能改回 READY；Go 恢复后按墓碑清理。放弃可释放用户活动名额，但未清理资源仍按原实例归属计量，未知执行继续占用隔离名额，不能用新演练绕过已有执行并发与隔离约束。任务 16 不新增固定全局磁盘预算或水位策略，不意味着残留资源可以不记账或磁盘不足可以伪报成功。
- 数据库/Go 不可用时不能假装完成栅栏或冻结，`CLOSING` 显示 `RECONCILIATION_REQUIRED` 并告警；该情况不是“最终验收环境失败”。恢复冻结能力前不声称有最终冻结版本。持续基础设施失联的运维处置不以伪报成功解决。

### 测试任务

`ACCEPTED → SNAPSHOTTING → STARTING → RUNNING`；任一非终态可进入 `STOPPING`。终态为 `PASSED / FAILED / TIMED_OUT / STOPPED / ENVIRONMENT_ERROR / INTERRUPTED`。

- `FAILED` 只表达编译/权威测试失败；`TIMED_OUT` 为平台保护性超时，不自动推断算法不合格；`INTERRUPTED` 为无法恢复可靠结果，不伪造退出码。`exitCode` 在未启动、未确认退出或无法读取时为 null。
- 结构化结果至少包含任务、工作区实例、修订、目标、题目/镜像身份、状态、原因、开始/结束时间（可空）、耗时（可空）、退出码（可空）、结果证据及清理状态。日志文本不决定结果。
- Go 在每任务持久化串行协调区排序“自然结果发布”与“停止意图”。自然结果先发布则停止返回原结果；停止意图先持久化则进入 STOPPING，确认容器及进程退出后发布 STOPPED，不再改为 PASSED。停止后无法确认退出/证据损坏可为 INTERRUPTED，并隔离执行槽位。RPC cancellation 不等于停止。
- 超时意图也经同一协调区；先落盘的停止/超时意图决定 STOPPED/TIMED_OUT，已经发布的终态不可覆盖。Java 仅接收同 taskId、executionInstanceId 的单调结果版本，终态比较并交换一次；重复/迟到事件不反转终态。
- 用户停止仅针对手动测试；FINAL_TESTING 的任务由系统有限预算管理。页面接管、断线、退出登录及重置密码都不停止任务。

### 最终验收有限预算

用户确认的产品路径：环境故障有限重试后结束，保留冻结代码，显示“最终验收结果不可用”，释放活动演练名额，不记为代码失败。

- 冻结回执固定 `finalRevision`。只有相同工作区实例、修订、题目/镜像身份及目标的有效 PASSED/FAILED/TIMED_OUT 结果可复用；旧版本通过不得复用。
- 技术初值：总计最多 3 次尝试（初次 + 2 次环境错误/中断重试），重试间隔 5 秒、30 秒；从进入 FINAL_TESTING 起总墙钟预算 10 分钟。每次还受题目单次执行超时限制。值集中配置并绑定本次演练，重启不能清零次数、重设预算。
- 每次派发前持久化 attempt 和任务 ID；响应未知先核对，不计为可自动重试的确定失败。前次执行未静默时不启动下次，等待也消耗总预算。FAILED/TIMED_OUT 正常结束，不为追求通过自动重跑。
- 次数/总预算用尽即以 `finalVerification=UNAVAILABLE` 结束，保留冻结版本和各次证据；已有确认结果则记录该结果。冻结后的总预算涵盖停止旧任务或等待执行槽位的时间。
- 预算到期先持久化禁止继续派发/接受后续业务结果的决定，并发出幂等停止；不必等清理成功才释放用户活动名额。执行未知仍占用隔离的宿主容量/旧演练槽位，后台继续核对与回收。迟到结果可附为恢复证据，不能静默修改已经显示的最终验收结论。

## 页面操作权与可恢复栅栏

### 页面凭证

- 不使用自动续租/自动抢占的页面租约。操作凭证是至少 256 位随机秘密，绑定演练、控制代次及签发用户，服务端只存摘要，页面仅内存持有。它不替代登录、CSRF 或资源归属。
- 无持有者时可申请；已有持有者则返回 409 `CONTROL_HELD`。刷新失去凭证后重新申请，不因同 Cookie/自称相同 pageId 而重获；页面展示只读和显式接管入口。持有者可显式释放。关页面不依赖 unload 自动释放，不自动停止后台测试。
- 页面 `controlGeneration` 与内部 `gateEpoch` 分开：接管/释放推进控制代次；重置协调仅推进内部栅栏，不把页面凭证秘密当作 Go 授权。Java 每次将当前有效控制代次映射到已激活 gateEpoch。
- 凭证无独立定时租期：直到释放、接管或演练结束失效，但每次请求仍检查登录会话期限。凭证丢失/签发响应丢失时显式接管换新，不从存储恢复秘密，也不复用旧代次。
- 请求携带 `X-Operation-Credential`，不放 URL、Cookie、本地持久存储或日志。签发仅一次返回秘密；幂等重放只返回 `credentialDelivered=false` 和状态。数据库认证版本变化不自动改变页面持有者，但旧会话不能使用其凭证；同一页面重新登录后仍需复核归属/控制代次。

### 写入、接管、释放、结束的排序

Java 持久化每演练的串行协调记录，不持数据库事务等待网络。协调记录含 `transitionId`、目标状态、预期代次、阶段及未决操作；按用户 → 演练 → 操作的固定顺序锁定，短事务中校验和预留。Java 重启/多个工作线程都从该记录恢复，不凭本地锁宣布接管成功。

```text
Java 短事务：校验身份/控制权，记录关闭准入意图与 transitionId
  → Go CloseGate(expectedEpoch, transitionId)
      与文件发布共用工作区串行协调区
      持久化 epoch+1、CLOSED、transitionId，再返回回执
  → Java 核对旧代次所有未决文件请求
      有回执：记录原提交
      无回执且 CLOSED 已确认：可判未提交；迟到 RPC 被旧代次拒绝
  → 接管/释放：Java 记录新持有者或无持有者、待激活代次
      → Go OpenGate(closedEpoch, transitionId)，持久化 epoch+1、OPEN
      → Java 确认激活后才返回成功/一次性新凭证
  → 结束：不再 Open，Go Freeze 固定最终版本
```

- Close/Open 带精确预期代次及 transitionId；重复相同步骤返回原回执，陈旧/跳跃/不同 transition 拒绝。不允许“设置任意更大 epoch”或旧 Open 重开新栅栏。每演练一次只驱动一个 transition，完成/恢复后才受理下一个。
- Go 把 gateEpoch、门状态及转换回执持久化到任务 04 的发布记录；关闭/打开栅栏不推进内容修订。冻结后任何 Open 拒绝。Go 重启未恢复权威记录前拒绝写入，损坏不退回旧代次。
- Apply 的可信元数据包含 `operationId/requestId`、workspaceInstanceId、gateEpoch、基线、载荷指纹、`notAfter`。Go 在原子发布协调区核对 gateEpoch、OPEN 和截止时间；在准备新树后、发布前再次检查截止时间。notAfter 不超过当次授权检查的会话闲置/绝对期限和演练期限，后续续期不能延长已派发请求。
- 文件写入先发布，则接管保留其结果；CloseGate 先发布，则旧代次不能再发布。Go 先查已提交回执可返回原结果，这不是新写入；Java 返回该回执前仍校验当前身份/归属。
- 查询 NotFound 不证明 RPC 已取消。只有拿到覆盖旧代次的持久化关闭回执后，无回执才允许收敛为未提交；无法确认栅栏则保持 `PENDING_RECONCILIATION`，不换键重发、不宣布接管成功。
- 到期的 Go 提交检查直接拒绝超时发布，不依赖 Java 定时器是否及时运行。Java 恢复/任何业务请求也检查 deadline，关闭新作答准入，推进 CLOSING。已有成功文件提交不回滚；冻结不提交本地草稿。
- 手动启动测试带作答 gateEpoch 和 notAfter；Go 在登记执行/稳定副本准入时与栅栏排序，要求 OPEN、未冻结、作答期限未到。已经登记的测试不因后续接管被停止，执行槽位和取消墓碑防止迟到 Start 重复执行。

### 系统最终验收的独立准入

最终验收不是继续作答。内部 StartTest 明确区分 `purpose=MANUAL` 与 `purpose=FINAL_VERIFICATION`；浏览器测试路由只能映射 MANUAL，不接受客户端指定 purpose、冻结回执或系统预算。

- Java 只在 FINAL_TESTING 中、已有确认冻结回执、未耗尽次数/总预算且执行槽位可用时，事务内分配稳定 `finalizationId`、`attemptId`、taskId/executionInstanceId 和派发记录；同一次尝试不得换任务 ID 重发。派发授权绑定工作区实例、冻结回执 ID、冻结 gateEpoch、finalRevision、题目/镜像/目标及 `finalizationDeadlineAt`，纳入幂等指纹。
- Go 的 FINAL_VERIFICATION 分支要求该实例仍 FROZEN/CLOSED，冻结回执/代次/修订匹配，尝试标识完整，且 `now < notAfter <= finalizationDeadlineAt`；此 notAfter 来源是持久化收尾预算，不是已到期的作答期限或用户会话期限。授权仅来自受信任 Java。复制冻结树不要求 OPEN，不执行 OpenGate，也不赋予任何写入权限。
- Go 在原子登记/冻结副本准入时检查该系统授权，并在实际创建/启动容器前再次检查 notAfter 与取消墓碑；排队或重启不能延长期限。与手动任务共用每演练执行槽位及停止/去重协议。已经登记的任务查询走 QueryTask；过期重发不得创建新执行。
- 次数或收尾预算用尽、演练删除时，Java 停止该 finalization 的新派发，按已持久化 taskId 发出停止/取消墓碑；Go 禁止迟到启动。Go 失联时，旧授权最迟在既定 notAfter 失效，未知执行继续隔离；业务结束不回收身份/版本证据，也不假装进程已退出。
- 因此到期冻结后仍可执行系统验收；旧页面迟到 MANUAL 请求始终被关闭栅栏/作答截止拒绝，不能改用系统分支。

### 密码重置、改密与退出

为满足任务 02“业务先提交或撤销先提交”的保证，撤销不能仅提交身份事务后异步通知 Go：

1. Java 在用户协调记录中持久化撤销意图，关闭该用户新的副作用准入；登录签发、控制权转换和创建演练也与之串行。读取在身份撤销提交前仍按现有会话有效性校验。
2. 排空已有数据库短事务；对其可能存在跨端写入的工作区完成 CloseGate 并核对回执。已有控制权 transition 先恢复到可关闭状态；迟到派发即使 JVM 已重启也不能绕过栅栏。
3. 按任务 02 在一个事务内更新密码/认证版本/会话撤销及操作结果。此提交是身份撤销点；此前已提交文件保留，此后旧会话不能产生新文件效果。
4. 按同一 transition 协议为仍可作答工作区恢复新栅栏代次（保留页面持有者语义，不发给旧身份新权限）；新请求必须重新查身份。不能重新打开已冻结工作区。清除撤销意图，通知连接关闭。

Go 暂不可用时保持可查询的撤销操作未决，禁止新副作用，并立即暂停相关 SSE 私有数据发送；不得返回“重置成功”而留下迟到写入窗口。临时密码不可写入未决载荷：先完成栅栏准备，再生成临时密码、哈希及提交，秘密仅在成功响应展示一次；提交后响应丢失遵循任务 02 的已完成无凭证回执。自行改密只能持久化新密码的安全哈希及请求 HMAC，不保存明文。退出只撤销当前会话；必要的工作区栅栏仍按同样排序，其他会话可重新准入。登录撤销不停止已登记测试。

## REST 与幂等边界

成功 JSON 统一为 `{code:"OK", message:"...", data:..., requestId:"..."}`；错误为同形 `{code:"符号错误", message:"安全可展示描述", data:null, requestId:"..."}`，可加不含秘密的 `details`。HTTP 状态权威，不用 HTTP 200 包装业务失败；204 无 body。SSE/gRPC 不使用此包装。异步受理返回 202 和 operationId/查询地址；这不是完成。

| REST（`/api` 下） | 成功及关键冲突 |
|---|---|
| `POST /exercises` `{problemVersionId}` | 202 准备操作；409 ACTIVE_EXERCISE_EXISTS |
| `GET /exercises/current`、`GET /exercises/{id}` | 当前或指定演练快照，包含状态、控制权状态（无秘密）、截止时间、已确认版本、任务、冻结/清理状态 |
| `POST /exercises/{id}/preparation-retry`、`/abandon` | 202；只允许失败准备，abandon 也允许 READY；旧 attempt 封存 |
| `POST /exercises/{id}/start` | 200 ACTIVE 与原 deadline；不同键重复开始 409 INVALID_STATE，不重置时钟 |
| `POST /exercises/{id}/control/acquire`、`/takeover`、`/release` | 200 完成或 202 转换中；409 CONTROL_HELD/CONTROL_TRANSITION_PENDING；release 要求当前凭证 |
| `POST /exercises/{id}/file-batches` | 已确认回执 200，未知 202；409 REVISION_CONFLICT/STALE_CONTROL，423 WORKSPACE_FROZEN |
| `POST /exercises/{id}/tests` `{expectedRevision,target}` | 202、taskId；409 TEST_SLOT_BUSY/REVISION_CONFLICT；目标只接受题目允许值 |
| `GET /exercises/{id}/tasks/{taskId}` | 200 快照/游标；查询不重启任务 |
| `POST /exercises/{id}/tasks/{taskId}/stop` | 202 STOPPING；已终态 200 原结果 |
| `POST /exercises/{id}/end` | 202 CLOSING；已收尾返回原状态，不重新冻结/验收 |
| `DELETE /exercises/{id}` | 202，已结束才允许；之后私有资源 404，另提供本人最小删除进度 |
| `GET /operations/{operationId}` | 200 PENDING/PENDING_RECONCILIATION/SUCCEEDED/REJECTED，回执引用；每次校验操作所有权 |
| `GET /exercises/{id}/tasks/{taskId}/events?after={cursor}` | SSE，有效 Cookie，无操作凭证；游标不是授权 |

- GET 缺少资源/跨用户统一 404，避免泄露；无效会话 401，CSRF/受限会话 403，非法载荷 400，非法状态/幂等冲突 409，容量 429，权威依赖暂不可确认 503（未受理）或 202（已有持久化意图）。到期写入 409 EXERCISE_EXPIRED。身份规则先于幂等结果返回。
- 所有副作用路由要求 `Idempotency-Key`（不含秘密，限制长度）；数据库 scope 为 `(执行用户, 操作种类, 目标实例, key)`，创建演练目标为该用户。请求规范化指纹覆盖全部业务输入与基线，不把登录 Cookie/页面凭证纳入指纹；授权必须在每次重试重新校验。同 scope 同键异载荷 409 IDEMPOTENCY_CONFLICT。
- Java 首次受理在同一短事务保存唯一业务操作、可恢复非秘密载荷/指纹及槽位。成功重试返回原引用；进行中返回原 operationId；不同键仍受唯一活动名额/任务槽位限制。文件侧映射为工作区实例内唯一 requestId，不按文件拆键。
- 完整业务回执至少保留完成后 7 天；未决操作绝不按时间清除。详细回执到期后保留精简墓碑（scope、key 指纹、operationId、结果引用/已过期标志），同键返回原引用或 410 OPERATION_RECEIPT_EXPIRED，绝不作为新请求执行。密码回执不存明文，指纹沿用任务 02 的服务端 HMAC。
- 墓碑保留至所属资源不可再接收任何旧操作且所有内部派发均已被持久化栅栏覆盖；账号变更墓碑在账号存续期间保留。归属演练的记录纳入任务 16 的单场 512 MiB 预算及收尾预留，达到准入上限拒绝新增占用，不静默丢弃去重记录。非演练记录不因此获得无界存储承诺，容量与回收仍按其所属模块验证。
- 删除后内部防重放记录仅含必要标识、指纹与处理结果，不含代码、日志正文或凭证。完整业务回执不等于完整原始请求载荷；仍需对账的载荷/证据在安全收敛前不得清除，也不得提前报告清理完成。最小删除进度的 7 天期限不覆盖业务回执的完成时间下限，更不授权按统一 TTL 删除安全墓碑。
- 凭证签发类一次性秘密不做成功 body 缓存；响应丢失通过查询确认完成，再按明确路径换新凭证。身份撤销后旧会话访问操作回执仍 401，不能为幂等豁免身份。

## Java–Go 消息与重启恢复

下表为正式 `.proto` 的语义输入，具体字段编号/生成代码由任务 06/10/13/14 实现，不新增另一套协议源。

| RPC 语义 | 必需输入/结果 |
|---|---|
| PrepareWorkspace / QueryPreparation / CancelPreparation | exerciseId、attemptId、workspaceInstanceId、固定题目身份；返回准备状态及实例；取消墓碑先于销毁 |
| CloseGate / OpenGate / QueryGate | 实例、transitionId、expectedEpoch；返回持久化 epoch、OPEN/CLOSED/FROZEN、转换回执 |
| ApplyBatch / QueryMutation | 实例、requestId、operationId、指纹、基线、gateEpoch、notAfter、变更；返回任务 04 回执/NOT_FOUND/UNKNOWN |
| Freeze / QueryFreeze | 实例、关闭栅栏代次、operationId；返回冻结版本；不自行冻结一个仍 OPEN 的实例 |
| StopSystemTask / QueryStop | 受信任生命周期调用，系统停止要求 CLOSED/FROZEN；返回执行快照或持久化取消回执，并绑定接受/观察取消的 hostEpoch。Java 在协调锁内重新读取派发代次，跨宿主代次的缺失登记不能据此释放隔离槽位 |
| StartTest / QueryTask / StopTask | exerciseId、taskId、executionInstanceId、purpose、实例/预期修订/目标/固定执行配置、gateEpoch、notAfter；FINAL_VERIFICATION 另含 finalizationId、attemptId、冻结回执及 finalizationDeadlineAt；返回执行登记/结果/静默与清理状态 |
| WatchTask | taskId、executionInstanceId、afterSequence；服务端流 TaskEvent，支持显式 RESET_REQUIRED |
| ListExecutions / Reconcile / Cleanup | 有界分页 inventory 与宿主执行代次；清理只作用于经校验归属的精确实例，不模糊匹配目录/容器名 |

所有内部请求只允许受信任 Java，含超时/取消，但超时不撤销已持久化意图。`INVALID_ARGUMENT` 映射输入错误，`FAILED_PRECONDITION` 映射代次/冻结/状态冲突，`RESOURCE_EXHAUSTED` 映射容量；`UNAVAILABLE/DEADLINE_EXCEEDED` 是结果未知信号，不直接映射业务失败。NOT_FOUND 与存储不可读分开。

- Java 派发前落盘操作/outbox 及稳定 ID；重启扫描未决操作、到期演练、转换、撤销意图及任务，按原 ID 查询 Go，不生成新任务替代未知任务。outbox 是 PostgreSQL 表/worker，不引入消息队列。
- Go 在创建容器前落盘执行登记和固定 executionInstanceId；容器标签含受控实例标识。StopTask 在尚无登记时也持久化取消墓碑，迟到 StartTask 返回 STOPPED，不再启动。Start 的登记与 Stop/扫描共用执行协调区。
- Go 重启先关闭新任务准入，恢复登记，扫描其管理的容器标签，核对 taskId/执行实例/镜像。登记存在且容器仍运行：恢复观察；已退出且结果/结构化证据完整：恢复结果；容器存在但业务未确认：先询问 Java/核对，不再次启动。只有日志/退出码不足以证明权威测试通过。
- 未知结果经核对仍不可确认：标 INTERRUPTED；若进程仍可能存在，执行槽位/宿主容量继续隔离，直到确认进程退出且禁止旧 Start，不能以“中断”释放执行互斥。
- 登记缺失/损坏且无法排除旧 Start 时，持久化提升宿主执行代次并撤销旧派发，再清扫/隔离旧代次容器，最后恢复准入。内部旧代次请求拒绝；服务不能丢失登记后按空表对外提供 Start。磁盘不可持久化栅栏时保持不可用，需运维恢复。
- 孤儿容器、临时副本、stage 按登记/引用核对后删除；Java 不可用时不删除不明工作区或代码证据。清理单独持久化、幂等、可重试；失败告警且继续计容量。终态结果和日志先保存，再移除执行资源。

## 日志、快照与 SSE 衔接

### 信封与有界保留

- Go 每 task execution 持久化 `streamId` 和递增 `sequence`，事件含 schemaVersion、taskId、executionInstanceId、streamId、sequence、type、occurredAt、payload；type 为 `log`（channel=stdout/stderr）、`progress`、`log_truncated`、`task_result`。序号是去重/顺序依据，时间戳不是。
- Go 原子保存结果与对应事件/发布意图。Java 按 `(executionInstanceId, streamId, sequence)` 去重，持久化浏览器事件及物化快照的同一事务中推进浏览器 high-water cursor。浏览器流使用独立 streamId/sequence，不能把 Go 游标当浏览器游标混用。
- 日志正文按题目 `runtime.logBytes` 有界保留，保留尾部或首尾策略必须在快照声明，本阶段采用尾部；丢弃正文仍持续排空 stdout/stderr，累计 droppedBytes、首尾保留位置，并持久化 `truncated=true`。控制事件/结果有独立预留容量，不能被日志撑掉。
- 浏览器事件窗口技术初值为每任务最多 4096 条且最多 4 MiB，先到上限即淘汰最旧事件；单日志事件至多 16 KiB UTF-8 有效字节，超长行切块，不能把一行当无限对象。该窗口不是完整日志存储上限，日志上限以题目配置为准。单纯的事件窗口淘汰不删除结构化终态。
- 长期测试记录另按任务 16 保留：普通测试最近 20 次已完成完整记录，最终验收各次证据单独保留至演练删除；运行中、未知或恢复引用中的记录不得淘汰。整条普通测试记录淘汰后，不再承诺其终态/日志仍可查询，界面明确较早记录已清理，不把记录消失当作任务失败或重新执行的理由。相关代码版本只在无其他引用时回收。
- 查询快照返回任务完整状态/结果、日志保留范围与截断信息、`streamId`、`cursor`、`firstRetainedSequence`；日志可按已固定范围分页，分页途中保留窗口移动则明确 reset，不能拼接伪完整文本。

### 无缝查询与订阅

```text
浏览器 GET task snapshot
  → 原子得到状态 + 已保留日志范围 + cursor C
浏览器订阅 events?after=C
  → Java 在同一流协调边界登记订阅并取得高水位 H
  → 发送仍保留的 (C, H]；再发送实时 >H 的有界队列
  → 同 streamId、sequence <= 已应用游标：去重
  → 序号跳跃、流代次改变、游标过期/未来：重新取快照，不启动任务
```

- cursor 是绑定 task/stream 的不透明编码；错误任务、错误流或超出保留范围返回 `reset_required`（建连前可 HTTP 409 CURSOR_EXPIRED），已建流发送控制事件后关闭。客户端替换快照而非把新日志重复追加。日志窗口缺失必须展示截断，不承诺无限回放。
- SSE `id` 对应浏览器 cursor，`event` 为类型，`data` 为 JSON 信封；支持 `Last-Event-ID`，与 query 同时存在且不一致则拒绝。GET 不续期、不启动任务；终态来自快照/结构化事件，不能从连接关闭推断成功。
- 每连接待发送队列上限 256 KiB，超限停止排队并关闭（能发送则先发 reset_required，不能保证该提示送达）；重连按快照恢复。终态可查，不承诺慢连接必收到终态事件。每连接心跳 15 秒、每用户最多 5 条连接，超过返回 429；参数集中配置。

### 撤销与发送边界

- 每次建连、每批私有事件出队前、空闲时最多每 5 秒复核权威会话、认证版本、资源归属及删除状态。只读查询/SSE/心跳均不续期。数据库不可用则关闭，不沿用缓存授权。批次大小上限 64 KiB，不积压无限已授权数据。
- 撤销提交后使用本进程连接注册表立即关闭；事务持久化撤销版本供所有连接定期复核，不依赖易丢失通知。首期无需 Redis 或数据库 LISTEN/NOTIFY；通知丢失由 5 秒复核兜底，运行阻塞需监控，不能宣称硬实时网络断开。
- 出队授权检查与撤销事务按用户授权锁排序：撤销先提交，禁止新批次；批次先通过，则已交给传输层的有限字节不能追回。不能在持有数据库锁时等待网络发送。重置意图未完成栅栏期间暂停该用户流，完成后要求重新鉴权。

## 实现与验证边界

最小模型只枚举选定原子动作顺序，不证明原子性、进程持久性或真实跨服务排序。以下是各功能应落实的不变量，不是逐票真实故障矩阵。Agent 以确定性 L1 覆盖核心正常、失败和竞争分支，可用持久化夹具、依赖替身、异常注入和可控时钟；真实数据库、Docker、跨服务或浏览器验证仅按明确安排或具体问题最小执行。未做额外验证不自动产生 QA 欠账，不阻塞 Dev Done；不得以替身证明真实隔离效果。

- 任务 07/08：数据库唯一约束、登录/重置事务、撤销栅栏与 SSE；密码明文不进入 outbox。
- 任务 10/11：把 gateEpoch、关闭回执与任务 04 原子发布接在同一协调区；处理中断恢复、丢响应、迟到 RPC、刷新接管及发布前到期边界。
- 任务 13/14：执行停止/超时、容器创建前后中断恢复、登记丢失/损坏隔离、Go/Java 重启 inventory、执行代次栅栏、日志洪水/慢连接/撤销通知丢失。
- 任务 15/16：正常系统时间前提下的连续计时及临界竞争（系统时钟跳变风险按 ADR 0003 接受，不要求专项检测）、持久化重试预算、冻结与最终验收复用、容量配额/保留/墓碑 GC、删除与恢复竞态。未决/隔离记录不得靠 GC 放行旧请求。
- 任务 06 及相应功能：正式 Protobuf 字段编号与生成、REST 边界、Vue 消费与协议一致性；服务实现不能由模型证据代替。
