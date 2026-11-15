# 当前文档与开发方向

## 当前交付边界

第一阶段工程底座与第二阶段 Copilot 已完成约定验收。运行入口在 [根 README](../README.md)，第二阶段完整证据在 [P2-22](../.scratch/copilot-loop/issues/22-stage-acceptance.md)。

- 已交付：账号、题目发布、工作区、隔离测试、恢复/冻结/保留删除，以及 Copilot 工具循环、ASK/AUTO、steer、压缩、个人 Skill 与用量费用。
- 支持声明：DeepSeek Chat OFF/ON、Responses OFF、Anthropic Messages OFF；自定义连接只承诺配置、安全、检查与未知价格契约。OpenAI/GLM/Kimi 三家已由用户移出第二阶段目标，既有入口不是兼容承诺。详见 [矩阵](research/model-provider-matrix.md)。
- 尚未实现：完整面试 FSM、面试官、挑战、回顾、AI 报告、平台 Agent 配置与授权反馈。它们属于第三阶段，不是第二阶段遗留缺陷，也不因文档整理而获得实施授权。
- 当前部署仅限可信受邀用户的本机 Linux Docker 环境；公开部署、任意恶意代码判题、灾难恢复与生产 SLA 没有随阶段验收获得保证。

## 文档分工

| 资料 | 用途 | 不应如何使用 |
|---|---|---|
| [AGENTS.md](../AGENTS.md) | 协作、安全与验收规则 | 不在功能票中另设更严的默认重型回归 |
| [GLOSSARY.md](../GLOSSARY.md) | 领域术语 | 不把术语存在当作功能已实现 |
| [架构](design/architecture.md)及 `design/` 专题 | 当前有效行为与第三阶段待决边界 | 不把已实现内容重新列为待开发，也不把未来设计当现有能力 |
| `adr/` | 已确认决策、取舍及后续补充 | 不因年代较早而删除仍有效的边界 |
| [第二阶段规格](../.scratch/copilot-loop/spec.md)与 issues | 范围、逐票验收和执行证据 | `Status` 是分诊；完成看验收清单、`Acceptance` 及最终记录 |
| [research/](research/model-provider-matrix.md) | 带核验日期的外部事实和兼容性证据 | 不把目录、价格或未验证组合当永久事实 |
| [validation/](validation/README.md) | 历史验证环境、失败与当时结果 | 不把早期“未完成”当当前待办或新任务的回归清单 |
| [一次性原型](../backend/prototypes/README.md) | 解释选型、保留实验依据 | 不继承其依赖、运行架构或样例限制到正式服务 |

发现当前设计、ADR 与实现冲突时应核对具体决定和证据；不能仅凭代码已存在就覆盖已确认产品行为。

## 有效决策与已被取代的理解

| 决策 | 当前有效结论 | 已失效的旧理解 |
|---|---|---|
| [ADR 0001](adr/0001-copilot-application-owned-loop.md) | LangChain4j 模型 API + 应用自管 Loop | 原型没测过故障 ≠ 正式服务仍没做恢复；不重启 agentic 选型 |
| [ADR 0002](adr/0002-java-go-shared-linux-clock.md) + [0003](adr/0003-accept-shared-system-clock-risk.md) | Java/Go 同一 Linux 时钟域，接受系统校时风险 | Windows JVM + Linux Go 不再是跨端截止基线；不建设专项时钟跳变保护，不加授权宽限 |
| [ADR 0004](adr/0004-model-url-https-only.md) | 合法 HTTPS、不做 IP/DNS/内网目标限制；后续已明确禁止重定向 | “只允许公网 HTTPS”和“重定向待确认”不再有效；SSRF 剩余风险仍在 |
| [ADR 0005](adr/0005-live-model-connection-updates.md) + [0008](adr/0008-model-revisions-and-history-boundaries.md) | 显式选模/应用修订，完整批次后生效；不兼容历史转为有来源文本，保留原证据 | 编辑个人配置自动跟随、整场固定 Copilot 模型、候选 Key 检查通过才替换均非现行规则 |
| [ADR 0006](adr/0006-exercise-storage-reservations.md) | 单场 512 MiB 统一账本、256 MiB 收尾保留 | 两套 AI/工作区额度、192 MiB 收尾旧值、全局固定磁盘水位均非当前政策 |
| [ADR 0007](adr/0007-owner-recovery-stop-and-end.md) | 本人显式恢复停止/结束，不授予普通写权限 | 无额度时无限免费接管，或只等待到期代替安全终止，均不采用 |

0002/0005 仍然有效，只是其中的专项时钟保护和编辑生效待决项分别由 0003/0008 取代或补齐；不能把整份 ADR 标成失效。

## 下一阶段的设计入口

[面试与评测](design/interview-evaluation.md)保留已确认产品行为及真正待决项：完整 FSM、评价维度/证据访问、回顾与报告调度、失败处理、平台配置版本和授权反馈。开始实现前应定界这些依赖，不重新建设已交付的账号、题目发布、文件/测试恢复或模型连接模块，也不自行扩大供应商与部署范围。

[审批](design/approval.md)、[工作区与执行](design/workspace-execution.md)、[接口与事件](design/api-events.md)、[模型接入](design/model-access.md)、[Skill](design/skills.md)是应复用的现有边界。平台 Skill 的发布测试集尚未设计，不等于个人 Skill 管理未完成。
