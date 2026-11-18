# 验证证据台账与诚实边界

本台账严格按 **运行环境真实度** 分三层记录本仓库的可复现证据，避免把替身/脚本测试混说为公网大模型或真容器实测。

---

## 层级说明

| 证据层级 | 真实组件 | 替身 / 不覆盖范围 | 核心命令 |
| --- | --- | --- | --- |
| **Tier 1：真 Docker 端到端** | Linux Docker 引擎、PostgreSQL、Go 沙箱、Java 主服务、Playwright 浏览器、Seccomp/Cgroups | 无外部公网模型（浏览烟雾用本地可控 fixture） | `test-execution.mjs`、`validate-problem.mjs`、`test-tracer.mjs` |
| **Tier 2：隔离真 PG + 脚本化模型驱动** | 真实 PostgreSQL、MyBatis/Flyway 71 步迁移、Copilot 状态机与手工组装的持久化服务夹具 | `ModelCallDriver` 与沙箱/文件读写为确定性脚本替身，不含公网 LLM 延迟与全套 HTTP 过滤链 | 配置 `COPILOT_IT_JDBC_URL` 运行 `CopilotReplayEvalTest`、`PublicationConcurrencyTest`、`CopilotPersistenceIT` 等 PG IT |
| **Tier 3：快速门禁 / 前端单测 / 静态契约** | Java 架构与业务单测、Go 宿主单测与 Linux `go vet`、Vitest/JSDOM 组件、Playwright Chromium IndexedDB 集成、Node 静态契约 | 默认 `verify.mjs --quick` 不连接外部 PG/Docker（Windows 下跳过 `*_linux_test.go`，另已用容器验证 `internal/workspace` Linux 单测 70 PASS / 1 SKIP）；`PYTHON_PYTEST` 无运行容器 | `node scripts/verify.mjs --quick`、`npm --prefix frontend run test:*` |

---

## 一、Tier 1：真实 Docker 与浏览器端到端实证

1. **Go 沙箱安全与执行全套（`node scripts/test-execution.mjs`）**
   - 覆盖范围：容器隔离、`NetworkMode: "none"`、只读根文件系统、`CapDrop: ALL`、`no-new-privileges`、Seccomp、磁盘/tmpfs/PIDs/内存配额、日志截断（1 MiB 上限）、超时强杀（`exitCode=137`）、Egress 防 SSRF 白名单代理、按工作区/执行 ID 精确回收与孤儿容器清理。
   - 本轮容器实测结果：58 个顶层 Go 用例全部 `PASS`，另 1 个进程崩溃夹具 `TestExecutionCrashWorker` 按设计 `SKIP`（退出码 `0`）；另已在 Scratch 容器内实跑 `internal/workspace` Linux 单测（含 16 MiB manifest 独立上限与 `NodeStatus.loaded_package_sha256` 上报），70 个顶层用例全部 `PASS`（1 个子进程故障夹具 `TestMutationCrashWorker` 按设计 `SKIP`）。
2. **多文件工程题包双阶段真容器自检（`node scripts/validate-problem.mjs`）**
   - 目标题包：`problems/sliding-window-rate-limiter/1.0.0`（多文件 `QuotaKey`/`Rule`/`SlidingWindow` + 分层 `AGENTS.md`）。
   - 阶段三归档实测结果（退出码 `0`，耗时 `65s`，本轮收口仅复跑 `--quick` 37 项 Node 题包静态契约未重跑该项真容器构建）：
     - 基础阶段（`base`）：参考解答 `9/9` 全过；初始模板预期失败 `7/9`；isolation / log-overflow / deadline 全过。
     - 挑战阶段（`challenge`）：参考解答 `14/14` 全过；初始模板预期失败 `12/14`；含热更新规则与 64 轮交错并发线性化测试。
3. **Playwright 浏览器端到端烟雾与认证边界测试**
   - `node scripts/test-tracer.mjs --smoke --identity --workspace`（退出码 `0`，**`3 passed`（18.6s）**）：动态子网隔离的 Postgres + Go Sandbox + Java Docker 容器 + Vite 生产预览 + Chromium（含 V69~V71 迁移与 `NodeStatus.loaded_package_sha256` 路由绑定），覆盖 `identity.spec.ts`（`3.8s`）与 `workspace.spec.ts` 草稿恢复（`6.9s`）/ 闭环评测（`16.8s`）。
   - `node scripts/test-tracer.mjs --identity --boundary-only`（退出码 `0`）：[`IdentityIT`](../backend/server/src/test/java/ai/codecraft/modules/identity/IdentityIT.java) **`22/22` PASS**（`0` 失败 / `0` 错误 / `0` 跳过，70.55s），覆盖会话鉴权、密码轮换与平台 Agent 绑定隔离。
4. **跨 JVM / Go 崩溃恢复与对账测试（阶段四归档基线；真实 Docker + PostgreSQL + Go 沙箱）**
   - `node scripts/test-tracer.mjs --workspace --boundary-only --copilot-recovery`（退出码 `0`，耗时 `250s`）：[`CopilotCrashRecoveryIT`](../backend/server/src/test/java/ai/codecraft/modules/copilot/service/CopilotCrashRecoveryIT.java) **`1/1` PASS**（115.2s，0 失败 / 0 错误 / 0 跳过），验证了审批挂起、原子写前后（`write-before` / `write-after`）崩溃、Steer 纠偏、流式部分持久化后崩溃（不重发未知模型调用）、抢占撤权以及 Java+Go 双重启停止并对账原 `executionInstanceId`（`STOPPED`）。
   - `node scripts/test-tracer.mjs --workspace --boundary-only --workspace-recovery-only`（退出码 `0`，耗时 `161s`）：[`WorkspaceCrashRecoveryIT`](../backend/server/src/test/java/ai/codecraft/modules/workspace/WorkspaceCrashRecoveryIT.java) **`1/1` PASS**（54.13s，0 失败 / 0 错误 / 0 跳过），验证了 `ApplyBatch` 与 `StartTest` 在 Java 进程硬崩溃重启后不重放副作用。
   - **修复机制边界声明**：恢复夹具通过 [`RecoveryChildRuntime.java`](../backend/server/src/test/java/ai/codecraft/testfixture/RecoveryChildRuntime.java) 在容器本地文件系统暂存 classpath 并在首次启动时轮询真实 PG `sandbox_node` 探活租约；唯一生产改动为 [`TestTaskStore.java#L128-L131`](../backend/server/src/main/java/ai/codecraft/modules/workspace/service/TestTaskStore.java#L128-L131) 在同一准入锁内将 `409 TEST_SLOT_BUSY` 判定前置到 `routing.admitExecution` 之前，未放宽任何超时、锁或 fail-closed 约束。

---

## 二、Tier 2：确定性回放评测与并发持久实证（PostgreSQL + Scripted Driver）

### 1. Copilot Loop 量化回放基准（[CopilotReplayEvalTest.java](../backend/server/src/test/java/ai/codecraft/modules/copilot/service/CopilotReplayEvalTest.java)）

> 数据源标记：`DETERMINISTIC_SCRIPTED_BASELINE`（见 [copilot-eval-report.json](../backend/server/target/copilot-eval-report.json) 与 [copilot-eval-report.md](../backend/server/target/copilot-eval-report.md)）。测量使用真实 Runner、ToolExecutor 与隔离 PostgreSQL 容器，包含数据库事务与调度耗时，**不含**外部 LLM 服务商网络耗时。

| 对比维度 | 对照 A（基准） | 对照 B（优化后） | 实测收益 | 口径边界 |
| --- | --- | --- | --- | --- |
| **并发只读窗口**（4 次读） | `A_serial`：5 轮 / 4 窗口 / 峰值并发 1<br>`wallMs = 2675 ms` / `21599 B` | `A_parallel`：2 轮 / 1 窗口 / 峰值并发 4<br>`wallMs = 968 ms` / `21431 B` | 耗时下降 **63.8%**，模型轮次 5 → 2 | 含本地 PG 与虚拟线程调度，无模型网络延迟 |
| **Explore 子任务委派**（3 路探索） | `B_main`：4 轮 / 3 窗口 / `1743 ms`<br>`parentRequestBytes = 16687 B` | `B_explore`：3 个只读子任务<br>`parentRequestBytes = 4005 B` | 父消息体积节省 **`12682 B`**（76.0%） | 仅对比最终父上下文请求字节数，不计子任务独立开销 |
| **Micro-Compaction 微压缩** | `beforeBytes = 4716` | `afterBytes = 3915` | 单轮投影节省 **`801 B`** | `CopilotContextPlannerTest#compareMicro` 规划器投影对比 |
| **恢复与 Steer 观测** | — | — | `askRounds = 3`、`revisedWritesApplied = 1`、`recoveredRevisionConflicts = 0`、`steerInterruptions = 1` | 当次脚本未构造滞后重读后的二次重试，故恢复冲突计数按 V68 规则准确为 0 |

### 2. PostgreSQL 并发闭环与快速回归门禁
- 临时隔离 PostgreSQL 容器实跑 `CopilotReplayEvalTest,PublicationConcurrencyTest`：**`2/2` 通过**（0 失败 / 0 错误 / 0 跳过；含完整 71 步 Flyway 迁移与并发 `Idempotency-Key` 竞态验证）。
- 临时隔离 PostgreSQL 容器实跑 Failsafe 持久化套件（71 步迁移）：`OutboxPersistenceIT` `4/4`、`CopilotPersistenceIT` `157/157`（跳过 `7` 项需 `COPILOT_LIVE_DEEPSEEK=1` 的在线模型测例）、`LlmProxyPersistenceIT` `12/12`、`ModelConnectionPersistenceIT` `32/32`（跳过 `2` 项需 `MODEL_ACCESS_LIVE_DEEPSEEK=1` 的在线模型测例）、`ProviderPresetRetirementIT` `1/1`、`SkillPersistenceIT` `1/1`、`CommandPersistenceIT` `14/14`、`ReportTimelinePersistenceIT` `2/2`、`WorkspaceRetentionPersistenceIT` `10/10`、`CopilotConversationMigrationIT` `1/1`、`CopilotExploreMigrationIT` `1/1`（0 失败 / 0 错误）。
- `verify.mjs --quick`（未配置 `COPILOT_IT_JDBC_URL` 时跳过上述 2 个外部 PG 测试）：覆盖 Java Checkstyle（`0` 违规）、单测与架构规则（`552` 项含 `2` 项跳过）、Go 宿主单测与 Linux 编译/`vet`、Node 发布契约测试（`37` 项）与前端 `typecheck` / `lint`（`0` error，`26` 既有 warning）/ `test:unit`（`89` 文件 `367` 项）。
- `PYTHON_PYTEST` 静态发布路径：通过 `V67` 数据库约束、`PublicationConcurrencyTest` 与 `AdminProblemStoreTest` 证明同一 `Idempotency-Key` 并发竞态与异包冲突（`409 IDEMPOTENCY_CONFLICT`）严格收敛，且对运行时启动保持 `EXECUTION_UNSUPPORTED`（fail-closed）。

---

## 三、Tier 3：前端静态检查、组件单测与集成测试

- 覆盖链路：
  - `npm --prefix frontend run typecheck`（`vue-tsc --noEmit`）与 `npm --prefix frontend run lint`（零 error；保留既有无关组件 26 条既有 warning 口径）
  - `npm --prefix frontend run test:unit`：含 `CatalogPage.test.ts`、`CopilotTelemetryCard.test.ts`、`AdminOperationsPage.test.ts`、`AppShell.test.ts`、`CopilotUsage.test.ts` 等全量组件与状态单测
  - `npm --prefix frontend run test:ui`、`npm --prefix frontend run test:integration` 与 `npm --prefix frontend run build`：基于可控 fixture / mock transport 与 Vue Router 驱动的前端交互验证
- 浏览器烟雾外未覆盖项：`/admin/operations` 页面交互通过 Vitest + `@vue/test-utils` 与 API contract 校验完成，未单独编写 Playwright 管理员运营端 E2E 用例；`copilot-browser` 需真实收费大模型凭证，按环境变量门控显式触发。
