# 一次性原型：历史证据，不是正式服务

以下目录保留早期问题探索和选型实验，不属于当前应用的构建/部署入口，也不是下一阶段待开发清单。

| 目录 | 当时的问题 | 当前有效入口 |
|---|---|---|
| `agent-loop-poc/` | 比较 AI Services、应用自管与 agentic 包装执行器，以及固定批次恢复 | [ADR 0001](../../docs/adr/0001-copilot-application-owned-loop.md)、[Copilot Loop](../../docs/design/copilot-loop.md) |
| `workspace-consistency/` | Linux 文件发布、版本与恢复策略 | [工作区与执行](../../docs/design/workspace-execution.md)及正式 `backend/sandbox` |
| `task-protocol/` | 任务、栅栏、幂等与恢复状态模型 | [任务与恢复协议](../../docs/design/task-event-recovery.md)及正式 Java/Go 实现 |

原型中的固定批次、假模型、依赖版本、诊断权限与未验证项仅描述当时实验。它们不能限制正式工具循环轮次、代替真实 SDK/沙箱验证、要求保留 Docker socket 权限，或触发无需求的技术路线复议。正式依赖以 `backend/server/pom.xml`、`backend/sandbox/go.mod` 为准，正式运行见 [README](../../README.md)。

保留源码和历史输出以解释取舍；不要求重复运行或新建归档分支才能继续开发。若确需重跑原型，先辨认其可能的网络/收费/容器副作用，不能混入默认 quick 或生产启动。
