# 3天速通 + 面试深挖图谱（Backend & Agent Engineering）

本文面向 **后端研发 / Agent 工程** 面试准备：用「先立边界、再拆状态机、最后看实证」的方式，把 CodeCraft-AI 梳理为可直接在 5 分钟项目介绍与 40 分钟深挖中讲透的技术资产。

---

## 一、五分钟项目总览（面试开场白骨架）

- **业务定位**：面向 AI 结对编程面试的工程演练与评估平台，核心分为两条主链路：
  1. **Agent Loop 控制面**（Java / Spring Boot / PostgreSQL / LangChain4j）：围绕持久化状态机驱动模型调用、只读并发窗口、只读探索子任务、`micro-compaction` + 摘要压缩、人类 Diff 审批、`Steer` 抢占与崩溃恢复。
  2. **工作区与执行数据面**（Go Sandbox / gRPC / Docker Engine / Linux Cgroups & Seccomp）：以 `workspaceInstanceId + revision + gateEpoch` 为权威锁点，隔离用户代码编辑、只读测试副本抓取、`mvn test` 无网容器执行、以及受限出网代理。
- **架构主轴文档**：[architecture.md](design/architecture.md)、[copilot-loop-and-tools.md](design/copilot-loop-and-tools.md)、[workspace-and-sandbox.md](design/workspace-and-sandbox.md)、[task-recovery-and-realtime.md](design/task-recovery-and-realtime.md)。

---

## 二、3天代码速通路线

| 天数 | 聚焦模块 | 必读源码入口 | 掌握目标（自检问题） |
| --- | --- | --- | --- |
| **Day 1** | **域边界与持久状态机** | [ExerciseService.java](../backend/server/src/main/java/ai/codecraft/modules/workspace/service/ExerciseService.java)、[OutboxRelay.java](../backend/server/src/main/java/ai/codecraft/infrastructure/tasks/OutboxRelay.java)、[WorkspaceWriteCoordinator.java](../backend/server/src/main/java/ai/codecraft/modules/workspace/service/WorkspaceWriteCoordinator.java) | 为什么网络 RPC 不能放进 DB 事务？`PENDING -> UNKNOWN / PENDING_RECONCILIATION` 如何靠 `GateEpoch` 收敛？为什么 Outbox 拆了 `interactive` 与 `slow` 双池？ |
| **Day 2** | **Agent Loop 与量化观测** | [CopilotRunner.java](../backend/server/src/main/java/ai/codecraft/modules/copilot/service/CopilotRunner.java)、[CopilotContextPlanner.java](../backend/server/src/main/java/ai/codecraft/modules/copilot/service/CopilotContextPlanner.java)、[CopilotExecutionStore.java](../backend/server/src/main/java/ai/codecraft/modules/copilot/service/CopilotExecutionStore.java)、[V68__telemetry_retry_evidence.sql](../backend/server/src/main/resources/db/migration/V68__telemetry_retry_evidence.sql) | 并发读（≤4）和并发 Explore（≤3）的抢占安全点在哪里？`micro-compaction` 怎么省字节却不破坏 `tool_call/tool_result` 配对？ |
| **Day 3** | **Go 沙箱硬隔离与题库管理** | [runner_linux.go](../backend/sandbox/internal/execution/runner_linux.go)、[mutation_linux.go](../backend/sandbox/internal/workspace/mutation_linux.go)、[testcopy_linux.go](../backend/sandbox/internal/workspace/testcopy_linux.go)、[egress.go](../backend/sandbox/internal/security/egress.go)、[AdminProblemStore.java](../backend/server/src/main/java/ai/codecraft/modules/workspace/service/AdminProblemStore.java) | Docker 测试容器用了哪几层隔离？DNS Rebinding 在 `egress.go` 怎么防？多文件题包怎么做到幂等发布且对未接入执行器的新语言 fail-closed？ |

---

## 三、六个高频深挖点（问题 → 实现锚点 → 追问话术）

### 1. 工具并发窗口与 Steer 抢占安全点（Agent Loop）
- **核心矛盾**：模型单轮常输出多个只读工具（`read`/`grep`/`list`）或探索委派（`EXPLORE`），串行慢；但用户随时可能发 `Steer` 指令或 `Stop` 请求，不能等整批乱序副作用失控。
- **实现机制**：
  - [CopilotRunner.java](../backend/server/src/main/java/ai/codecraft/modules/copilot/service/CopilotRunner.java#L103-L142)：在 `TOOLS` 状态循环里先尝试聚合连续最多 **3 个 `MAIN` 通道的 `EXPLORE` 委派**（[`executeExplorations`](../backend/server/src/main/java/ai/codecraft/modules/copilot/service/CopilotToolExecutor.java#L67-L73)）；否则聚合连续最多 **4 个可重读的只读工具**（`rereadable()`）进入 [`executeReads`](../backend/server/src/main/java/ai/codecraft/modules/copilot/service/CopilotRunner.java#L171-L204) 虚拟线程池并发执行。
  - **配额与安全点**：整个窗口在启动前由 `capacity.admit(...)` 一次性登记准入并持久化工具槽位；`executeReads` 每 `100ms` 轮询租约/停止标记；**只有当当前窗口全部 `completeTool` 落库后**，外层循环才检查 [`steerAtBoundary`](../backend/server/src/main/java/ai/codecraft/modules/copilot/service/CopilotExecutionStore.java#L97-L112)，避免在单个只读批次半途撕裂消息序列。
  - 若存在未决外部副作用（`proposals.applying`、`tests.waiting`、`commands.waiting`、`delegations.pending`），`supersedeOldBatch` 明确拒绝抢占（#L102-#L112），直到外部状态收敛。
- **面试追问应答**："为什么只读窗口宽度上限设 4、Explore 上限设 3，而不是无限开并发？" → 存储/限流预算按批预扣，窗口即安全点边界；过宽会放大 `Steer` 响应延迟与数据库锁争用，实测 `4` 路在本地脚本基线中将整轮编排耗时（含本地 PG 事务与虚拟线程调度）从 `2675ms` 压到 `968ms`、模型轮次从 `5` 轮降到 `2` 轮（详见 [verification-evidence-ledger.md](verification-evidence-ledger.md)）。

### 2. 上下文微压缩（Micro-Compaction）与消息配对不变量（Context Engineering）
- **核心矛盾**：长对话中 `read_file`/`grep` 的返回体积膨胀最快，直接全量丢给模型会撑爆上下文或触发全量摘要；但如果直接删截工具结果，容易破坏模型要求的 `AiMessage(tool_calls)` ↔ `ToolExecutionResultMessage` 严格配对。
- **实现机制**：
  - [CopilotContextPlanner.java](../backend/server/src/main/java/ai/codecraft/modules/copilot/service/CopilotContextPlanner.java#L118-L143)：`completeUnits` 按工具调用批将消息切分为不可拆分的原子单元，确保重放或摘要裁剪永不把一组 `tool_calls` 与其 `tool_results` 劈开。
  - [CopilotContextPlanner.java#L75-L116](../backend/server/src/main/java/ai/codecraft/modules/copilot/service/CopilotContextPlanner.java#L75-L116)：对超出 `recentUnits` 保留窗口、且属于 `rereadable` 工具的历史结果，触发 `project(..., 512)` 微压缩——把大块源码/搜索输出裁剪为带 `code: "CONTEXT_EVIDENCE_TRUNCATED"`、`sourceMessageId` 与 `readTool: "read_evidence"` 指针的短凭证，同时累加 `microSavedBytes` 落盘，既省 Token 又保留按需回查线索。
  - **不可信输入隔离**（#L203-L213）：题目项目指令与 Skill 指南均以 `UserMessage` 身份前置注入，显式阻止被当作系统提示词提权。

### 3. Revision 冲突门禁与跨进程故障对账（Distributed Systems & Recovery）
- **核心矛盾**：AI 写提案、人工点保存、Go 沙箱执行异步并发；同时 Java 服务进程可能在发出 RPC 前、等待响应期间或写回 DB 前任意时刻崩溃。
- **实现机制**：
  - **规划版本与按文件读证据防陈旧**（[CopilotToolExecutor.java](../backend/server/src/main/java/ai/codecraft/modules/copilot/service/CopilotToolExecutor.java#L45-L56)、[CopilotProposalPreparation.java](../backend/server/src/main/java/ai/codecraft/modules/copilot/service/CopilotProposalPreparation.java)）：极简工具 schema 不强迫模型手写版本号，由执行器在调用前比对可选旧参并注入当前 `planningRevision`，同时校验被覆盖或局部修改的目标文件是否已在当前修订下被模型真实读取过；若期间文件被外部修改或未读目标文件即盲写，立即返回 `REVISION_CONFLICT` 引导模型先读目标文件再改。该恢复链路的精确 SQL 证据定义见 [V68__telemetry_retry_evidence.sql](../backend/server/src/main/resources/db/migration/V68__telemetry_retry_evidence.sql#L22-L34)（仅当冲突后成功重读且同参数工具无二次冲突完成重试才计入 `recovered_revision_conflicts`）。
  - **跨服务未知副作用收敛**（[WorkspaceWriteCoordinator.java](../backend/server/src/main/java/ai/codecraft/modules/workspace/service/WorkspaceWriteCoordinator.java#L44-L97)、[CopilotExecutionStore.java](../backend/server/src/main/java/ai/codecraft/modules/copilot/service/CopilotExecutionStore.java#L503-L561)）：DB 事务内绝不持有 gRPC 网络调用；RPC 异常先标 `unknown` / `PENDING_RECONCILIATION`，随后 `drive(...)` 先调沙箱 `closeGate` 推进 `GateEpoch` 阻断旧时代延迟写入，再反查 `QueryMutation` 收敛是"已提交"还是"未提交"。
  - **端到端证明**：[CopilotCrashRecoveryIT.java](../backend/server/src/test/java/ai/codecraft/modules/copilot/service/CopilotCrashRecoveryIT.java#L48-L160) 与 [WorkspaceCrashRecoveryIT.java](../backend/server/src/test/java/ai/codecraft/modules/workspace/WorkspaceCrashRecoveryIT.java#L35-L120) 真实强杀子 JVM 并检验 `write-before` / `write-after` / `test-crash` 崩溃点不重复写磁盘（`applies.txt` 严格无重放），且在 Go 重启恢复期间已有持久任务占槽优先返回 `409 TEST_SLOT_BUSY`（[TestTaskStore.java#L128-L131](../backend/server/src/main/java/ai/codecraft/modules/workspace/service/TestTaskStore.java#L128-L131)）。

### 4. Go 沙箱内核隔离、幂等写与防 SSRF 出网代理（Infrastructure & Security）
- **执行隔离**（[runner_linux.go](../backend/sandbox/internal/execution/runner_linux.go#L404-L442)）：评测容器强制 `NetworkMode: "none"`、`ReadonlyRootfs: true`、`CapDrop: ["ALL"]`、`no-new-privileges:true`、内置 Seccomp 配置、`noexec` 的 `/work` 与 `/tmp` tmpfs、非 root UID `10001:10001`，并以只读 volume subpath 挂载目标测试快照。
- **沙箱存储一致性**（[mutation_linux.go](../backend/sandbox/internal/workspace/mutation_linux.go#L25-L59)、[testcopy_linux.go](../backend/sandbox/internal/workspace/testcopy_linux.go#L40-L90)）：`ApplyBatch` 按 `RequestId + Fingerprint` 保证幂等与篡改拒绝，并在终端占有工作区时拒绝并发写（`TerminalBusy`）；`PrepareTestCopy` 校验 `GateEpoch` 与 `ExpectedRevision` 一致后才发布不可变运行副本。
- **防 DNS Rebinding 代理**（[egress.go](../backend/sandbox/internal/security/egress.go#L15-L70)）：针对需要联网下载依赖的命令通道，仅允许精确 `domain:80/443` 白名单（禁止通配符），单次解析 IP 后校验禁止所有私网/环回/保留网段及非公网 IPv6 地址，再直连已校数字 IP，避免二次解析劫持。

### 5. 多语言契约解耦与多文件工程题设计（Platform Engineering）
- **语言契约与 Fail-Closed**：[ProblemLanguage.java](../backend/server/src/main/java/ai/codecraft/modules/workspace/service/ProblemLanguage.java) 将题目语言拆为 `JAVA_MAVEN`（可沙箱执行）与 `PYTHON_PYTEST`（静态契约发布验证）；[ProblemRepository.java](../backend/server/src/main/java/ai/codecraft/modules/workspace/repository/ProblemRepository.java#L22-L32) 与 [ProblemTransactionService](../backend/server/src/main/java/ai/codecraft/modules/workspace/service/AdminProblemStore.java#L41-L46) 拦截不可执行版本进入启动流程（`EXECUTION_UNSUPPORTED`），前端 `/practice` 同步禁用启动并标明“仅静态契约”。
- **幂等发布闭环**：[AdminProblemController.java](../backend/server/src/main/java/ai/codecraft/modules/workspace/controller/AdminProblemController.java#L30-L48) 与 [AdminProblemStore.java](../backend/server/src/main/java/ai/codecraft/modules/workspace/service/AdminProblemStore.java#L33-L62) 通过 `problem_publish_receipt(actor_id, request_key, fingerprint, version_id)`（见 [V66](../backend/server/src/main/resources/db/migration/V66__problem_publish_receipts.sql)、[V67](../backend/server/src/main/resources/db/migration/V67__static_publications_and_receipt_results.sql)）区分同 key 同包重放与同 key 异包冲突（`IDEMPOTENCY_CONFLICT`），由 [PublicationConcurrencyTest.java](../backend/server/src/test/java/ai/codecraft/modules/workspace/service/PublicationConcurrencyTest.java) 验证并发竞态原子性。
- **新增多文件工程题**：`problems/sliding-window-rate-limiter/1.0.0`（多文件：`QuotaKey.java` / `Rule.java` / `SlidingWindow.java`，含 `challenge/` 进阶动态规则热更新与优先级预留、以及初始根目录与包级分层 `AGENTS.md` 引导）。

### 6. Agent 可观测账本与确定性回放基准（Evaluation & Telemetry）
- **零双写开销遥测（读时关联聚合）**：[V65__copilot_telemetry.sql](../backend/server/src/main/resources/db/migration/V65__copilot_telemetry.sql) 与 [V68__telemetry_retry_evidence.sql](../backend/server/src/main/resources/db/migration/V68__telemetry_retry_evidence.sql) 仅在原有 checkpoint 表扩展固定列，并用普通 SQL 视图 `CREATE OR REPLACE VIEW copilot_task_telemetry` 配合 `CROSS JOIN LATERAL` 对 `turn`、`tool`、`delegation` 按索引分别聚合——确保“一轮调用 4 个工具仍只计 1 次模型请求”且 Chat Token 不混入 Summary 轮次。之所以用普通视图而非物化视图，是因为单场演练的轮次与工具行数在百行量级，索引下按需读时聚合仅亚毫秒级，既避免事务内双写记账开销，又没有物化视图 `REFRESH` 的数据陈旧与锁表问题。
- **多租户权限前置**：[CopilotTelemetryService.java](../backend/server/src/main/java/ai/codecraft/modules/copilot/service/CopilotTelemetryService.java#L20-L28) 先 `REPEATABLE_READ` 下执行 `exercises.find(session, exercise)` 校验会话归属再查账本，防越权读取（见 [CopilotTelemetryServiceTest.java](../backend/server/src/test/java/ai/codecraft/modules/copilot/service/CopilotTelemetryServiceTest.java)）。
- **确定性对照评测**：[CopilotReplayEvalTest.java](../backend/server/src/test/java/ai/codecraft/modules/copilot/service/CopilotReplayEvalTest.java) 复用真实状态机与 PostgreSQL 评测脚本化 `ModelCallDriver`，直接给出并行读 vs 串行读、Explore 子任务 vs 主上下文字节节省、micro-compaction 压缩收益及 Steer/Ask 重试计数（详见 [verification-evidence-ledger.md](verification-evidence-ledger.md)）。

---

## 四、七大后端高频考点面经对照表（现状 / 面试直答话术）

| 考点 | 代码锚点与真实机制 | 面试时可以直接这样回答 |
| --- | --- | --- |
| **高可用与卷亲和** | [SandboxNodeRepository.java](../backend/server/src/main/java/ai/codecraft/modules/workspace/repository/SandboxNodeRepository.java)（15s 租约 + `storageId` 绑定）、[SandboxRoutingStore.java](../backend/server/src/main/java/ai/codecraft/modules/workspace/service/SandboxRoutingStore.java)、[node_linux.go](../backend/sandbox/internal/workspace/node_linux.go) | “演练的工作区文件树和执行历史落盘在有状态的本地卷上。新开演练时 Java 按健康租约和剩余槽位挑选节点并持久绑定 `storageId`；一旦绑定，如果该节点失联，系统宁可对这场演练返回不可用，也绝不自动切去另一台空白节点冒充成功，防止静默丢代码或破坏单调修订号。” |
| **故障恢复与防重复扣费** | [OutboxRepository.java](../backend/server/src/main/java/ai/codecraft/infrastructure/tasks/OutboxRepository.java)（30s 发布租约 / 10min SENT / 5min 消费租约）、[OutboxStore.java](../backend/server/src/main/java/ai/codecraft/infrastructure/tasks/service/OutboxStore.java)（5 次失败进死信） | “我把恢复分成三类：消息未发出靠 30 秒发布租约重领；消息已发出但消费者崩溃靠 5 分钟消费租约重投，连续失败 5 次进死信隔离；最关键的是外部收费 LLM 调用或容器执行一旦发出但结果未知，重启后绝不自动重发，而是先向沙箱查询已提交回执或拉起 `gateEpoch` 栅栏收敛。” |
| **数据一致性** | [V63__outbox_events.sql](../backend/server/src/main/resources/db/migration/V63__outbox_events.sql)（同事务触发器）、[StorageCapacityStore.java](../backend/server/src/main/java/ai/codecraft/modules/workspace/service/StorageCapacityStore.java) | “Java 的 Postgres 与 Go 的文件系统不做跨进程两阶段提交，而是靠‘同库事务写 Outbox + 操作前预留配额 + 单调序号后置对账’：写磁盘前先在数据库扣住最大预留量，Go 靠 `gateEpoch` 和 `revision` 拦截迟到或乱序写，完成后再用带 `meterEpoch` 和单调序号的快照释放已收敛预留，旧快照绝不会覆盖新账本。” |
| **全链路幂等性** | [useCommand.ts](../frontend/src/api/useCommand.ts)、[OutboxConsumer.java](../backend/server/src/main/java/ai/codecraft/infrastructure/tasks/OutboxConsumer.java)、[mutation_linux.go](../backend/sandbox/internal/workspace/mutation_linux.go) | “幂等分两层：消息层即使 Broker 重复投递，消费者也会在 Postgres 里抢 `outbox_consumption` 的 5 分钟租约与 token，已完成或租约内的重复投递直接跳过；业务层上，前端在超时未知时固定用原 `Idempotency-Key` 核对，Go 沙箱按 `RequestId + Fingerprint` 直接返回已提交的历史回执，换参重放则直接报冲突。” |
| **水平扩展与隔离** | [SandboxRoutingStore.java](../backend/server/src/main/java/ai/codecraft/modules/workspace/service/SandboxRoutingStore.java)、[retention_linux.go](../backend/sandbox/internal/execution/retention_linux.go)、[terminal_linux.go](../backend/sandbox/internal/execution/terminal_linux.go) | “水平扩展按演练粒度分片：多个 Java 实例通过数据库行锁选节点，把不同演练分摊到多台 Go 沙箱节点上；同一台 Go 节点内部按工作区隔离终端静默检查与历史回收，避免候选人 B 长时开着终端阻塞候选人 A 的关门与磁盘清理。” |
| **限流与差异化降级** | [RedisSlidingWindowRateLimiter.java](../backend/server/src/main/java/ai/codecraft/infrastructure/ratelimit/RedisSlidingWindowRateLimiter.java)、[TransientTaskStreamHub.java](../backend/server/src/main/java/ai/codecraft/infrastructure/stream/TransientTaskStreamHub.java)、[RabbitTaskConfiguration.java](../backend/server/src/main/java/ai/codecraft/infrastructure/tasks/RabbitTaskConfiguration.java) | “不同链路选了相反的故障策略：限流器在 Redis 异常时故意失败关闭、绝不切回 PG，否则切换瞬间配额计数归零会被打穿；反过来，Copilot 流式预览走有界内存池（128 槽 LRU 淘汰）和 Redis Pub/Sub，允许随时丢帧降级，因为权威状态有 PG 检查点和前端 2 秒轮询保底。” |
| **关键工程取舍** | [ModelCredentialCipher.java](../backend/server/src/main/java/ai/codecraft/modules/modelaccess/security/ModelCredentialCipher.java)、[CopilotContextPlanner.java](../backend/server/src/main/java/ai/codecraft/modules/copilot/service/CopilotContextPlanner.java)、[mutation_linux.go](../backend/sandbox/internal/workspace/mutation_linux.go) | ① **密钥环直接加密 vs 信封 DEK**：API Key 仅 `<4KB`，`AES-GCM` 微秒级加解密配合 AAD 与后台 CAS 逐行重加密即可，省去双层密钥表；② **SSE 快照 vs 游标**：Copilot 状态用最新快照收敛，测试输出是追加日志才用 `?after=` 游标续传；③ **本地字节预算 vs 供应商 Token**：跨多厂商模型在本地用 UTF-8 字节做确定性硬上限与微压缩触发线，计费严格按供应商 `tokenUsage` 回执结算。 |

---

## 五、诚实边界（被问“还有哪些没做 / 已知限制”直接答）

1. **`PYTHON_PYTEST` 当前仅为静态契约验证**：库表支持存储无执行镜像的静态发布包，管理员页与选题页均显式提示并阻断启动，目前未接通真实 Python pytest 容器运行时。
2. **`CopilotReplayEvalTest` 为确定性脚本基线，非真实公网 LLM 评测**：报告标题显式标注 `DETERMINISTIC_SCRIPTED_BASELINE`，验证的是平台 Loop 编排、DB 事务与上下文规划器的性能与不变量，不包含外部大模型服务商网络延迟或自然语言推理准确率。
3. **跨进程崩溃恢复测试对子 JVM 冷启动与节点探活租约的依赖**：在 Windows Docker bind-mount 场景下，[CopilotCrashRecoveryIT](../backend/server/src/test/java/ai/codecraft/modules/copilot/service/CopilotCrashRecoveryIT.java) 与 [WorkspaceCrashRecoveryIT](../backend/server/src/test/java/ai/codecraft/modules/workspace/WorkspaceCrashRecoveryIT.java) 若直接从挂载盘扫描类路径，仅启动阶段即耗约 50s 且 HTTP `/api/internal/status == 200` 并不代表后台调度器已完成 `sandbox_node` 租约探测；现已由 [RecoveryChildRuntime.java](../backend/server/src/test/java/ai/codecraft/testfixture/RecoveryChildRuntime.java) 在容器本地文件系统暂存 classpath 并真实校验 `sandbox_node` 租约后运行通过（不篡改租约表、不放宽超时）。需要注意的是：**全量无界实测（显式传入 `node scripts/verify.mjs --all` 或 `--heavy`；不传参数默认为 `--quick`）与公网付费模型 `copilot-browser` E2E 仍按明确环境门控跳过或按需手动触发**，详见 [verification-evidence-ledger.md](verification-evidence-ledger.md)。
