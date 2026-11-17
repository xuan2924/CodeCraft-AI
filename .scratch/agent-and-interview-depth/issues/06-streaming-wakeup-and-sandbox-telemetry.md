# 06: 流式预览写放大治理、事件驱动低延迟唤醒与沙箱分段耗时观测

**What to build:** 消除 Copilot 流式输出期间高频全量覆写数据库的写放大与 SSE 连接每秒空轮询，通过事务提交后（`afterCommit`）轻量唤醒信号实现任务低延迟调度与线程池隔离，抽离统一滑动窗口限流接口，并在 Go 沙箱执行器中记录分段耗时观测数据。

**Blocked by:** 01: Copilot 工具目录内聚、两轴结果模型与规划基线预重构

**Status:** ready-for-agent

- [x] 引入进程内有界流式通知枢纽 `TransientTaskStreamHub`：`CopilotRunner.PartialCapture` 收到流式分片时即刻发布轻量内存通知唤醒对应 SSE 连接，数据库预览检查点降频为至少间隔 2 秒或完成/异常边界落盘（重连仍从权威持久快照恢复，区分非持久预览序号与持久游标）；`TaskEventTransport` 改为事件唤醒驱动加 5 秒空闲身份复核与心跳。
- [x] 为 Copilot、面试官、评测官及自动批准流程接入事务 `afterCommit` 轻量唤醒信号，触发对应 Worker 立即调度；将高频任务调度与 60 秒低频清理/密钥轮换任务拆分为独立调度线程池，同时保留持久数据库扫描兜底及全部停止/到期/租约安全检查频率。
- [x] 抽离统一的滑动窗口限流接口 `SlidingWindowRateLimiter`，默认使用持久化 PostgreSQL 滑动窗口实现（零外部中间件依赖开箱即用），并提供可选的 Redis Lua 脚本分布式限流实现（明确故障切换窗口重置边界，不缓存权威会话撤销状态）。
- [x] 在 Go 沙箱 `backend/sandbox/internal/execution/runner_linux.go` 中记录各阶段耗时分解（输入准备、容器创建、启动附着、命令执行、静默清理），通过 Java 与 Go 单元测试验证。

## Comments

### 实现与验证收口（2026-11-17，pi-backend）

- `TransientTaskStreamHub` 仅保留至多 128 个任务、每项至多 32 KiB 的最新预览，无逐 Token 队列；`copilot_preview` 不带 SSE id，epoch 为 UUID 字符串、sequence 为十进制字符串，与持久游标严格分离。发送前重新授权并匹配 RUNNING task/turn；重连以持久快照恢复。Copilot 与摘要的数据库预览最多每 2 秒检查点一次，完成/异常边界补写。
- SSE 改为任务信号唤醒，合并在途通知避免丢唤醒，5 秒空闲权威身份复核保留；慢连接及批次大小边界不放宽。Copilot、面试官、评测官及 AUTO 审批接入 afterCommit 有界合并队列，提交线程不执行数据库发现/模型 IO；原扫描、租约、停止、到期恢复保留。60 秒维护/密钥轮换使用独立调度池。
- 统一 `SlidingWindowRateLimiter`：默认 PostgreSQL 独立事务实际滑动窗口，批量计数/批量计费，有界桶容量；可选 Spring Data Redis/Lettuce Lua 原子实现，显式配置、有限连接/命令超时、失败关闭，不自动回退重置窗口。V59 保守迁移旧有效额度；后端/策略切换与 Redis 丢数据的窗口重置边界已写入 api-events 设计，不缓存会话撤销。
- Go 五阶段耗时采用安全结构化日志；不输出命令、代码、路径、凭证或异常原文，不作为持久验收证据。额外按 leader 批准泛化 Java/Go 题包发布入口、允许根/嵌套 AGENTS.md 初始指导，并在 Java/Go 修改入口保持不可写。
- Java `./mvnw.cmd -q test`：108 类、469 项全通过（含编译/Checkstyle）。真实一次性 PostgreSQL：限流独立提交/并发/旧迁移 3 项通过，报告聚合 2 项通过，Copilot 边界回归 7 项通过。可选 Redis Lua 在一次性 Redis 容器实际集成测试通过。
- Go：`go test ./internal/execution/... -count=1` 本机通过；交叉编译后的 Linux execution 测试在 Alpine 容器运行通过，Linux workspace 全包非 root 运行通过；两个包的 Linux `go vet`、gofmt、差异空白检查通过。真实执行容器集成用例保持原 opt-in，未将跳过计为通过。首次 root 运行的权限故障夹具不适用，改用非 root 后通过。
- 独立审查发现并修复 PostgreSQL advisory lock 的 MyBatis 映射与 N+1 调用；回归覆盖实际 mapper、并发、保留窗口与迁移。流式/调度边界审查无未修复阻塞。全量 CopilotPersistenceIT 扩大回归曾在 240 秒超时，未记通过；定向受影响回归已通过（见 01）。后端本票无已知剩余功能缺口。
