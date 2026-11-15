# P2-21：AI 数据保留与删除联动

**Type:** implementation
**Status:** ready-for-agent
**Acceptance:** accepted（L1 / Dev Done；验收清单、实际测试与审查已完成）
**Blocked by:** P2-04 上下文与 AI 数据容量契约；P2-09 修改提案、Diff 与审批；P2-16 上下文占用、用量与费用；P2-18 Skill 快照与按需加载
**Spec:** [第二阶段规格](../spec.md)

## What to build

在第一阶段 #16 既有删除流程上联动所有演练 AI 数据，保证单场预算、30 天保留、引用回收和个人设置独立，不另建一套无授权的清理通道。

## 开工门槛

第一阶段达到 Dev Done（功能、L1 与代码审查完成，不含远程 CI 或重型验证；特别是 [#16](../../workspace-test-foundation/issues/16-deletion-retention.md)）；前置各数据类型、计量和引用契约完成。读 [Loop](../../../docs/design/copilot-loop.md)、[Skill](../../../docs/design/skills.md)及 [保留与删除](../../../docs/design/workspace-execution.md#保留与删除)。

## Acceptance criteria

- [x] 对话、协议消息、工具结果、Diff、审批及 Skill 快照随演练结束后保留 30 天；提前/到期删除统一撤销访问和事件订阅，再后台清理。
- [x] 已实现的存储预留覆盖全部 AI 类型并计入 512 MiB 单场预算，不额外发 AI 额度；用量/消息/快照共享与多处物化的计量无漏项。
- [x] 压缩不删原始记录；未决写入、审批、模型/工具恢复所需证据先安全收敛，不靠清理释放未知副作用。
- [x] 删除演练不删除个人模型连接/加密 Key 或 Skill 原件；删除原件不破坏保留的快照，最后一个有效引用消失后才可回收共享版本。
- [x] Key 不复制进模型消息/工具记录/快照，删除后最小记录不残留代码、日志或凭证正文；沿用安全墓碑与完整业务回执下限。
- [x] 沿用 #16 清理重试/24 小时人工提示/7 天完成进度/草稿处理，失败不恢复访问，残留继续计量。
- [x] 真数据库和文件记录验证满额/磁盘不足、删除与重启、未知写入、共享 Skill 引用及其他用户隔离；不新增固定全局预算、水位或备份验收。

## Comments

本票收口删除联动和跨类型计量。调用前预留、原始记录持久化和引用完整性须由 P2-08 起逐票实现，不能最后补。

### 交付与验证（2026-11-15）

核对既有 `ExerciseDataService` 及 Copilot/Skill contributor：所有 AI 业务行、usage/价格快照、摘要、提案、steer、测试绑定和 Skill 引用均纳入统一容量；erase 先检查全部 contributor 静默，再在既有删除事务中清理，不新增清理通道或 AI 配额。个人连接/密文 Key 与原件不在演练删除集合。

新增真实 PG `allAiEvidenceUsesTheExistingManualOrThirtyDayDeletionBoundary`（手动/到期两例）：建保留历史、已发布摘要、待审批提案、两份 Skill 快照；删除其中一份个人原件后，快照继续存在。30 天前不自动删除，边界受理即禁止任务/消息读取；跨用户查询拒绝。未收敛批次使整个清理事务回滚，原保留字节不降；失败 24 小时出现人工提示且访问不恢复。后台恢复补齐原批次后，重建删除服务清理，AI 计量归零、最后引用版本回收，个人原件和加密 Key 完全保留；完成进度 7 天消失而安全删除标识仍在。仅在既有公开服务/事务边界验证，不冒充实际进程强杀或磁盘实填。

共享版本同/跨演练计量与最后引用回收、满额原子拒绝沿用 #17/#18 的真实 PG 回归；未知写入/模型、摘要及测试证据保护沿用完整 Copilot 集成集。文件与磁盘不足处理复用第一阶段 #16 的实现/证据，不为了本票重跑跨进程故障矩阵。

实际验证：`DeletionStoreTest,DeletionRetryBudgetTest,TestTaskStopRetentionTest,CopilotContextPlannerTest,CopilotSkillToolsTest,SkillPackageReadTest` 通过；完整 `CopilotPersistenceIT,SkillPersistenceIT,WorkspaceRetentionPersistenceIT` 合计 **106 通过、7 个显式收费测试跳过、0 失败**。其中 Copilot 102 个报告项（含 7 skip），147.415 秒；Skill 1、Workspace retention 10。首次整套命令超出 180 秒，未记通过；按合理整套时限重跑通过，未改业务逻辑绕过。日志 `%TEMP%/ai-retention-integration.log`、`ai-retention-full.log`、`ai-retention-full-repeat.log`。本票无新增生产代码。固定范围 `d3be51c...1f1d05f` 的独立 Standards / Spec 审查均未发现问题，分别给出 OK / OK with notes；备注保留 #20 供应商缺口，不影响本票已完成的保留删除验收，也不证明整个阶段完成。审查运行及完整 L1 结果见 #22；未重跑已通过的验证或新增收费调用。本票功能、L1 与审查已收口。
