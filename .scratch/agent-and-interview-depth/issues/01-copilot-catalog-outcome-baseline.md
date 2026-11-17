# 01: Copilot 工具目录内聚、两轴结果模型与规划基线预重构

**What to build:** 将散落在多处的 Copilot 工具定义、权限、配额与规则加载收敛到单一不可变工具目录，区分工具执行完成状态与业务结果码，并建立服务端维护的规划基线（Planning Baseline），使 `run_tests` 和 `bash` 无需模型手填 `expected_revision` 即可安全执行且能拦截外部并发修改。

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] 建立不可变静态工具目录 `CopilotToolCatalog`，统一声明工具名称、Schema、通道权限（`MAIN / CHILD / BTW`）、是否可重读、是否触发子目录 `AGENTS.md` 规则加载、参数字节上限、操作类型及空间预留额度，消除多处硬编码字符串分支。
- [ ] 新增数据库迁移（不修改已发布历史迁移），为 `copilot_tool` 增加业务结果码 `outcome_code`，并使用显式结果类型 `ToolOutcome` 区分挂起等待（`Suspended`）与执行完成（`Completed(outcomeCode, payload)`）；子目录 `AGENTS.md` 规则仅在读取工具真实成功（`outcome_code = 'OK'`）时加载。
- [ ] 为任务执行分支（`MAIN / CHILD` 独立）维护服务端持久化的规划基线 `planningRevision`：移除 `run_tests` 与 `bash` 工具 schema 中的 `expected_revision` 必填要求（兼容可选传入），当工作区被外部手动保存或恢复推进时向模型返回结构化 `REVISION_CONFLICT`，本任务自身确认写入或命令同步成功后自动推进基线。
- [ ] 清理 `CopilotExecutionStore` 中仅为打破循环依赖而存在的 `*Lifecycle` 胶水包装与多义 `null` 返回，保持短事务与事务外 IO/容量准入边界不变。
- [ ] 通过受影响范围的 Java 单元测试、Checkstyle 与持久化回归验证。

## Comments
