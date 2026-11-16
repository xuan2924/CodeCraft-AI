# 开发导航

当前已交付工程底座、Copilot 编码体验与完整面试流程（平台角色、暂停/挑战、回顾评测、中文 PDF、授权反馈）。运行从 [README](../README.md) 开始；功能细节以 `design/` 为现行说明，不从历史票的早期检查点推断今天的缺口。

## 接手阅读路径

不需要先读全部文档。先建立三个心智模型，再按任务深入：

1. [架构](design/architecture.md)：Java 拥有业务状态，Go 拥有文件/执行；浏览器不直接接触 Go、Docker 或模型凭证。
2. [术语](../GLOSSARY.md)：演练、登录会话、Copilot 对话/任务、正式工作区/草稿/测试副本不是同一个概念。
3. [任务与恢复](design/task-event-recovery.md)：受理、执行、业务终态、执行静默与清理分别表示什么；未知副作用必须核对。

随后按改动选择入口：

| 要做什么 | 先读/先看 |
|---|---|
| 启动、生成、测试 | 根 README；[工具链](design/engineering-toolchain.md#验证命令契约)；`compose.yaml`、`scripts/verify.mjs` |
| 登录/权限/重连 | [接口与事件](design/api-events.md)；`identity/`、`frontend/src/api/` |
| 文件/执行/保留 | [工作区与执行](design/workspace-execution.md)；`WorkspaceAiOperationService`、`StorageCapacityService/Store`、`backend/proto/workspace.proto` |
| Copilot 工具、审批或恢复 | [Loop](design/copilot-loop.md)、[审批](design/approval.md)；`CopilotTaskService → CopilotTaskStore`、`CopilotCoordinator → CopilotRunner` |
| 模型/Skill | [模型接入](design/model-access.md)、[Skill](design/skills.md)；`modelaccess/`、`skills/` |
| 面试/挑战/报告 | [面试与评测](design/interview-evaluation.md)；`platformagent/`、`ExercisePauseStore`、`ExerciseChallengeStore`、`ExerciseReportStore` |
| Web IDE | [Web IDE](design/web-ide.md)；`routes.ts`、`api/useCommand.ts`、`WorkspacePage.vue`、`useExerciseTimer.ts`、`CopilotConversations.vue` |
| 为什么这么设计 | 按疑问查下方 ADR；完整考古见 [历史复盘](research/history-and-simplification.md)，不是日常必读 |

### 容易误判的代码边界

- `Service` 常在事务外读取文件/计量，`Store` 经 Spring 代理做短事务授权和持久化。不要因类短就合并成一个长事务或同类自调用。
- `revision` 标识文件内容；`stateVersion` 排序业务快照；`generation/credential` 标识页面操作权；`gateEpoch` 拒绝旧授权副作用；`hostEpoch` 拒绝旧执行宿主观察；`meterEpoch` 关联容量计量。它们不能合并成一个通用 version。
- `CopilotConversations` 是会话容器，内部 `CopilotPanel` 展示一个会话；`useCommand` 保留结果未知的原始意图键，不是普通“失败重试”工具。
- 各后台 Coordinator 负责发现/恢复，模型 Worker 使用有界执行池；调度发现和实际执行并非同一线程。调度并行度会改变排序和竞争，不能只改线程数而不核对。
- 首题目录仅保留 `problems/cart-total/1.1.0`；基础与挑战分别验证，`initial/` 必须相同。合法编辑根固定为两个 Java 源码目录，这是当前题型限制，不是任意仓库产品。

## 决策索引

| 决策 | 当前结论 |
|---|---|
| [ADR 0001](adr/0001-copilot-application-owned-loop.md) | LangChain4j 模型 API + 应用自管 Loop；不用自动循环代替持久工具批次 |
| [0002](adr/0002-java-go-shared-linux-clock.md)、[0003](adr/0003-accept-shared-system-clock-risk.md) | Java/Go 同 Linux 时钟域；接受系统校时风险，不做专项异常保护 |
| [0004](adr/0004-model-url-https-only.md) | HTTPS、不跟随重定向；不做内网/DNS 目标限制，剩余 SSRF 风险明确保留 |
| [0005](adr/0005-live-model-connection-updates.md)、[0008](adr/0008-model-revisions-and-history-boundaries.md) | 显式选模/应用修订，完整工具批次之后生效；保留原协议证据 |
| [0006](adr/0006-exercise-storage-reservations.md)、[0007](adr/0007-owner-recovery-stop-and-end.md) | 512 MiB 单场统一预算、256 MiB 收尾保护；本人显式恢复停止/结束 |

## 文档分工与历史入口

- `AGENTS.md`：协作与通用规范；`GLOSSARY.md`：概念；`design/`：当前行为；`adr/`：重要取舍。功能状态不能由概念或 ADR 的存在推导。
- `.scratch/` 的规格和票据保留范围决定、验收及返工原因；不是新人阅读前置。`Status` 是分诊，完成看验收及最终 Comments。
- [validation](validation/README.md) 是历史验证证据，旧“尚未实现”不构成当前欠账；[research](research/model-provider-matrix.md) 的外部事实有核验日期，不能当永久兼容保证。
- 一次性 PoC 和旧 UI 参考图已从当前树移除，仍可从 `c185c65` 查看。设计结论留在 ADR/现行设计，不复制一份归档目录。

当前部署限可信受邀用户的本机 Linux Docker。公开部署、任意恶意代码判题、备份/灾难恢复和生产 SLA 不在现有保证内。
