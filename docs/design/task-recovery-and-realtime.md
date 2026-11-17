# 身份鉴权、状态恢复与实时事件流

## 核心痛点

AI 请求动辄数十秒、Docker 测试随时可能被停止或遇到进程重启、用户还会多开标签页编辑。系统必须解决：**并发多标签页写冲突、未知副作用断线重试不重复扣费、以及流式输出既低延迟又不写爆数据库**。

## 关键设计机制

### 1. 身份、CSRF 与单页面操作权（Page Control）

- **服务端会话**：基于 PostgreSQL 存储会话 + `HttpOnly Cookie`（不采用 JWT），登录与改密接入图形验证码和 `SlidingWindowRateLimiter`（每分钟 5 次按账号/IP 双维限流，默认 PG 滑动窗口、可选 Redis Lua）。
- **密码重置即刻撤销**：修改/重置密码撤销所有旧会话，已建立的 SSE 连接每批发送及空闲每 5 秒复核一次身份，撤销后立即关闭。
- **页面独占写凭证**：浏览器在页面内存持有 `X-Operation-Credential` 和 `X-Codecraft-Owner`。多标签页同时打开时，只有持有当前凭证的页面能保存或审批；新标签页通过显式“接管”作废旧凭证，但不打断后台任务。空间耗尽导致无法普通接管时，归属用户仍可通过不依赖页面凭证的安全通道停止测试或结束演练（[ADR-0007](../adr/0007-owner-recovery-stop-and-end.md)）。

### 2. 跨进程幂等、栅栏（`gateEpoch`）与未知副作用对账

- **全链路幂等键**：前端 `useCommand.ts` 为写操作固定原始 `Idempotency-Key`；当网络超时或状态未知时只允许用**原键**查询/重放核对，绝不换新键重发导致重复扣费或重复改代码。
- **拒绝迟到请求的栅栏**：暂停或结束时，Java 递增并持久化 `gateEpoch` 通知 Go `CloseGate`。任何携带旧 `gateEpoch` 迟到的写 RPC 都会被 Go 直接拒绝；`hostEpoch` 则用于拦截 Go 重启前旧宿主实例的迟到回调。
- **先落库再调用，未知不对账不重放**：AI 调用或容器启动前先提交本地事务记为运行态；如果进程重启后遇到未确认结果的任务，先向 Go 查询已提交回执或容器状态对账，**绝不自动重发收费的 LLM 请求**。

### 3. 实时事件流（SSE）与流式写放大治理

早期每次收到 LLM 流式分片都全量覆写数据库并依赖 SSE 每秒空轮询，造成严重写放大与延迟。现状拆分为**两条通道**：

| 通道 | 载体与序号 | 持久化策略 | 作用与重连行为 |
|---|---|---|---|
| **瞬态流式预览** | `copilot_preview`（无 SSE `id:`，`previewEpoch` + `previewSequence`） | 有界本地枢纽 + 可选 Redis Pub/Sub（每节点最多 128 任务 × 32 KiB） | 分片毫秒级直推前端渲染；数据库预览检查点降频为 **≥ 2 秒** 一次 |
| **权威持久事件** | `copilot_snapshot` / 任务终态事件（带持久 `cursor`） | PostgreSQL 落库 + 事务 `afterCommit` 唤醒推送 | 前端断线重连带 `Last-Event-ID` 对齐快照；若游标过期发 `reset` 全量重建 |

- **事件驱动低延迟调度**：Copilot、面试官、评测官与 AUTO 审批均接入 `TaskWakeupPublisher`（事务提交后发送轻量唤醒信号，由有界异步池立即拉起 Worker），同时保留数据库轮询扫描兜底。
- **跨 Java 节点提示**：`REALTIME_BACKEND=redis` 启用 `TaskRealtimeBus`，在固定版本频道发布完整预览、clear、持久事件通知与任务 lane 唤醒；接收端只更新本地有界预览或调度已有读取器，仍逐批鉴权。发送单线程、队列 128，节点 ID 去除自身回声，不再次转发收到的消息。断网、满队列或坏消息记录丢弃提示，由持久扫描恢复；不在提交线程做 Redis IO。默认 `local` 不连接 Redis。
- **重连不是 Redis 重放**：不引入第二套 `Last-Event-ID` 或 Redis 历史日志。Pub/Sub 断线期间的瞬态分片可丢失，后续完整预览/PG 检查点修复；关键快照与终态仍由 PG cursor 重放。Redis 重启不改变任务所有权，也不触发未知收费请求重试。

### 4. 只读热路径与分布式准入

- `WorkspaceImmutableCache` 在授权后的 `ExerciseService.read/grep` 路径缓存 Go 校验过的 **commit 树**响应；Key 包含 `(exerciseId, workspaceInstanceId, revision, 完整 protobuf 请求)`，revision 0 也是明确版本。命中后仍重新鉴权/核对版本；未提交 PTY 工作树、无版本 query、权限结论均不缓存。路径列表已有 PG 投影，无需新增 gRPC 缓存。
- 缓存为每 JVM 512 项、约 16 MiB 序列化数据预算、60s TTL 的 LRU；保存不可变 protobuf 对象，无反序列化通道。错版响应拒绝且不写缓存；失效只回源携带相同 expectedRevision 的 RPC，Go 若已换版则显式冲突，绝不退回最新版本。
- `RATE_LIMIT_BACKEND=redis` 复用原多桶 Lua 滑动窗口，并为 CLI Proxy 增加每用户 **5 次/秒**突发桶（保留 30 次/分钟、每演练 300 次/小时）。异常/空返回均失败关闭，不回退 PG 放行；默认仍为 PG 确定性窗口。
- 终端 WS 另以 `ConcurrentLeaseLimiter` 限制全局 64 个传输槽位、同 task 一个 owner。Lua 使用 Redis TIME，15s TTL，5s 续租，owner token 比较后才能续租/释放，过期不复活；续租失败关闭连接，释放失败靠 TTL 回收。未启用 Redis 时使用可控 Clock 的有界本地实现。此租约只保护传输资源，**不替代 PG 执行准入、Go 单次 PTY attach 或 gateEpoch 写授权**。
- Compose Redis 为可选 `redis` profile；同时配置两个 backend 与独立 `REDIS_PASSWORD` 后启动 `docker compose --profile redis up`。无宿主端口，健康检查 PING，64 MiB/noeviction 避免配额键被驱逐后静默放行。Redis 数据不是持久账本；部署在可信服务网络，密码只传 Java/Redis，不能传到浏览器或执行容器。
- 可选真实 Redis 契约测试：独立临时 Redis 下设置 `RATE_LIMIT_REDIS_IT_RUN=disposable`、`RATE_LIMIT_REDIS_PORT`，运行 Maven `-Dtest=RedisConcurrentLeaseIT,RedisSlidingWindowRateLimiterIT,RedisTaskRealtimeIT test`；普通 `test/verify --quick` 不执行这些 IT。

## 关键源码入口

- 身份与限流：`IdentityService.java`、`CaptchaService.java`、`IdentityRevocationBarrier.java`、`PostgresSlidingWindowRateLimiter.java`
- 栅栏与删除清理：`WorkspaceFenceTransactionService.java`、`DeletionCoordinator.java`
- 唤醒与流式推送：`TaskWakeupPublisher.java`、`TaskRealtimeBus.java`、`TransientTaskStreamHub.java`、`TaskEventTransport.java`
- 前端请求与 SSE：`frontend/src/api/request.ts`、`frontend/src/api/useCommand.ts`、`frontend/src/workspace/useTaskStream.ts`
