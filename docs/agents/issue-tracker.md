# 本地任务管理

规格与任务保存在 `.scratch/`，按功能组织：

- 规格：`.scratch/<feature>/spec.md`；描述阶段目标、范围和完成标准，不维护当前实现进度。
- 任务：`.scratch/<feature>/issues/<NN>-<slug>.md`
- 每个任务独立文件，从 `01` 编号，阻塞项优先。
- `Status:` 记录分诊状态，取值见 `triage-labels.md`。
- `Blocked by:` 记录阻塞任务编号；无依赖明确写 None。
- 决定、阻塞及交付结论追加到任务的 `## Comments`，保留历史；日常测试重跑与内部步骤不逐轮记流水账。

技能要求“发布”时创建相应文件；要求“获取任务”时读取指定文件。
实现完成情况由任务验收清单及验证记录表达，不将分诊标签当作完成证明。前置实现任务的“完成/全部验收”默认指根规范的 L1；验证与审查节奏也以根规范为准。拆票按可独立交付的行为或真实依赖边界组织，不按内部类、网络层、页面等机械拆分。用户已授权实现时，票内步骤安排由 Agent 自行推进；新增产品范围或额外验收安排须确认，未确认方案标为草案。

## Wayfinder 决策任务

仅使用 wayfinder 时采用：

- 地图：`.scratch/<effort>/map.md`
- 子任务：同目录下的 `issues/NN-<slug>.md`
- `Type:` 为 research / prototype / grilling / task。
- 待处理任务尚未 claimed/resolved；执行前标记 claimed。
- `Blocked by:` 中所有任务 resolved 后才可开始。
- 完成后追加 `## Answer`，标记 resolved，并在地图加入结论与链接。

此状态约定仅用于决策任务，不替代实现任务的分诊标签。
