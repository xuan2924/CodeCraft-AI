# Copilot 自管 Loop、工具链与协作机制

## 核心痛点

直接使用框架自带的自动工具循环，很难优雅处理**长时人工审批挂起、运行中途 `steer` 纠偏、服务端并发基线校验以及多会话共享工作区**。因此选择了 LangChain4j 仅做模型通信适配，由 Java 应用层自管整个任务 Loop（[ADR-0001](../adr/0001-copilot-application-owned-loop.md)）。

## Loop 主流程与工具批次状态机

```text
准备上下文（系统指令 + Skill 目录 + 历史消息 + planningRevision）
  → 调用模型（流式推送 copilot_preview，每 ≥2s 落盘检查点）
  ├─ 无工具调用 → 输出最终回答，任务结束
  └─ 工具调用批次（连续只读窗口最多 4 路并行，其余串行；结果按原始 position 落盘）
       ├─ 检查点：停止？steer？演练暂停/到期？
       ├─ 只读/搜索/受控测试 → 直接执行并回填结果
       ├─ edit / write / bash（ASK 模式）→ 持久化提案，释放 Worker 等待人工审批
       └─ 审批通过 / AUTO 模式 → 重验基线、权限与容量 → Go 原子执行 → 推进 planningRevision → 继续下一轮
```

## 关键设计与能力模块

### 1. Pi 风格极简工具链与规划基线（`CopilotToolCatalog`）

- **静态统一目录**：`CopilotToolCatalog` 集中声明每个工具的 schema、通道白名单（`MAIN / CHILD / BTW`）、参数限额与空间预留，替代早期散落各处的硬编码判断；用 `ToolOutcome(Suspended / Completed(outcomeCode))` 区分挂起等待与业务结果。
- **核心工具**：
  - `read`：单次最多 200 行 / 64 KiB，超限自动分页并提示续读位置；**只有 `read` 真实返回 `OK` 时，才按需加载对应目录的只读 `AGENTS.md`**。
  - `grep`：Java 完成授权后以当前修订发起单次 `GrepWorkspace` RPC；Go 校验 `commit-*` 树后扫描，保留字面量/无回溯正则子集、路径前缀、上下文、200 行与 64 KiB 上限，返回前重验修订；Java 返回前再次核对授权与修订。`find` 继续匹配已发布路径。
  - `edit` / `write`：`edit` 优先精确替换；仅在未命中时尝试整行首尾空格/Tab 归一化后的唯一匹配，仍拒绝多处或重叠匹配，保留 CRLF。失败返回总行数和有界的建议重读行号，不回显整份源码。
  - `edit` / `write` / `run_tests` / `bash` 的模型 schema 不暴露 `expected_revision`，服务端注入任务私有 `planningRevision`；旧格式显式传入的版本不匹配仍返回 `REVISION_CONFLICT`，外部保存不会被自动覆盖。
  - `run_tests` / `bash` / `load_skill` / `read_source` / `read_evidence` / `delegate_task` / `web_search` / `web_read`（并保留历史工具名兼容）。

连续只读工具（含历史别名、Skill、证据与 Web 读取）以最多 4 个为一个安全点窗口：短事务一次登记各自存储预留与 `RUNNING` 标记，事务外并发 IO，按 `position` 顺序逐一提交结果并推进游标。`steer` 在已登记窗口排空后、下一工具之前生效；等待期间轮询归属与停止，停止/中断取消未结束任务并沿用现有批次配对补齐及预留结算。已登记未完成的工具不自动重放（Web 调用可能计费）；写入、测试、命令和委派不进入并行窗口。

### 2. 审批策略（`ASK` vs `AUTO`）

- 默认 `ASK`，可由用户显式切换为 `AUTO`（模型无权自己开启）。模式以**提案服务端登记时刻**为准，切到 `AUTO` 不会自动批准之前已挂起的待审批提案。
- 多文件提案整批原子成功或整批拒绝；拒绝时可附原因，模型可继续调整而不终止当前任务。已生效的修改可通过工作区历史安全撤销。

### 3. 运行控制与多会话协作

| 机制 | 行为定位 | 关键约束 |
|---|---|---|
| **`steer`** | 任务运行中途插队调整方向 | 在下一个安全点截断旧批次未执行工具（补齐 `NOT_EXECUTED` 保持配对）、作废待审批提案后重新规划 |
| **`follow-up`** | 当前任务彻底结束后再执行的队列 | 绑定所属会话，按序派发；等待审批期间不会越过当前任务偷跑 |
| **`New` / `Fork`** | 同一工作区上的多条对话分支 | `Fork` 必须落在工具调用与结果完整配对的安全点；**切换对话只换聊天，不复制或回滚代码文件** |
| **`btw` 旁支** | 主线旁展开的只读技术讨论 | 不打断主任务执行，用户可选择将讨论结论显式带回主线 |
| **`delegate_task`** | 委派单层子任务（`CHILD`） | 复用主线模型/审批/停止传播体系，防止子任务失控或无限递归 |
| **上下文压缩** | 自动与手动压缩长对话 | 仅在消息配对完整的安全点执行，保留近期原文、摘要与决策，**不删原始持久证据** |
| **Skill 快照** | 按需加载任务指导包 | 个人原件上限 50 个 / 5 MiB；任务启动时固定已启用版本快照，`steer` 不刷新 |

## 关键源码入口

- Loop 核心：`CopilotCoordinator.java`、`CopilotRunner.java`、`CopilotPartialCapture.java`、`CopilotToolCatalog.java`、`CopilotToolExecutor.java`
- 工具与审批：`CopilotReadTools.java`、`CopilotProposalPreparation.java`、`CopilotProposalService.java`、`CopilotApprovalModeService.java`
- 控制与扩展：`CopilotSteerService.java`、`CopilotFollowupService.java`、`CopilotBtwService.java`、`CopilotDelegationService.java`、`CopilotCompactionService.java`、`CopilotSkillService.java`、`CopilotWebTools.java`
