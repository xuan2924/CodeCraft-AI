# 开发导读与面试复习地图

这份导读专为**快速读懂代码**与**应对面试技术深挖**整理，按“架构全景 → 演进脉络 → 技术专题 → 核心源码”的顺序阅读即可，半小时即可建立完整心智模型。

---

## 一、建议阅读路线

1. **先花 5 分钟对齐概念与全局边界**：
   - [术语表](../GLOSSARY.md)：理清 `演练 / 任务 / 修订 / 栅栏 Epoch` 的边界。
   - [系统架构与职责边界](design/architecture.md)：理解 Java、Go、Vue 三端如何各司其职，为什么不拆微服务。
2. **再花 10 分钟顺一遍项目是怎么演进的**（按阶段拆分的 5 张短卡片）：
   - [01 工程底座与跨端沙箱](evolution/01-foundation.md)
   - [02 持久 Copilot 循环](evolution/02-copilot-loop.md)
   - [03 编码体验扩展](evolution/03-agent-experience.md)
   - [04 完整面试流程与 UI 重塑](evolution/04-interview-ui.md)
   - [05 Pi 极简工具链与有状态深度升级](evolution/05-minimal-tools-depth.md)
3. **快速上手演示与面试复盘（新入口）**：
   - [面试速通图谱](interview-mastery-map.md)：3 天路线 + 6 个高频深挖卡片 + 诚实边界。
   - [实操演示手册](cursor-demo-playbook.md)：类 Cursor 编码体验与 `/admin/operations` 沙箱发布动线。
   - [验证证据台账](verification-evidence-ledger.md)：严格区分真 Docker、PG+脚本基准、前端单测的三层实证。
4. **按面试深挖方向或准备修改的模块精读对应专题与 ADR**。

---

## 二、面试高频深挖索引（场景 / 决策 / 源码直达）

| 面试官可能深挖的问题 | 核心看哪篇文档 | 对应关键源码 |
|---|---|---|
| **为什么 Copilot 不用 LangChain4j AI Services 自动循环而选择自管 Loop？** | [ADR-0001](adr/0001-copilot-application-owned-loop.md)、[Copilot Loop 与工具](design/copilot-loop-and-tools.md) | `CopilotCoordinator.java`、`CopilotRunner.java`、`CopilotToolExecutor.java` |
| **如何防止多页面、AI、Bash 和手动保存把代码互相覆盖？** | [工作区与沙箱](design/workspace-and-sandbox.md)、[任务恢复与实时事件](design/task-recovery-and-realtime.md) | `mutation_linux.go`、`WorkspaceWriteService.java`、`WorkspaceAiOperationService.java` |
| **Windows 本地开发 + Docker Linux 容器时遇到过什么跨端坑？** | [01 底座演进](evolution/01-foundation.md)、[ADR-0002](adr/0002-java-go-shared-linux-clock.md)、[ADR-0003](adr/0003-accept-shared-system-clock-risk.md) | `compose.yaml`、`PreparationReceiptValidator.java` |
| **服务重启或 SSE 断线重连后，怎么保证已扣费的 LLM 调用不重复发、未完工具不错乱？** | [任务恢复与实时事件](design/task-recovery-and-realtime.md) | `CopilotTaskStore.java`、`TaskEventTransport.java`、`TransientTaskStreamHub.java` |
| **数据库提交与消息发布怎样避免漏任务，重复消息为什么不会重复收费？** | [任务恢复与实时事件](design/task-recovery-and-realtime.md) | `OutboxStore.java`、`OutboxRelay.java`、`WorkspaceOutboxHandler.java` |
| **多个 Go 节点失联时为何不能直接换节点重跑？** | [工作区与沙箱](design/workspace-and-sandbox.md) | `SandboxRoutingStore.java`、`SandboxClients.java`、`node_linux.go` |
| **流式输出写放大（PartialCapture）和空轮询是怎么发现并治理的？** | [05 深度升级](evolution/05-minimal-tools-depth.md)、[任务恢复与实时事件](design/task-recovery-and-realtime.md) | `CopilotPartialCapture.java`、`TransientTaskStreamHub.java`、`TaskWakeupPublisher.java` |
| **沙箱执行怎么防越权、防符号链接、隔离 Challenge 隐藏测试？** | [工作区与沙箱](design/workspace-and-sandbox.md) | `runner_linux.go`、`testcopy_linux.go`、`ProblemPublicationReader.java` |
| **512 MiB 单场存储预算怎么跨 Java Postgres 和 Go 磁盘协同计量？** | [ADR-0006](adr/0006-exercise-storage-reservations.md)、[ADR-0007](adr/0007-owner-recovery-stop-and-end.md) | `StorageCapacityService.java`、`StorageCapacityStore.java`、`capacity_linux.go` |
| **用户 API Key 怎么安全保存？不信任内网限制时怎么控 SSRF？** | [模型接入与安全](design/model-access-and-security.md)、[ADR-0004](adr/0004-model-url-https-only.md) | `ModelCredentialCipher.java`、`ModelConnectionValidator.java`、`ModelCredentialRotationCoordinator.java` |
| **如何防止模型编造 `expected_revision`、怎么把工具改造得像 Pi 一样流畅？** | [05 深度升级](evolution/05-minimal-tools-depth.md)、[Copilot Loop 与工具](design/copilot-loop-and-tools.md) | `CopilotToolCatalog.java`、`CopilotReadTools.java`、`CopilotProposalPreparation.java` |
| **面试官怎么做到不泄露挑战题目、分主题渐进提示和基于代码证据追问？** | [面试与评测](design/interview-evaluation.md) | `InterviewerWorker.java`、`InterviewerContextCollector.java`、`ExercisePauseStore.java` |

| **面向候选人与管理员：怎么用这套类 Cursor 工作台与沙箱题库系统？** | [实操演示手册](cursor-demo-playbook.md) | `WorkspacePage.vue`、`AdminOperationsPage.vue`、`validate-problem.mjs` |
| **面向面试官与本人复盘：3 天速通与核心架构深挖图谱** | [面试速通图谱](interview-mastery-map.md)、[验证证据台账](verification-evidence-ledger.md) | `CopilotRunner.java`、`CopilotContextPlanner.java`、`CopilotReplayEvalTest.java` |

---

## 三、全栈核心源码地图

### 1. Java 主后端（`backend/server/src/main/java/ai/codecraft/`）

| 目录 / 模块 | 职责说明 | 核心入口文件（先看这几个） |
|---|---|---|
| `modules/identity` | 登录会话、图片验证码、CSRF、密码哈希与会话撤销 | `IdentityService.java`、`CaptchaService.java`、`IdentityRevocationBarrier.java` |
| `modules/workspace` | 演练生命周期、暂停/挑战/冻结、受控测试、Dev PTY、面试官与报告 | `ExerciseService.java`、`WorkspaceAiOperationService.java`、`TerminalGateway.java`、`TestTaskService.java`、`InterviewerWorker.java`、`ReportTimelineReader.java` |
| `modules/copilot` | 自管 Loop、工具目录、审批、steer/follow-up/BTW/子任务、压缩 | `CopilotCoordinator.java`、`CopilotRunner.java`、`CopilotToolCatalog.java`、`CopilotToolExecutor.java`、`CopilotProposalService.java` |
| `modules/modelaccess` | 三协议 SDK 适配、凭证加解密轮换、用量计费与 Host LLM Proxy | `ModelInvocationService.java`、`ModelSdkClientFactory.java`、`ModelCredentialCipher.java`、`ModelCostCalculator.java`、`LlmProxyController.java` |
| `modules/platformagent` | 管理员面试官/评测官配置、版本检查、发布与演练绑定 | `PlatformAgentService.java`、`PlatformCheckWorker.java`、`PlatformBindingService.java` |
| `modules/skills` | 个人 Skill 包解析（YAML 安全检查）、版本快照与按需读取 | `CopilotSkillService.java`、`CopilotSkillContext.java` |
| `infrastructure` | Redis 实时提示、事务唤醒、Outbox/Rabbit 分级任务、PG/Redis 限流 | `TaskRealtimeBus.java`、`OutboxRelay.java`、`OutboxStore.java`、`RabbitTaskConfiguration.java` |

### 2. Go 执行服务（`backend/sandbox/internal/`）

| 目录 | 职责说明 | 核心入口文件 |
|---|---|---|
| `workspace/` | 不可变 `commit-*` 文件树、路径/符号链接防御、多节点卷身份、栅栏、冻结与计量 | `service_linux.go`、`node_linux.go`、`mutation_linux.go`、`gate_linux.go`、`freeze_linux.go`、`capacity_linux.go` |
| `execution/` | Docker 受限容器运行测试、Egress 白名单代理与 Dev PTY、孤儿清理与恢复 | `runner_linux.go`、`network_linux.go`、`terminal_linux.go`、`stop_linux.go`、`recovery_linux.go`、`orphans_linux.go` |

### 3. 前端 Web IDE（`frontend/src/`）

| 目录 / 文件 | 职责说明 | 核心入口文件 |
|---|---|---|
| 全局壳层与网络 | 路由表、统一请求与原键恢复、SSE 订阅、主题 Token | `routes.ts`、`api/request.ts`、`api/useCommand.ts`、`workspace/useTaskStream.ts`、`shell/AppShell.vue` |
| `workspace/` | 三栏工作台、Monaco 编辑、xterm Dev PTY、草稿、倒计时、面试官与报告 | `WorkspacePage.vue`、`InteractivePtyTerminal.vue`、`terminalApi.ts`、`drafts.ts`、`InterviewerPanel.vue`、`ReportPanel.vue` |
| `copilot/` | 多会话容器、工具结果卡片、Diff 审批、Steer/Follow-up/BTW/子任务 | `CopilotConversations.vue`、`CopilotPanel.vue`、`CopilotToolResult.vue`、`CopilotProposals.vue` |
| `modelaccess/` & `platformagent/` | 个人模型管理与演练切换、管理员平台角色发布 | `ModelSettingsPage.vue`、`ExerciseModelPicker.vue`、`PlatformAgentsPage.vue` |
