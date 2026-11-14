# P2-14：恢复与竞争场景验收

**Type:** implementation
**Status:** needs-info
**Blocked by:** P2-12 自动审批模式；P2-13 运行中的 steer
**Spec:** [第二阶段规格](../spec.md)

## What to build

对已逐票交付的持久化和恢复做跨服务故障注入，补齐批准、停止、steer、到期、接管和身份撤销的组合边界。修复归属原功能，不另写旁路恢复器。

## 开工门槛

第一阶段达到 Dev Done（功能、L1 与代码审查完成，不含远程 CI 或重型验证）；前置能力及 P2-02 恢复/授权契约完成。读 [接口恢复](../../../docs/design/api-events.md#保存与恢复)、[底座恢复协议](../../../docs/design/task-event-recovery.md)。

## Acceptance criteria

- [x] 待审批跨 Java 重启恢复，保留完整批次、执行位置及已完成结果；处理前重新检查权限、版本和提案状态，不自动批准。
- [x] 在 Go 发布前/后、响应丢失、Java 入库前/后故障时，按原请求对账；不重复应用、不把未知结果伪报未应用。
- [x] 模型流中断或 Java 重启不自动重发收费请求；用户重试路径明确，浏览器重连只恢复观察，部分内容标不完整。
- [x] 批准与停止/steer/到期按确认顺序处理，已应用保留，先失效则禁止写入；工具 ID/结果/推理字段不因恢复缺失或错配。
- [x] 接管、刷新、会话撤销与后台 auto 的授权栅栏正确，旧页面不能继续操作，合法后台任务不因浏览器断线误停。
- [x] 父任务停止与所属测试自然完成、未知执行、Go 重启一致；不误停独立测试，不提前释放隔离槽位。
- [x] 真数据库、文件提交及 Docker 故障测试与模型替身测试分别记录，模型协议再由真实供应商票验证；不能以状态模型证明 exactly-once。

## Comments

这是综合恢复验收，不是持久化第一次进入实现。P2-08/09/10/12/13 必须已具备所属功能的检查点与回归证据。

### 实施决定

- 复用正式协调器及既有 `WorkspaceCrashApplication`/Linux tracer 的真实子 JVM、PostgreSQL、Go、Docker 故障方式，不新增生产恢复旁路。模型使用测试专用、禁止真实网络的受控 SDK 流；与真实供应商兼容证据分开。
- 最小真实边界覆盖：待审批 JVM 强杀重启；Go 已发布/Java 回执入库前与入库后强杀；未知写入期间 steer 先等待原回执；半截模型流强杀后禁止自动重发；已登记 Docker 测试、Java/Go 重启及父停止仍保留原执行身份和隔离槽位。底层 Go 发布前/后文件故障复用既有真实子进程 crash 测试。
- 不等待 130 秒租约：确认旧 JVM 已退出后，仅测试夹具定向老化该任务 worker lease；不改会话/演练/Go 截止时间。所有真实进程仍处于同一 Linux 时钟域。其他批准/停止/到期/接管/撤销顺序复用已有确定性证据并针对缺口补测。
- 模型未知恢复的界面应明确供应商可能计费；用户显式停止/新请求与重新确认费用是重试路径，GET/SSE/浏览器重连不派发。当前 `recover` 仅在内部 turn 标 MODEL_RESULT_UNKNOWN、任务却统一报 SERVICE_INTERRUPTED，已将该未知原因贯通任务视图。

### Dev Done 与实际验证

- 新增独立非收费入口 `node scripts/test-tracer.mjs --workspace --boundary-only --copilot-recovery`；编译后在隔离 Linux 测试容器启动真实子 JVM，重用正式服务/SQL/原 RPC。`CopilotRecoveryApplication` 仅在 test-classes，SDK 发布者禁止真实供应商；故障钩子只阻塞选定边界或在确切回执点硬退出，不新增生产恢复器。命令混用在创建资源前拒绝。
- 真实最终链路 `.runtime/cc-tracer-33436-4756da83/` 通过，测试 254.3s（`/tmp/copilot-recovery-real3.log`）。待审批强杀后原 Diff/提案仍 PENDING；Go 成功但 Java 尚未记账时强杀，恢复暂时阻塞查询再接受 steer，仍 WAITING_WRITE，释放后仅原回执推进 revision 1；Java 回执已提交后再次强杀，原批次恢复到 revision 2。Apply 身份无重复，旧工具逐 ID 配对，SDK thinking 字段保留。
- 半截模型流保留“不完整预览”；旧 JVM 已死后定向老化 worker lease，新 JVM 返回 INTERRUPTED/MODEL_RESULT_UNKNOWN，原模型调用只出现一次。真正网络中断的收费情况不由此推断；该次 SDK 是无网络替身。
- 真实 Go 接管后旧凭证请求返回 STALE_CONTROL；合法 AUTO 在无页面凭证的只读观察期间继续成功到 revision 3。另一 AUTO 提案在授权点前阻塞，经正式注销/Go 撤销栅栏完成后，新的 USER 会话只读观察不能复活原任务；解除阻塞得到 CANCELLED/AUTHORIZATION_ENDED，无新增 Apply。
- revision 4 的真实 Docker 长测试：Go 接受 Start 后 Java 硬退出，再重启本次 Go；未知期间独立手动测试返回 TEST_SLOT_BUSY。父停止后原 childTaskId/executionInstanceId 收敛为 STOPPED、quiescent=true、copyCleanup=SUCCEEDED，Start 仅一次。持久结果在 `java-tests-copilot-recovery/server/target/copilot-recovery-13726547587517310175/result.json`。自然结果先赢、到期及不误停手动测试的确定性顺序复用完整 PostgreSQL 套件与 #10 实际证据，不声称穷举所有物理时序。
- 底层真实 Go 文件/子进程 `node scripts/test-workspace.mjs -test.run=TestMutationProcessFailureRecovery` 通过：update/rename × 发布前 staging 写失败/发布后丢响应，共 4 场景（`/tmp/copilot-recovery-go.log`）；不是状态模型。
- PostgreSQL 完整 63 项：61 通过、2 收费默认跳过（`/tmp/copilot-recovery-full.log`）。未知模型原因先复现红灯 SERVICE_INTERRUPTED，再定向修复通过；新增 UI 回归验证计费未知提示、不完整预览及不自动启动。quick Java 261/Vue 176、Node、类型/Lint、Go 全通过，33.02s（30s 目标警告、60s 硬限内）；前端生产代码未变，复用 #13 构建证据。
- 首次真实入口因测试会话 token 长度不符而未通过，已改用 32 字节 base64url token；后续基础链路 249.9s 及扩展链路均通过，未调用收费供应商。全部仅清理本次资源，不触及历史 tracer 容器。
- 自审：生产改动仅贯通未知结果原因；原请求、持久身份、授权、栅栏和恢复协调器不变。测试故障点位于事务外且精确确认提交后才强杀；真实与替身证据已分开，未以有限用例承诺通用 exactly-once。无已知票内功能缺口。
