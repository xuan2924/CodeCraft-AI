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
  - `grep`：Java 完成授权后以当前修订发起单次 `GrepWorkspace` RPC；Go 校验 `commit-*` 快照后直接按 `blobs/<sha256>` 扫描，由 Go 原生 `regexp.Compile`（RE2 线性时间引擎，天然无回溯 ReDoS 风险）作为唯一正则语法权威（支持 `foo|bar`、`class\s+Foo` 等常规正则），保留 512 字符模式上限、路径前缀、上下文、200 行与 64 KiB 上限，返回前重验修订；Java 返回前再次核对授权与修订。`find` 继续匹配已发布路径。
  - `edit` / `write`：`edit` 优先精确替换；仅在未命中时尝试整行首尾空格/Tab 归一化后的唯一匹配，仍拒绝多处或重叠匹配，且按原文件偏移切片拼接，保留未修改区间的原始换行符（混合换行文件不会误改未选中行）。失败返回总行数和有界的建议重读行号，不回显整份源码。
  - `edit` / `write` / `run_tests` / `bash` 的模型 schema 不暴露 `expected_revision`，服务端注入任务私有 `planningRevision`（旧格式显式传入版本不匹配仍返回 `REVISION_CONFLICT`）；对已有文件执行覆盖写或 `edit` 修改/删除时，按 **`path + contentSha256`** 校验该目标文件当前内容指纹已被本任务 `read` 读取（或由本任务刚成功写入更新），既防止盲写覆盖未观察内容，又消除“修改文件 A 推进全局 revision 导致已读且未变的文件 B 失效重读”的摩擦。
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
| **`delegate_task`** | `EXECUTE`（默认）为单写 `CHILD`；`EXPLORE` 为只读调研 | 连续 Explore 请求最多 3 个一组原子启动，全部收敛后按原 position 回填；每父任务累计最多 8 个，所有子任务均禁止递归委派 |
| **上下文压缩与双轨预算** | Token 预算与 JSON 传输字节上限分离，先零调用 Micro-compaction，再按窗口压力决定 LLM 摘要 | Token 估算结合会话最近真实 `usage.inputTokens` 动态校准（系数限制在 `0.5~3.0` 并统一应用于主请求与摘要请求），仅与模型 `contextWindow` 比较；UTF-8 编码字节数仅与 `execution.contextBytes()` 比较，不再混用单位取 `Math.min`。最近 `recentUnits` 完整单元之外的大型可重读结果优先投影为约 512 字节证据指针 |
| **Skill 快照** | 按需加载任务指导包 | 个人原件上限 50 个 / 5 MiB；任务启动时固定已启用版本快照，`steer` 不刷新 |

### 4. Explore 与工程态摘要边界

- `EXPLORE` 只允许读、检索、证据、Skill 与 Web 工具，服务端拒绝写入、测试、命令及递归委派；不推进父任务的写入规划基线。子任务仅收到有界目标和显式选取的上下文，主对话只接收有界最终结论和证据 ID。
- Explore 窗口按原始工具位置登记并一次性停放父任务，所有子任务终态后在同一归属锁事务中登记回执、依序回填并唤醒。停止、演练结束及权限撤销会停止所有等待子任务；恢复只对账，不重发模型请求。V60 保留 EXECUTE 单等待/CHILD 单写约束，并以 1–3 槽位唯一索引约束 Explore 并发。
- 摘要 JSON 仍为 `goal / decisions / changes / state / evidence` 五字段：保留文件路径与修改意图、精确验证命令及通过/失败/跳过信号、已证伪路径和下一步；提案、未知副作用与未运行验证不能写成成功事实。

## 只读遥测与回放

`GET /api/exercises/{exerciseId}/copilot/telemetry` 校验演练归属，返回 `totals` 与最近 20 项 `recentTasks`。`GET /api/admin/metrics/copilot` 仅对 ADMIN 返回跨演练数值汇总。账本复用任务、轮次、工具与委派检查点；不为统计另发请求、不重放工具。只读窗口利用率为已登记读槽数 /（窗口数 × 4）；无窗口时为 null。模型、工具耗时为已观测生命周期之和（工具可能等待审批、并行时间会重叠），不是进程 CPU 时间。`chatInputTokens/chatOutputTokens/chatCacheReadTokens/chatCacheWriteTokens` 只累计 CHAT 已知值，全缺失为 null，不含 SUMMARY；要比较总用量请查 usage 按 purpose 的分组。`recoveredRevisionConflicts` 只计最终成功任务中，冲突后有后续轮次成功读、且再后续轮次出现同名同参数工具 OK 的冲突；两次相同冲突只归最近一次。放弃旧操作并直接回答不算重试成功。

设置 `COPILOT_IT_JDBC_URL` 指向可创建临时数据库的 PostgreSQL fixture 后运行 `CopilotReplayEvalTest`，结果位于 `backend/server/target/copilot-eval-report.{json,md}`。`DETERMINISTIC_SCRIPTED_BASELINE` 表示使用脚本模型而非供应商跑分；不配置测试数据库时跳过此项。

## 关键源码入口

- Loop 核心：`CopilotCoordinator.java`、`CopilotRunner.java`、`CopilotPartialCapture.java`、`CopilotToolCatalog.java`、`CopilotToolExecutor.java`
- 工具与审批：`CopilotReadTools.java`、`CopilotProposalPreparation.java`、`CopilotProposalService.java`、`CopilotApprovalModeService.java`
- 控制与扩展：`CopilotSteerService.java`、`CopilotFollowupService.java`、`CopilotBtwService.java`、`CopilotDelegationService.java`、`CopilotCompactionService.java`、`CopilotSkillService.java`、`CopilotWebTools.java`
