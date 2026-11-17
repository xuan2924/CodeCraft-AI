# 04: 有状态智能面试官闭环（多轮记忆、分主题渐进提示与代码证据答辩）

**What to build:** 将面试官从单轮无状态问答升级为具备多轮对话记忆、实时演练态势感知、按主题独立递进的三级提示锦囊（`L1 -> L2 -> L3`）以及基于有界代码与变更证据的 `ACTIVE` 阶段代码设计拷问（`DEFENSE`），并在工作台面试官面板提供直观的模式与主题切换交互。

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] 扩展 `interviewer_task` 持久化模型与 API 契约，正交区分生命周期状态（`phase: READY|ACTIVE`）、题目阶段（`stage: BASE|CHALLENGE`）、互动模式（`mode: CLARIFY|HINT|DEFENSE`，默认 `CLARIFY`）、提示主题（`topic`，默认 `general`）与提示等级（`hintLevel: 1..3`），保持仅传 `{text}` 的请求向后兼容。
- [ ] `InterviewerWorker` 在派发模型请求时注入本场演练最近最多 8 轮已完成的问答历史，以及当前只读演练态势（当前阶段 `stage`、工作区版本、最近一次受控测试结果摘要）。
- [ ] 当 `mode="HINT"` 时，按 `(exerciseId, topic)` 独立计算已成功发布（`SUCCEEDED`）的提示次数并分配本次等级（`L1 思路方向 -> L2 模块/边界定位 -> L3 关键逻辑提示`，上限为 3）；失败（`FAILED`）或幂等重放不升级提示等级。
- [ ] 当 `mode="DEFENSE"`（在 `ACTIVE` 阶段可用）时，在数据库事务外读取当前已保存的可编辑源码有界节选与最近 `workspace_change` 变更摘要作为不可信证据注入提示词，使面试官针对候选人真实代码实现发起架构与边界追问。
- [ ] 前端 `InterviewerPanel.vue` 新增互动模式选择胶囊（`需求澄清` / `提示锦囊` / `代码拷问`）、`topic` 输入/快捷标签及历史消息中的模式与 `L1~L3` 等级徽标展示，通过前后端单元与组件测试。

## Comments
