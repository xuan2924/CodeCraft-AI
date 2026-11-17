# CodeCraft-AI 协作规范

面向 AI 结对编程面试的工程演练与评估平台：Java 主后端 + Go 执行沙箱 + Vue Web IDE。

## 阅读导航

- 架构与源码入口：[开发导读](docs/README.md)
- 核心概念对照：[术语表](GLOSSARY.md)
- 演进与踩坑复盘：`docs/evolution/01~05`
- 模块设计专题：`docs/design/`
- 重要架构决策：`docs/adr/`
- 编码细则：[后端规范](backend/AGENTS.md)、[前端规范](frontend/AGENTS.md)

## 开发与交付原则

1. **先对齐边界再动手**：涉及权限、存储配额、状态机或跨服务契约的改动，先核对对应 `docs/design/` 与 `docs/adr/`。未明确的重大取舍先确认，普通接线自行决定。
2. **风险优先与不缩水**：授权校验、幂等、未知副作用对账、资源清理等核心逻辑必须落地，并用确定性测试（可控时钟、替身/注入故障、临时目录或测试库）覆盖正常与关键失败分支。
3. **反过度工程**：优先复用现有模块与成熟依赖，不提前建设通用框架、无用接口包装或仅为拆行数的短类；避免大段复制粘贴逻辑。
4. **验证与汇报诚实**：
   - 开发中运行受影响模块的编译、静态检查与单测；完整收口运行 `node scripts/verify.mjs --quick`。
   - 需要跨服务或真实 Docker/浏览器证据时按需运行对应脚本（如 `test-execution.mjs`、`test-tracer.mjs`），不编造未运行的验证结果，不把替身测试说成真实环境实测。
5. **文档同步**：协议或架构变更时同步精简更新对应的 `docs/design/` 或 `docs/adr/`，保持文档简短、讲清因果、不写防御性空话。

## 安全与提交约定

- 本地密钥与敏感配置只放未跟踪的 `.env`，禁止写入代码、命令日志或前端配置。
- 提交信息格式：`<type>: <中文描述>`（`feat`、`fix`、`docs`、`refactor`、`test`、`chore`）。

## 协作辅助文档

- 领域约定：[领域文档](docs/agents/domain.md)
- 风险顺序：[风险优先实施](docs/agents/risk-first-delivery.md)
- 任务约定：[任务管理](docs/agents/issue-tracker.md) 与 [分诊标签](docs/agents/triage-labels.md)
