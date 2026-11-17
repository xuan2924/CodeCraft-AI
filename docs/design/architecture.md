# 系统整体架构与职责边界

## 解决什么问题

CodeCraft-AI 是一个面向 AI 结对编程面试的工程演练与评估平台：候选人在受限的 Web IDE 中配合个人 Copilot 完成真实 Java/Maven 项目开发，同时由平台内置的面试官（澄清/提示/代码追问）与评测官完成闭环评估。系统核心矛盾在于**既要像真实 IDE 一样流畅协作，又要保证代码安全隔离、计费隔离和结果可核验**。

## 架构全景与技术选型

```text
Browser (Vue 3 + Monaco + xterm + IndexedDB 草稿)
   │  REST + SSE（HttpOnly Cookie + CSRF + X-Operation-Credential）
   ▼
Java 主后端 (Spring Boot 3 + MyBatis + Flyway + LangChain4j) ──▶ PostgreSQL 17
   │  gRPC / Protobuf（SANDBOX_RPC_KEY 内部鉴权，同 Linux 时钟域）
   ▼
Go 执行服务 (Docker SDK) ──▶ 受限题目 / 开发容器（Linux cgroup + 禁网/开发隔离）
```

### 核心设计取舍

1. **Java 与 Go 职责严格切分，不共享数据库**：
   - **Java 拥有业务状态**：用户身份、演练生命周期、授权锁、任务调度、模型调用与评测逻辑。
   - **Go 拥有文件与容器执行**：工作区磁盘目录、原子版本提交、测试副本、Docker 容器启停与回收。Go 不连 PostgreSQL，浏览器也绝不直连 Go 或 Docker。
2. **三个 AI 角色收敛在 Java 单体内，不拆微服务**：
   - **Copilot**：用用户自己的模型 Key 与预算，拥有受控代码工具和审批流。
   - **面试官（Interviewer）与评测官（Evaluator）**：用管理员发布的平台模型 Key 与预算，绝对禁止改代码；这样做可以共用演练状态锁、存储预算账本与事件流设施。
3. **前端状态单一事实来源**：
   - TanStack Vue Query 管理全部服务端状态；Pinia（`layoutStore`）只管面板布局等客户端 UI 状态；UI 组件库采用 shadcn-vue + Reka UI，主题色统一由 `tokens.ts` 同步到 Monaco 和 xterm。
4. **当前部署前提**：面向可信受邀用户的本机 Linux Docker 部署，不引入 Redis 强制依赖（可选跨节点实时提示、限流与终端传输租约；不承载业务真相）、消息队列、向量库或容器预热池；同一用户同时最多只有 **1 场未结束演练**。

## 权限、隐私与费用隔离

| AI 角色 | 配置入口 | 凭证与计费方 | 数据访问边界 |
|---|---|---|---|
| Copilot / 上下文摘要 | 普通用户个人中心 | 用户 Key，用户承担 | 读写当前演练可编辑文件、个人 Skill 快照 |
| 面试官（Interviewer） | 管理员平台配置 | 平台 Key，平台承担 | 只读公开题面、最近 8 轮问答、`DEFENSE` 模式读有界源码 |
| 评测官（Evaluator） | 管理员平台配置 | 平台 Key，平台承担 | 只读冻结代码证据、受控测试结果、时间轴与上下文轨迹 |

> 管理员后台不提供偷看普通用户私有演练代码、对话或模型 Key 明文的入口；仅在用户主动提交授权反馈包时导出受控快照。

## 关键源码入口

- 启动与配置：`ServerApplication.java`、`compose.yaml`
- 跨端契约：`backend/proto/workspace.proto`、`backend/sandbox/internal/workspace/service_linux.go`
- 前端总入口：`frontend/src/routes.ts`、`frontend/src/shell/AppShell.vue`、`frontend/src/shell/layoutStore.ts`
