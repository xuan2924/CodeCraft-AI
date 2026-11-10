# CodeCraft-AI Agent Rules

## 项目与文档边界

面向 AI 结对编程面试的工程演练与评估平台，采用 Java 主后端 + Go 执行服务 + Vue Web IDE。

- 本文件维护全局协作与安全规则。
- 前后端 `AGENTS.md` 仅维护通用编码规范。
- 功能、协议、状态与部署方案在 `docs/design/` 维护。
- 待决内容留在设计文档中。
- 涉及待决项时，先确认，再实现。
- ADR 记录已作出的重要决策及取舍。
- `docs/draft.md`、研究报告和 PoC 仅作背景或证据，不作为有效规格。
- 扩展首期范围或变更已确认技术路线前，说明原因并获得用户确认。

## 按任务阅读

- 后端代码、数据或配置：先读 [后端规范](backend/AGENTS.md)。
- 前端页面、状态、网络或构建：先读 [前端规范](frontend/AGENTS.md)。
- 跨端任务：同时阅读前后端规范。
- 初始化、依赖选型、职责或部署边界：读 [架构与首期范围](docs/design/architecture.md)。
- 模型接入、工具循环、上下文或预算：读 [Copilot Loop](docs/design/copilot-loop.md)。
- 复议 Copilot 选型：同时读该设计关联的 ADR。
- 审批默认值、切换或处理：读 [审批设计](docs/design/approval.md)。
- 文件、Patch 应用、测试或沙箱：读 [工作区与执行](docs/design/workspace-execution.md)。
- REST/gRPC/SSE、鉴权或重连：读 [接口与事件](docs/design/api-events.md)。
- 面试流程、题目挑战或评分：读 [面试与评测](docs/design/interview-evaluation.md)。
- 编辑器、Diff、对话或日志交互：读 [Web IDE](docs/design/web-ide.md)。

## 实施与验证

- 实现前检查实际目录、依赖清单和脚本。
- 风险优先：涉及跨进程副作用、异步授权或持久化恢复时，实现前读 [风险优先实施](docs/agents/risk-first-delivery.md)，先验证失败与恢复切片，再扩展功能。
- 方案选型优先复用成熟组件、官方 SDK 和已验证的架构模式，先检查已声明依赖能否满足需求。自研通用能力前，说明现有方案的具体缺口与自研维护成本；仅为减少依赖不足以作为自研理由。
- 使用第三方 API 前核实其接口。
- 兼容性验证先核对官方说明与现有配置；仅在具体疑问仍未解决时做最小实验，先说明疑问与通过条件，得到结论即停止。业务测试仍按任务验收要求执行。
- 新增依赖或升级主要版本前，说明理由。
- 验证报告区分实际执行项与未验证项。
- 按独立功能或可验证改动进行小步、原子化提交；每个切片完成后继续推进剩余验收项。
- 整票交付：用户要求完成整票时，以全部验收项及约定的验证、审查完成为结束条件。仅在用户要求暂停或遇到无法自行解除的阻塞时提前交接，并说明阻塞证据、剩余项及所需外部动作。
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
