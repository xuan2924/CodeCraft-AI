# 06: 流式预览写放大治理、事件驱动低延迟唤醒与沙箱分段耗时观测

**What to build:** 消除 Copilot 流式输出期间高频全量覆写数据库的写放大与 SSE 连接每秒空轮询，通过事务提交后（`afterCommit`）轻量唤醒信号实现任务低延迟调度与线程池隔离，抽离统一滑动窗口限流接口，并在 Go 沙箱执行器中记录分段耗时观测数据。

**Blocked by:** 01: Copilot 工具目录内聚、两轴结果模型与规划基线预重构

**Status:** ready-for-agent

- [ ] 引入进程内有界流式通知枢纽 `TransientTaskStreamHub`：`CopilotRunner.PartialCapture` 收到流式分片时即刻发布轻量内存通知唤醒对应 SSE 连接，数据库预览检查点降频为至少间隔 2 秒或完成/异常边界落盘（重连仍从权威持久快照恢复，区分非持久预览序号与持久游标）；`TaskEventTransport` 改为事件唤醒驱动加 5 秒空闲身份复核与心跳。
- [ ] 为 Copilot、面试官、评测官及自动批准流程接入事务 `afterCommit` 轻量唤醒信号，触发对应 Worker 立即调度；将高频任务调度与 60 秒低频清理/密钥轮换任务拆分为独立调度线程池，同时保留持久数据库扫描兜底及全部停止/到期/租约安全检查频率。
- [ ] 抽离统一的滑动窗口限流接口 `SlidingWindowRateLimiter`，默认使用持久化 PostgreSQL 滑动窗口实现（零外部中间件依赖开箱即用），并提供可选的 Redis Lua 脚本分布式限流实现（明确故障切换窗口重置边界，不缓存权威会话撤销状态）。
- [ ] 在 Go 沙箱 `backend/sandbox/internal/execution/runner_linux.go` 中记录各阶段耗时分解（输入准备、容器创建、启动附着、命令执行、静默清理），通过 Java 与 Go 单元测试验证。

## Comments
