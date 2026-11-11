# CodeCraft-AI Agent Rules

## 项目与文档边界

面向 AI 结对编程面试的工程演练与评估平台，采用 Java 主后端 + Go 执行服务 + Vue Web IDE。

- 本文件维护全局协作与安全规则。
- 前后端 `AGENTS.md` 仅维护通用编码规范。
- 功能、协议、状态与部署方案在 `docs/design/` 维护。
- 待决内容留在设计文档中。
- 涉及待决项时，先确认，再实现。
- ADR 记录已作出的重要决策及取舍。

## 按任务阅读

- 后端代码、数据或配置：先读 [后端规范](backend/AGENTS.md)。
- 前端页面、状态、网络或构建：先读 [前端规范](frontend/AGENTS.md)。
- 初始化、依赖选型、职责或部署边界：读 [架构与首期范围](docs/design/architecture.md)。
- 模型接入、工具循环、上下文或预算：读 [Copilot Loop](docs/design/copilot-loop.md)。
- 复议 Copilot 选型：同时读该设计关联的 ADR。
- 审批默认值、切换或处理：读 [审批设计](docs/design/approval.md)。
- 文件、Patch 应用、测试或沙箱：读 [工作区与执行](docs/design/workspace-execution.md)。
- REST/gRPC/SSE、鉴权或重连：读 [接口与事件](docs/design/api-events.md)。
- 面试流程、题目挑战或评分：读 [面试与评测](docs/design/interview-evaluation.md)。
- 编辑器、Diff、对话或日志交互：读 [Web IDE](docs/design/web-ide.md)。

## 验收责任

- **L1 · Dev Done（Agent 负责）**：实现约定功能，修复已知代码缺陷；类型/编译检查、语法与 Lint 通过，核心业务逻辑自动化单测通过，单测反馈回路控制在 10 秒内。命令以项目配置为准，Vue 使用 `vue-tsc --noEmit`。
- **L2 · QA / Functional Done（按需，由人工或 CI 负责）**：需要另行安排的 UI 交互、复杂端到端及真实环境验证。责任分层不意味着每项功能都必须安排 L2；仅交接已明确安排的验证，不自动生成“待 QA”欠账，也不以此阻塞 Dev Done。
- **L3 · Production / Reliability Done（运维与监控负责）**：长期稳定性、高并发压测及 SLA 保证。Agent 不生成此类验收要求或完成承诺。
- **实现不缩水**：恢复、授权、限流、清理重试等票内逻辑仍须实现，以快速、确定性的测试覆盖核心正常与失败分支。分别报告功能缺口与实际测试结果，不将“未做复杂验证”等同于“功能未完成”。
- **验证按需**：数据库崩溃、Java/Go 进程强杀、跨服务故障矩阵和全套真实回归不默认安排，也不自动转为 QA 待办。仅在用户明确要求，或具体问题确需复现时做对应的最小验证，得到结论即停止。保留、到期、重试等时间策略使用可控时钟测试，不通过实际等待分钟、小时或天数验收。

## 实施与验证

- 风险优先：涉及跨进程副作用、异步授权或持久化恢复时，实现前读 [风险优先实施](docs/agents/risk-first-delivery.md)，先实现高风险状态与恢复逻辑，以 L1 单测覆盖，再扩展功能。
- 方案选型优先复用成熟组件、官方 SDK 和已验证的架构模式，先检查已声明依赖能否满足需求。自研通用能力前，说明现有方案的具体缺口与自研维护成本；仅为减少依赖不足以作为自研理由。
- 交付优先：已确认的范围和验证边界直接执行，仅待决项、范围变更或无法解除的阻塞需要确认。记录集中在现有票据中，简述实现结果、审查结论和实际验证；仅协议或使用方式变化时同步必要文档，不逐轮新增过程报告。
- 提交信息采用 `<type>: <中文描述>` 格式，按改动选择 `feat`、`fix`、`docs`、`refactor`、`test` 或 `chore`，例如 `feat: 新增工作区保存功能`。

## 安全

- 本地敏感配置放在未跟踪的 `.env` 中。

## Agent skills

### Issue tracker

规格与任务使用本地 Markdown；创建、读取或拆分任务前，读 `docs/agents/issue-tracker.md`。

### Triage labels

任务分诊使用默认五类标签；分诊前读 `docs/agents/triage-labels.md`。

### Domain docs

采用单一领域上下文；探索领域概念或记录决策前，读 `docs/agents/domain.md`。
