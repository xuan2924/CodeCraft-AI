# 任务 14：停止、恢复与事件流底层切片

> 本文保留各切片当时的执行事实、失败及限制，不新增当前验收义务。当前完成边界以根 [AGENTS.md](../../AGENTS.md) 和 [#14 最新验收边界](../../.scratch/workspace-test-foundation/issues/14-stream-stop-recover.md#当前验收边界用户确认取代上述历史验证门槛) 为准；旧“后续门槛”不自动成为待办，已有证据无需逐票重跑。

当前状态：**#14 已达到 Dev Done，双轴独立审查问题已修复并复查；第一阶段 #15/#16 尚未完成**。新增确定性恢复/响应竞态测试及实际检查结果集中记录在 [#14 收口记录](../../.scratch/workspace-test-foundation/issues/14-stream-stop-recover.md#dev-done-收口2026-11-12)。下文为各切片当时的记录，旧缺口不代表当前状态。基线为实施前 `fa3edde8cc5d97fab297b0562592497330d4b37f`；审查固定点及 xterm 6.0.0 已由用户确认。本文不替代任务验收清单，也不把底层流称为浏览器实时日志交付。

## 续作：SSE 与浏览器（该切片进度）

用户已同意继续实施，包括此前提出的 xterm 6.0.0 依赖及 `fa3edde8` 审查基线。最终独立审查尚未执行，以下仍不是整票完成声明。

- V11 保存独立浏览器 stream/sequence、有界事件窗口与 Go 检查点；物化快照和浏览器高水位在任务锁下同事务推进。Go WatchTask 用于唤醒观察，日志合并为权威检查点再发布，不承诺逐分片原样回放；需要替换日志时 progress 显式携带 replaceLogs。
- Servlet 官方非阻塞输出承载 SSE；每次出队按用户授权锁复核、读取最多 64 KiB，连接不设置私有事件排队，慢写关闭并从持久化快照恢复。每用户 5 条/进程 32 条上限、15 秒心跳；权威会话每秒复核，本地撤销通知立即关闭。网络写不在数据库事务中。
- Vue 在查询缓存内应用有序事件，100ms 合批，客户端队列上限 256 KiB；重复忽略、跳跃/换流/重连重新查询，不自动重放 Start/Stop。卸载关闭连接、取消请求及计时器。停止未知保留原幂等键，旧页不可操作。
- xterm 禁止输入、控制序列过滤、独立 64K 字符显示尾部及 2000 行滚动边界，文本视图可复制；无剪贴板/链接插件，销毁 terminal 与 ResizeObserver。
- 实际红灯：SSE 路由 404、前端缺少停止按钮、前端未创建事件连接。修复后对应测试通过。
- `task14-sse-watch.log`：真实 stdout/stderr 到 SSE，真实密码重置关闭已有连接。
- `task14-sse-limits.log`：5 条连接/第 6 条 429，坏游标 409；注入数据库撤销且不发布本地通知后所有闲置连接关闭（此项是通知丢失故障注入，不冒充另一轮真实重置）。
- `task14-tracer-stream.log`：25 项 WorkspaceIT、WorkspaceCrashRecoveryIT、StatusIT 和完整现有浏览器链路通过。新增浏览器场景实际执行 Go/Docker 日志、xterm、网络离线/重新加载、同任务恢复、接管后旧页订阅继续、双页观察 STOPPED，任务数量不增加。
- `task14-verify-stream.log`：完整 verify 通过；前端 10 文件 65 项、vue-tsc、lint、build、audit 通过；verify 中真实依赖 IT 仍跳过，由上面的 tracer 单独执行。已有 Vue 格式警告和 Monaco 大 chunk 警告保留，不称为零告警。
- **当时的功能缺口**：宿主执行代次/分页 inventory、登记损坏恢复与隔离、创建/启动中断恢复、Java 运行任务重启、清理失败持久化退避等；后续完成情况以票据为准，本记录不替正在实施的改动宣告完成。
- **当前收口边界**：实现剩余功能并覆盖确定性 L1，完成整票代码审查；真实日志洪水、慢消费者故障矩阵和完整 tracer 不再是默认门槛。已有真实测试记录保留，不自动产生待 QA 项。

以下保留首个切片的历史记录；其中“尚未实现 SSE/前端”及“待确认”描述已由本节更新。

## 本轮实现

- 正式 `StopTask`、`QueryStop`、`ReconcileTask`、`WatchTask` RPC；只接受内部身份。停止校验当前工作区栅栏及授权期限，与接管排序；观察/订阅不授予写权限。
- Go 持久化串行区排序停止/超时意图和终态；停止先提交进入 STOPPING，确认静默后 STOPPED，终态不可被迟到停止覆盖。每次持久化推进结果版本，Java 拒绝旧版本观察覆盖新状态。
- 尚未登记的停止保存完整身份取消墓碑；迟到 Start 不准备副本或创建容器。`QueryStop` 可在原 gate/授权失效后核对旧回执，不重新授予停止权限。
- Java V10 保存停止操作、原 gate 和期限；REST 停止检查身份、归属、CSRF、页面凭证、幂等键。原请求未知时先查回执，失效授权不被续期。当前页面可停止，旧页面不能停止；完成后停止返回原终态。
- 已有合法登记及容器的进程重启可恢复观察，不重新调用 Create/Start，不重置单次超时。已确认结果只重试清理，不重新判分；确认不到可靠证据时明确中断并保留未静默槽位。副本释放墓碑支持删除中途失败后的幂等重试。
- Go 持久化日志检查点和有界事件窗口（4096 条、4 MiB，单日志事件不超过 16 KiB）；stdout/stderr、进度、截断及终态独立；gRPC 游标过期/换流返回 reset_required。Watch 不持工作区协调锁等待网络，断开不会停止执行。
- 恢复 Docker 日志使用 SDK `ContainerLogs`，替换旧快照并更换流代次；标记日志可能不完整，不声称全量回放。接口依据：[Moby v28.5.2 官方实现](https://raw.githubusercontent.com/moby/moby/v28.5.2/client/container_logs.go)。

## 实际执行

### TDD / 故障切片

| 边界 | 红灯 | 绿灯与证据 |
|---|---|---|
| gRPC + 真实 Docker 停止 | StopTask Unimplemented | 正在运行 → STOPPING → STOPPED，容器及副本回收；停止先于登记阻止迟到 Start；接管后的旧 gate 拒绝；完成后的停止保留结果 |
| 真实子进程 Kill 后恢复 | 恢复后仍 RUNNING、未回收 | 相同执行/容器 ID 和 startedAt 恢复 PASSED，并回收原容器；绝不重跑 |
| 停机超过保护超时 | 旧日志 deadline 已过期，恢复返回错误且一直 RUNNING | 先保存超时意图并终止原容器，得到 TIMED_OUT，不续期 |
| gRPC 日志 | WatchTask Unimplemented | 运行中收到真实 stdout/stderr 事件；关闭 Watch 后任务仍 RUNNING |
| 取消回执恢复 | QueryStop Unimplemented | gate 关闭后仍可查询之前取消墓碑，迟到 Start 仍拒绝 |
| REST + PostgreSQL + Go + Docker | 停止路由 404 | 当前控制权停止成功、旧控制权 409、跨用户 404、原键重试返回 STOPPED |
| Docker 清理故障 | 既有 Engine DELETE 500 故障夹具 | FAILED 清理状态可查，恢复 Engine 后重试清理成功，原 PASSED 和证据不变 |

- 首次超时恢复夹具未抵达运行同步点，不作为目标红灯；修复诊断输出并重跑后，实际得到 17 秒停机后仍 RUNNING 的目标红灯，再实施修复。
- 本轮自检修正 MyBatis 不允许同名重载映射语句的问题，后续完整检查通过。

### 完整检查与真实链路

- `node scripts/generate-proto.mjs`：通过，生成文件来自正式 `.proto`。
- `scripts/verify.mjs`：最终通过，使用仓库既有 LLVM-MinGW，仅设置当前进程 CC。含 Java 本地 18 项、Go 格式/vet/编译/Windows race、Linux 工作区及真实 Docker 执行套件、前端 9 文件 62 项、vue-tsc、lint、build、audit。
- verify 中 38 项真实依赖 IT **明确跳过**，不算通过。
- 单独 `node scripts/test-tracer.mjs --workspace`：通过，23 项 WorkspaceIT、1 项 WorkspaceCrashRecoveryIT、1 项 StatusIT、现有浏览器工作区用例及依赖故障/重启探针。此时新取消回执补丁尚未加入。
- 取消回执补丁后重新执行全套 verify，并定向执行 `stopsRealExecutionWithCurrentControlAndDurableOutcome`：通过。补丁不改前端，未再次跑完整浏览器链路。
- 日志位于忽略目录：`.runtime/task14-verify-slice-final.log`、`.runtime/task14-tracer-slice.log`、`.runtime/task14-rest-stop-final.log`。红绿过程另见同目录 `task14-*` 日志。
- 首次 verify 被既有未跟踪的 `problems/cart-total/1.0.0/initial/target/` 编译产物挡住（严格题目路径校验拒绝 `.class`）。测试期间临时移出，测试后恢复原位置；没有放宽校验或改变题目源码。再次检查前仍需隔离该本地产物。

## 剩余交付与验证

1. **Java 尚未消费 WatchTask，也没有 SSE**：浏览器独立游标、事件/物化快照同事务、发送限流、有界队列、慢连接、撤销锁排序、通知关闭和 5 秒复核仍未实现。
2. **前端仍是任务 13 的轮询界面**：实时只读终端、停止按钮/未知状态、断线恢复、订阅卸载、控制序列过滤及相应浏览器测试仍未实现。既有浏览器测试通过不代表这些新行为通过。
3. 登记缺失/损坏、未知孤儿容器仍失败关闭；尚未实现协议要求的宿主执行代次持久化提升、旧派发栅栏和分页 inventory。不能宣称整套重启恢复已交付。
4. 清理恢复目前由 Java 未决任务观察驱动；完整持久化退避、持续失败提示和未确认 Create 的隔离收敛仍需补齐。没有覆盖完整创建/启动每一提交点的 Kill 矩阵、真实 daemon 宕机或磁盘故障矩阵。
5. 本轮已覆盖真实流取消，但尚未验证慢消费者/洪水游标全矩阵、事件存储损坏、跨 Java 重启流衔接、密码重置在途 SSE、浏览器恢复。
6. 最终双轴独立审查尚未启动；等待用户确认固定点，完成剩余实现后执行，不能把本轮自检称为独立审查。

## 待确认

- 按既定 xterm 技术路线，拟新增 `@xterm/xterm@6.0.0`。现有 `frontend/package.json` 未声明此依赖；前端规范要求新增依赖先确认。官方 npm 清单已核对；只读终端不接输入，限制缓冲并过滤不可信控制行为。不引入 WebGL、剪贴板或链接 addon。
- 最终 code-review 固定点建议使用本轮开始前 `fa3edde8`。
