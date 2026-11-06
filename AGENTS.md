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
- 优先使用已声明的依赖。
- 使用第三方 API 前核实其接口。
- 新增依赖或升级主要版本前，说明理由。
- 验证报告区分实际执行项与未验证项。
- 按独立功能或可验证改动进行小步、原子化提交。
- 提交信息使用中文。

## 安全

- 本地敏感配置放在未跟踪的 `.env` 中。

## Agent skills

### Issue tracker

规格与任务使用本地 Markdown；创建、读取或拆分任务前，读 `docs/agents/issue-tracker.md`。

### Triage labels

任务分诊使用默认五类标签；分诊前读 `docs/agents/triage-labels.md`。

### Domain docs

采用单一领域上下文；探索领域概念或记录决策前，读 `docs/agents/domain.md`。
