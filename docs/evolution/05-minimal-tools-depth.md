# 阶段 5：极简工具链、有状态面试官与底层性能治理

## 解决的核心问题

经过前四阶段快速扩张，系统暴露出三个典型工程债：
1. **Agent 工具太啰嗦**：要求模型手填 `expected_revision` 容易幻觉冲突，System Prompt 充斥说教；
2. **面试官偏浅**：此前面试官是单轮无状态问答，报告缺少协作过程回放；
3. **流式与调度写放大**：Copilot 流式输出时 `PartialCapture` 频繁全量覆写数据库，SSE 每秒空轮询，高频任务与低频维护共用调度器。

此外，在正式收口前还执行了一轮全库清理，删除 `backend/prototypes/`（38 个一次性原型）、旧 UI 参考 PNG、合并多余旧题包与多余供应商预设分支。

## 关键架构选择与升级

1. **Pi 风格极简工具链 + 规划基线（`CopilotToolCatalog` + `planningRevision`）**：
   - 将散落各处的工具声明、通道权限、限额和规则加载收拢到静态不可变目录 `CopilotToolCatalog`，并用两轴状态 `ToolOutcome(Suspended / Completed(outcomeCode))` 取代多义 `null`。
   - 新增 `read / grep / find / edit / write` 极简工具：`read` 超范围自动分页（200 行/64 KiB）并提示续读；`grep` 用受限正则子集防 ReDoS 回溯；`edit` 用 `oldText -> newText` 局部精确替换（LF 归一化比较、保留原 CRLF），**模型不再手填 `expected_revision`**，由服务端按任务维度的 `planningRevision` 自动核对并发冲突。
   - **按需加载子目录 `AGENTS.md`**：只有 `read` 真实返回 `OK` 时才加载目标目录的指导文件，且禁止模型或用户通过文件修改接口改写 `AGENTS.md`。
2. **有状态智能面试官与多文件工程题包**：
   - 面试官升级为支持 8 轮多轮记忆与实时态势感知，正交支持三种互动模式：`CLARIFY`（澄清）、`HINT`（按 `topic` 自动从 `L1 思路 → L2 模块定位 → L3 关键逻辑` 渐进提示）和 `DEFENSE`（`ACTIVE` 阶段事务外读取候选人源码与最近变更进行代码拷问）。
   - 新增多文件真实工程题包 `problems/order-settlement-engine/1.0.0`（支持多层只读 `AGENTS.md`）。
   - 评价报告新增确定性的 **Session Timeline** 与 **Context Trace**（记录 `@引用`、Skill、`steer`、`BTW` 与提示等级），禁止按拒绝率或提问次数机械扣分。
3. **流式写放大治理与轻量唤醒**：
   - 新增进程内有界枢纽 `TransientTaskStreamHub`，实时分片通过**不带 SSE `id:` 的瞬态事件 `copilot_preview`** 立即推送给前端，数据库预览检查点降频为 ≥ 2 秒一次（重连仍以权威快照为准）。
   - 为 Copilot、面试官、评测官与 AUTO 审批接入**事务 `afterCommit` 轻量唤醒**（`TaskWakeupPublisher`），同时保留持久数据库扫表兜底；将高频唤醒与 60 秒清理/密钥轮换拆分到独立调度池；抽离统一 `SlidingWindowRateLimiter`（默认 PostgreSQL 滑动窗口，可选 Redis Lua）。
   - Go 沙箱 `runner_linux.go` 增加五阶段耗时分段观测日志。

## 关键代码入口

- 工具目录与新工具：`CopilotToolCatalog.java`、`CopilotReadTools.java`、`CopilotProposalPreparation.java`
- 有状态面试官与时间轴：`InterviewerContextCollector.java`、`InterviewerWorker.java`、`ReportTimelineReader.java`
- 流式唤醒与限流：`TransientTaskStreamHub.java`、`TaskWakeupPublisher.java`、`PostgresSlidingWindowRateLimiter.java`、`runner_linux.go`
