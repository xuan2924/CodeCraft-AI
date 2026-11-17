# 01: Copilot 工具目录内聚、两轴结果模型与规划基线预重构

**What to build:** 将散落在多处的 Copilot 工具定义、权限、配额与规则加载收敛到单一不可变工具目录，区分工具执行完成状态与业务结果码，并建立服务端维护的规划基线（Planning Baseline），使 `run_tests` 和 `bash` 无需模型手填 `expected_revision` 即可安全执行且能拦截外部并发修改。

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [x] 建立不可变静态工具目录 `CopilotToolCatalog`，统一声明工具名称、Schema、通道权限（`MAIN / CHILD / BTW`）、是否可重读、是否触发子目录 `AGENTS.md` 规则加载、参数字节上限、操作类型及空间预留额度，消除多处硬编码字符串分支。
- [x] 新增数据库迁移（不修改已发布历史迁移），为 `copilot_tool` 增加业务结果码 `outcome_code`，并使用显式结果类型 `ToolOutcome` 区分挂起等待（`Suspended`）与执行完成（`Completed(outcomeCode, payload)`）；子目录 `AGENTS.md` 规则仅在读取工具真实成功（`outcome_code = 'OK'`）时加载。
- [x] 为任务执行分支（`MAIN / CHILD` 独立）维护服务端持久化的规划基线 `planningRevision`：移除 `run_tests` 与 `bash` 工具 schema 中的 `expected_revision` 必填要求（兼容可选传入），当工作区被外部手动保存或恢复推进时向模型返回结构化 `REVISION_CONFLICT`，本任务自身确认写入或命令同步成功后自动推进基线。
- [x] 清理 `CopilotExecutionStore` 中仅为打破循环依赖而存在的 `*Lifecycle` 胶水包装与多义 `null` 返回，保持短事务与事务外 IO/容量准入边界不变。
- [x] 通过受影响范围的 Java 单元测试、Checkstyle 与持久化回归验证。

## Comments

### 实施决定（2026-11-17，backend worker）

- 新工具广告统一来自不可变 `CopilotToolCatalog`；旧 `view_file/grep_code/glob_files/propose_changes` 仍可执行。通道授权、上下文可重读投影、参数预算及容量预留改为读取目录。
- `ToolOutcome.Suspended/Completed` 区分等待与业务结果；生命周期仍兼容原状态，完成时同事务写入 `outcome_code`。历史已完成行标记 `LEGACY_UNCLASSIFIED`，不推测成功，也不据历史未知结果加载子目录规则；只有 `read/view_file + OK` 可加载。
- `planning_revision` 按任务隔离，首次模型派发初始化；成功 read 的观察版本在下一次模型派发采用，已确认提案/命令同步版本在回填时推进。单调推进避免同批较早 read 覆盖较晚自身写入。外部修改不自动更新基线，必须读取后重新规划。显式 revision 不一致同样返回冲突。
- `CopilotExecutionStore` 移除三种 Lifecycle 包装依赖，直接查询持久绑定并调用既有 workspace 公开停止入口；其他调用者现有 Lifecycle API 保持兼容，不改 workspace 模块。
- V58 同时承载 interviewer 上下文列及父协调者要求的 `reference_count`，可信引用页数在原始任务受理事务保存，旧行默认为 0。
- 验证协调：共享 Maven target 由父协调者串行执行，worker 未并发启动 Maven；定向测试已增加，实际结果见以下收口记录。

### 后端 L1 收口（2026-11-17，pi-backend）

- Java 全量 `./mvnw.cmd -q test`：108 个测试类、469 项测试通过（0 失败/错误/跳过），含编译与 Checkstyle。
- 一次性 PostgreSQL 17.6 数据库上执行 V1–V59 迁移及 7 项定向 Copilot 持久化测试通过，覆盖读成功刷新/外部修订冲突、流式完成与异常检查点、CHILD/AUTO 版本栅栏、自动审批、命令绑定与未知测试启动恢复。
- 持久化运行发现并修复 MyBatis 重载 mapper 方法 ID 冲突（setter 改名 `updatePlanningRevision`）。独立审查其余规划/结果边界无开放阻塞。
- 曾尝试扩大到整个 `CopilotPersistenceIT`，240 秒命令超时，未算通过；按本票受影响边界收缩至上述已通过回归，不将未完成扩大回归等同于功能缺口。
- 后端本票无已知剩余功能缺口；与后续票共享的迁移、构造器及唤醒基础设施一并验证。
