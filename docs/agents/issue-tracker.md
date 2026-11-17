# 本地任务管理约定

仅在需要多步拆分与跨轮次追踪时使用 `.scratch/<feature>/`：

- `spec.md`：只写阶段目标、核心边界与验收标准。
- `issues/<NN>-<slug>.md`：按可独立交付的功能切片建票，**禁止按 Controller/Service/UI 机械拆层**；`Status:` 使用 [分诊标签](triage-labels.md)，`Blocked by:` 标明前置依赖。
- 关键决策与最终验证结论简要记在对应票的 `## Comments`，不记每轮重跑的流水账；阶段收口后把长期价值沉淀到 `docs/evolution/` 与 `docs/design/`。
