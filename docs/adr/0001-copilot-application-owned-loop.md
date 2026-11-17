---
status: accepted
---

# ADR-0001：Copilot 采用 LangChain4j 模型 API + 应用自管循环

## 背景

Copilot 需要支持长时人工审批暂停（`ASK` 模式）、运行中途方向纠偏（`steer`）、多文件修改基线校验、服务重启恢复以及工具调用/结果精确配对。选型前完成了三轮官方能力调研与 PoC 实验。

## 备选方案

- **LangChain4j AI Services 自动循环**：支持自定义执行器实现基础审批，但原循环的透明跨进程状态恢复成本高、可控性弱。
- **LangChain4j `agentic` 实验性工作流**：支持在审批点跨 JVM 恢复，但实测仍需业务侧自行维护工具批次、游标与消息配对，同时还要承担实验性 API 的集成复杂度。
- **其他 Agent SDK（如 Spring AI / Pi）**：不额外引入新语言运行时，保持 Java 技术栈统一。

## 决策与代价

采用 **LangChain4j 仅做模型协议与流式适配，Java 应用层自管 Copilot Loop**。

- **收益**：完全掌握工具批次顺序、安全点 `steer` 插入、审批挂起释放 Worker 线程、幂等重放与未知结果对账的控制权。
- **代价**：团队自行承担流式分片、消息配对、死循环防线与重启恢复的正确性实现。
- **复议条件**：当业务出现大量通用跨天长等待工作流，或框架稳定版能原生接管相同故障恢复粒度时重新评估。

## 关键源码

- `CopilotCoordinator.java`、`CopilotRunner.java`、`CopilotToolExecutor.java`
- 设计详情：[Copilot Loop 与工具](../design/copilot-loop-and-tools.md)
