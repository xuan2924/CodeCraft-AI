# 验证证据台账与诚实边界

本台账严格按 **运行环境真实度** 分三层记录本仓库的可复现证据，避免把替身/脚本测试混说为公网大模型或真容器实测。

---

## 层级说明

| 证据层级 | 真实组件 | 替身 / 不覆盖范围 | 核心命令 |
| --- | --- | --- | --- |
| **Tier 1：真 Docker 端到端** | Linux Docker 引擎、PostgreSQL、Go 沙箱、Java 主服务、Playwright 浏览器、Seccomp/Cgroups | 无外部公网模型（浏览烟雾用本地可控 fixture） | `test-execution.mjs`、`validate-problem.mjs`、`test-tracer.mjs` |
| **Tier 2：隔离真 PG + 脚本化模型驱动** | 完整 Spring 服务、MyBatis/Flyway 68 步迁移、Copilot 状态机、并发事务 | `ModelCallDriver` 为确定性脚本，不含公网 LLM 延迟与自然语言准确率 | `verify.mjs --quick`、`CopilotReplayEvalTest`、`PublicationConcurrencyTest` |
| **Tier 3：单测 / 前端组件 / 静态契约** | Vitest/JSDOM 组件、Vue Router/Query 状态、静态打包契约 | 网络通过 mock/stub，非真实前后端联调；`PYTHON_PYTEST` 无运行容器 | `npm --prefix frontend run test:*`、`problem-contract.test.mjs` |

---

## 一、Tier 1：真实 Docker 与浏览器端到端实证

1. **Go 沙箱安全与执行全套（`node scripts/test-execution.mjs`）**
   - 覆盖范围：容器隔离、`NetworkMode: "none"`、只读根文件系统、`CapDrop: ALL`、`no-new-privileges`、Seccomp、磁盘/tmpfs/PIDs/内存配额、日志截断（1 MiB 上限）、超时强杀（`exitCode=137`）、Egress 防 SSRF 白名单代理与孤儿容器清理。
   - 阶段三实测结果：58 个顶层 Go 用例全部 `PASS`（退出码 `0`，耗时 `108s`）。
2. **多文件工程题包双阶段真容器自检（`node scripts/validate-problem.mjs`）**
   - 目标题包：`problems/sliding-window-rate-limiter/1.0.0`（多文件 `QuotaKey`/`Rule`/`SlidingWindow` + 分层 `AGENTS.md`）。
   - 实测结果（退出码 `0`，耗时 `65s`）：
     - 基础阶段（`base`）：参考解答 `9/9` 全过；初始模板预期失败 `7/9`；isolation / log-overflow / deadline 全过。
     - 挑战阶段（`challenge`）：参考解答 `14/14` 全过；初始模板预期失败 `12/14`；含热更新规则与 64 轮交错并发线性化测试。
3. **Playwright 浏览器端到端烟雾测试（`node scripts/test-tracer.mjs --smoke --identity --workspace`）**
   - 真实链路：动态子网隔离的 Postgres + Go Sandbox + Java Docker 容器 + Vite 生产预览 + Chromium。
   - 最新实测结果（退出码 `0`）：**`3 passed`（17.0s）**
     - `identity.spec.ts`：管理员登录、强制改密、受邀注册与登出（`3.4s`）。
     - `workspace.spec.ts`（草稿恢复）：接管操作权后编辑代码、本地草稿即时更新、刷新恢复草稿不丢内容（`6.2s`）。
     - `workspace.spec.ts`（闭环评测）：提交参考解答并触发真沙箱运行基础测试，出现 `通过 · 启动修订 1` 与 `清理：SUCCEEDED / SUCCEEDED`，刷新页面后仍持久恢复结果（`15.3s`）。
4. **跨 JVM / Go 崩溃恢复与对账测试（真实 Docker + PostgreSQL + Go 沙箱）**
   - `node scripts/test-tracer.mjs --workspace --boundary-only --copilot-recovery`（退出码 `0`，耗时 `250s`）：[`CopilotCrashRecoveryIT`](../backend/server/src/test/java/ai/codecraft/modules/copilot/service/CopilotCrashRecoveryIT.java) **`1/1` PASS**（115.2s，0 失败 / 0 错误 / 0 跳过），验证了审批挂起、原子写前后（`write-before` / `write-after`）崩溃、Steer 纠偏、流式部分持久化后崩溃（不重发未知模型调用）、抢占撤权以及 Java+Go 双重启停止并对账原 `executionInstanceId`（`STOPPED`）。
   - `node scripts/test-tracer.mjs --workspace --boundary-only --workspace-recovery-only`（退出码 `0`，耗时 `161s`）：[`WorkspaceCrashRecoveryIT`](../backend/server/src/test/java/ai/codecraft/modules/workspace/WorkspaceCrashRecoveryIT.java) **`1/1` PASS**（54.13s，0 失败 / 0 错误 / 0 跳过），验证了 `ApplyBatch` 与 `StartTest` 在 Java 进程硬崩溃重启后不重放副作用。
   - **修复机制边界声明**：恢复夹具通过 [`RecoveryChildRuntime.java`](../backend/server/src/test/java/ai/codecraft/testfixture/RecoveryChildRuntime.java) 在容器本地文件系统暂存 classpath 并在首次启动时轮询真实 PG `sandbox_node` 探活租约；唯一生产改动为 [`TestTaskStore.java#L128-L131`](../backend/server/src/main/java/ai/codecraft/modules/workspace/service/TestTaskStore.java#L128-L131) 在同一准入锁内将 `409 TEST_SLOT_BUSY` 判定前置到 `routing.admitExecution` 之前，未放宽任何超时、锁或 fail-closed 约束。

---

## 二、Tier 2：确定性回放评测与并发持久实证（PostgreSQL + Scripted Driver）

### 1. Copilot Loop 量化回放基准（[CopilotReplayEvalTest.java](../backend/server/src/test/java/ai/codecraft/modules/copilot/service/CopilotReplayEvalTest.java)）

> 数据源标记：`DETERMINISTIC_SCRIPTED_BASELINE`（见 [copilot-eval-report.json](../backend/server/target/copilot-eval-report.json) 与 [copilot-eval-report.md](../backend/server/target/copilot-eval-report.md)）。测量使用真实 Runner、ToolExecutor 与隔离 PostgreSQL 容器，包含数据库事务与调度耗时，**不含**外部 LLM 服务商网络耗时。

| 对比维度 | 对照 A（基准） | 对照 B（优化后） | 实测收益 | 口径边界 |
| --- | --- | --- | --- | --- |
| **并发只读窗口**（4 次读） | `A_serial`：5 轮 / 4 窗口 / 峰值并发 1<br>`wallMs = 2891 ms` / `21599 B` | `A_parallel`：2 轮 / 1 窗口 / 峰值并发 4<br>`wallMs = 1073 ms` / `21431 B` | 耗时下降 **62.9%**，模型轮次 5 → 2 | 含本地 PG 与虚拟线程调度，无模型网络延迟 |
| **Explore 子任务委派**（3 路探索） | `B_main`：4 轮 / 3 窗口 / `1784 ms`<br>`parentRequestBytes = 16687 B` | `B_explore`：3 个只读子任务<br>`parentRequestBytes = 4005 B` | 父消息体积节省 **`12682 B`**（76.0%） | 仅对比最终父上下文请求字节数，不计子任务独立开销 |
| **Micro-Compaction 微压缩** | `beforeBytes = 4716` | `afterBytes = 3915` | 单轮投影节省 **`801 B`** | `CopilotContextPlannerTest#compareMicro` 规划器投影对比 |
| **恢复与 Steer 观测** | — | — | `askRounds = 3`、`revisedWritesApplied = 1`、`recoveredRevisionConflicts = 0`、`steerInterruptions = 1` | 当次脚本未构造滞后重读后的二次重试，故恢复冲突计数按 V68 规则准确为 0 |

### 2. PostgreSQL 并发闭环与快速回归门禁
- 临时隔离 PostgreSQL 容器实跑 `CopilotReplayEvalTest,PublicationConcurrencyTest`：**`2/2` 通过**（0 失败 / 0 错误 / 0 跳过，38.28s；含完整 68 步 Flyway 迁移与并发 `Idempotency-Key` 竞态验证）。
- `verify.mjs --quick`（未配置 `COPILOT_IT_JDBC_URL` 时跳过上述 2 个外部 PG 测试，配置后全量运行 550 项）：覆盖 Java Checkstyle、单测与架构规则、Go 编译/`vet`/快速单测、Node 发布契约测试（37 项）与前端 `typecheck` / `lint` / `test:unit`。
- `PYTHON_PYTEST` 静态发布路径：通过 `V67` 数据库约束、`PublicationConcurrencyTest` 与 `AdminProblemStoreTest` 证明同一 `Idempotency-Key` 并发竞态与异包冲突（`409 IDEMPOTENCY_CONFLICT`）严格收敛，且对运行时启动保持 `EXECUTION_UNSUPPORTED`（fail-closed）。

---

## 三、Tier 3：前端静态检查、组件单测与集成测试

- 覆盖链路：
  - `npm --prefix frontend run typecheck`（`vue-tsc --noEmit`）与 `npm --prefix frontend run lint`（零 error；保留既有无关组件 26 条既有 warning 口径）
  - `npm --prefix frontend run test:unit`：含 `CatalogPage.test.ts`、`CopilotTelemetryCard.test.ts`、`AdminOperationsPage.test.ts`、`AppShell.test.ts`、`CopilotUsage.test.ts` 等全量组件与状态单测
  - `npm --prefix frontend run test:ui`、`npm --prefix frontend run test:integration` 与 `npm --prefix frontend run build`：基于可控 fixture / mock transport 与 Vue Router 驱动的前端交互验证
- 浏览器烟雾外未覆盖项：`/admin/operations` 页面交互通过 Vitest + `@vue/test-utils` 与 API contract 校验完成，未单独编写 Playwright 管理员运营端 E2E 用例；`copilot-browser` 需真实收费大模型凭证，按环境变量门控显式触发。
