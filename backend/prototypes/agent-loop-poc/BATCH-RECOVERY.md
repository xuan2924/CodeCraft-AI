# 一次性 PoC 第三轮：多工具批次在审批点跨 JVM 恢复

## 问题与运行

同一条 assistant 消息提出 `[view_file, apply_patch, run_tests]`，已经读取、尚未修改时终止进程。新进程能否继续批次，并把原 ID 的三条工具结果交回模型？

```powershell
cd backend/prototypes/agent-loop-poc
.\run-recovery.ps1 -Batch
```

Java 21，LangChain4j 1.23.0，agentic 1.23.0-beta33。没有新依赖、API Key、真实模型或 Docker。源代码 `src/main/java/prototype/BatchRecoveryPoc.java`；本次原始输出 `batch-recovery-output.txt`。

脚本运行两种路线 × 批准/拒绝/冲突，每个场景在检查点保存后 `Runtime.halt(0)`，然后由新的 JVM 恢复，再用第三个 JVM 重复审批。冲突场景增加独立用户编辑进程。所有 PID 在日志中；scratch 在忽略的 target/PROTOTYPE-wipe-me-* 下。

## 实际执行路径

```text
进程 A
  ChatModel 返回原始 assistant 工具批次
    call-1 view_file    → v1:original（计数 readRuns=1）
    call-2 apply_patch  → 待审批
    call-3 run_tests    → 未执行
  保存 user + assistant + call-1 result、cursor=1、base=1
  强制终止 JVM

进程 B
  反序列化原消息，恢复 call-1 的结果，不重新读取
  处理 call-2 → APPLIED / REJECTED / CONFLICT
  处理 call-3 → SIMULATED_PASS / SKIPPED（依赖修改未应用）
  ChatModel 接收 user + 原 assistant + 三条原 ID 工具结果
  保存 DONE

进程 C
  应用入口拒绝已完成任务重复审批，不再调用模型或工具
```

## 观察结果

两条路线均得到以下计数（缺失计数字段代表该动作未执行）：

| 场景 | 生成批次 | 读取 | AI 写入 | 模拟测试 | 结果回填后的模型调用 |
|---|---:|---:|---:|---:|---:|
| 批准 | 1 | 1 | 1 | 1 | 1 |
| 拒绝 | 1 | 1 | 0 | 0 | 1 |
| 版本冲突 | 1 | 1 | 0 | 0 | 1 |

- 重复审批不改变上述计数；保护来自应用的 DONE 检查，不是框架自动赋予。
- 冲突场景保留 user-edit；模型仍收到原读取结果 v1:original，同时收到 CONFLICT，不篡改历史观察。
- 使用真实 `ChatMessageSerializer` / `ChatMessageDeserializer` 保存并恢复消息，不用自然语言摘要重建工具调用。
- 假模型第二次调用会校验原 assistant 批次、三个结果的数量、顺序、调用 ID 和工具名称；不配对则抛异常，不打印假成功。
- 原始消息及最终三条结果另存 `completed-messages.json` 供检查；这个文件是观察记录，不是中间工具执行检查点。

## 本轮最重要的边界：不是 AI Services 自动循环恢复

| 责任 | 应用自管路线 | agentic 路线 |
|---|---|---|
| 模型通信与消息编码 | LangChain4j | LangChain4j |
| 工具批次、工具游标、消息组装 | 应用 | **仍是应用** |
| 审批点位置与恢复调度 | 应用 Properties 状态 | 框架 sequence planner + SuspendedResponse |
| 检查点存储 | 应用文件写入 | 应用 AgenticScopeStore + 框架序列化 |
| 版本校验、幂等回执、依赖测试策略 | 共用应用代码 | 共用应用代码 |

agentic 工作流包含“批次生成并执行读取 / 人工审批 / 执行剩余批次并再次调用模型”三个步骤。应用将消息 JSON 和 cursor 放入 scope，框架负责在人工审批步骤挂起和恢复。

**证明了：在这一个固定批次、固定审批位置，应用自管与 agentic 包裹应用执行器都可以完成跨进程续跑和消息配对。没有证明 AI Services 内部循环可以透明恢复。**

## 对选型的意义（未形成决定）

- 不能以“需要审批/恢复，所以框架做不到”为理由排除框架。
- 这个原型中，agentic 没有消除应用对工具执行状态和业务一致性的责任；它提供了工作流的挂起/恢复调度。
- 如果首期只有单 Copilot 工具循环，应用自管状态是一个直接候选；若多个明确的工作流阶段都有长期等待与恢复需求，agentic 的收益可能更大。未测量开发成本，不能仅按本原型代码量断言孰优。
- AI Services 自动循环已在第一轮验证基本审批；其透明跨进程续跑未验证，不能将第三轮结论记在 AI Services 名下。

## 未覆盖

1. 固定一轮、三个无参数工具、一个审批点。不是动态多轮、多审批或并行工具调度。
2. 审批之后，修改、测试、最终模型请求都处于一个恢复步骤内；没有逐工具持久化。若在此步骤中途崩溃，测试和最终模型请求仍可能重跑。
3. 仅检查点完整保存后 halt。没有检查点写坏、磁盘故障、外部副作用完成但结果未保存的故障注入。
4. 单用户单任务、顺序进程；没有权限校验、并发批准、多文件 Patch、取消、SSE、预算或真实 provider 行为验证。
5. 假工作区在同一文件保存修改与回执，不具有真实 Go / Java 跨服务事务的证明力。
6. 持久化的是原批次、结果和版本；修改内容是固定 fixture，不是模型生成的任意 Patch。

本轮编译运行成功，日志人工核对；未新增测试框架，原型不进入生产模块。不修改 AGENTS.md、不记录已选方案。确认取舍后再归档一次性分支。
