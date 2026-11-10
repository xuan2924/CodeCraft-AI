# 任务 14：停止、恢复与事件流底层切片

状态：**整票未完成，未做最终独立审查**。基线为实施前 `fa3edde8cc5d97fab297b0562592497330d4b37f`；审查固定点及前端新依赖待用户确认。本文不替代任务验收清单，也不把底层流称为浏览器实时日志交付。

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
