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
| **瞬态流式预览** | `copilot_preview`（无 SSE `id:`，`previewEpoch` + `previewSequence`） | 纯内存有界枢纽（`TransientTaskStreamHub`，最多 128 任务 × 32 KiB） | 分片毫秒级直推前端渲染；数据库预览检查点降频为 **≥ 2 秒** 一次 |
| **权威持久事件** | `copilot_snapshot` / 任务终态事件（带持久 `cursor`） | PostgreSQL 落库 + 事务 `afterCommit` 唤醒推送 | 前端断线重连带 `Last-Event-ID` 对齐快照；若游标过期发 `reset` 全量重建 |

- **事件驱动低延迟调度**：Copilot、面试官、评测官与 AUTO 审批均接入 `TaskWakeupPublisher`（事务提交后发送轻量内存唤醒信号，由有界异步池立即拉起 Worker），同时保留数据库轮询扫描兜底。

## 关键源码入口

- 身份与限流：`IdentityService.java`、`CaptchaService.java`、`IdentityRevocationBarrier.java`、`PostgresSlidingWindowRateLimiter.java`
- 栅栏与删除清理：`WorkspaceFenceTransactionService.java`、`DeletionCoordinator.java`
- 唤醒与流式推送：`TaskWakeupPublisher.java`、`TransientTaskStreamHub.java`、`TaskEventTransport.java`
- 前端请求与 SSE：`frontend/src/api/request.ts`、`frontend/src/api/useCommand.ts`、`frontend/src/workspace/useTaskStream.ts`
