# 一次性 PoC 第二轮：跨 JVM 审批恢复

**问题：模型提案 → 人工审批 → 应用修改的三步流程，能否在审批检查点终止 JVM，再由新 JVM 继续，而不重新生成提案？**

本次新增 `RecoveryPoc.java`、`run-recovery.ps1`、`recovery-output.txt`；锁定实验性依赖 `langchain4j-agentic:1.23.0-beta33`。该版本在 Maven Central 已发布，读取其 sources JAR 核实 SPI 并成功编译；未使用 SNAPSHOT API。原有 1.23.0 核心依赖不变。不是正式后端，也不构成选型决定。

## 运行（Windows PowerShell，已实际运行）

```powershell
cd backend/prototypes/agent-loop-poc
.\run-recovery.ps1
```

脚本通过 Maven Wrapper 编译，然后直接启动独立 `java` 进程。每次创建新的 `target/PROTOTYPE-wipe-me-<uuid>/`，不覆盖之前记录。首次运行需下载依赖；无真实模型 API、Key、数据库和 Docker。

`start` 写完检查点后调用 `Runtime.halt(0)`，不执行 JVM shutdown hooks。`resume`、重复审批及用户编辑都是新进程。日志带真实 PID。不是同一对象上的“模拟重启”按钮，也不是普通函数返回。零退出码是预期终止点，不意味着没有强制终止。

## 比较范围：恢复机制，不是完整工具循环

| 路线 | 谁维护恢复位置 | 谁保存文件 |
|---|---|---|
| 应用自管三步状态流程 | 应用的 WAITING/DONE 和 base 字段 | 应用写 Properties |
| agentic 三步 sequence | 框架 planner 与 SuspendedResponse | 应用实现 AgenticScopeStore，使用框架 JSON 序列化 |

```text
进程 A
  假模型生成固定提案 → 写入 base → 等待审批 → 保存检查点 → halt

进程 B（新 JVM，无原来的对象和线程）
  加载检查点 → 完成人工决定 → 版本校验 → 应用或拒绝 → 保存完成状态

进程 C（再一个新 JVM）
  重复审批 → 应用入口发现已完成 → 拒绝再次调用工作流
```

两边共享假模型与假工作区。模型通过真实 ChatModel 接口返回固定文本；不是 provider API。提案调用次数保存在独立工作区文件中，恢复时若重跑可见。agentic 使用固定 `@MemoryId`、`AgenticScopeAccess`、`completePendingResponse` 及重新构建的同一 sequence。

**这轮没有序列化第一轮 ManualLoop 的消息/批次游标，也没有恢复 AI Services 内部正在执行的工具批次。** 为单独核实框架持久化能力，问题缩小为三步工作流。因此不能把结果写成“完整 Copilot 已能跨进程续跑”。模型提案后的续跑仅到应用修改，不包含再一次模型回复或测试工具。

## 实际观察：两种路线 × 三个场景

| 新进程中的动作 | 两种路线观察结果 |
|---|---|
| 批准 | proposalCalls 保持 1，writes 从 0 变 1 |
| 拒绝 | proposalCalls=1，writes=0，原内容不变 |
| 先在独立进程编辑，再批准 | proposalCalls=1，writes=0，CONFLICT，保留 user-edit |
| 每个场景完成后再从新进程批准 | 应用入口识别完成/无待审批项，拒绝再次调用流程；没有重复写入 |

观察记录：`recovery-output.txt`。未添加回归测试框架或自动断言；结果由实际运行日志核对。

**结论：agentic 1.23.0-beta33 的工作流级审批检查点确实能跨 JVM 恢复，不只是文档推测。** 它需要应用提供存储，不会自动接入 PostgreSQL。版本校验、重复审批保护、回执与外部写入仍由应用负责。这个结果削弱了“为了持久化必须手写”的理由，但没有证明 agentic 更适合本项目。

## 未验证与限制

- 仅在检查点完全保存之后强制终止。未在保存中途、审批完成但未保存、写入成功但检查点未更新时终止。
- 假工作区把修改与回执放在同一个文件，不代表 Go 写入与 Java 数据库之间有事务或 exactly-once。
- 临时文件替换没有 fsync/断电耐久性保证；仅单用户、单任务、进程顺序运行。没有并发审批竞争、鉴权或生产数据。
- 仅固定三步 sequence；没有多轮工具调用、工具请求/结果配对、流式参数、取消、重新提案和预算恢复。
- agentic 仍是实验性模块；使用了该发行版提供的 `internal.SuspendedResponse`，升级兼容性需关注。
- 不能据此推断 AI Services 原工具循环可以直接在任意 executor 处保存调用栈。

下一步有区分度的问题是：**真实工具循环如何切成可恢复步骤，并保留同一批工具的执行位置和请求/结果配对？** 这将体现使用工作流框架是否需要改变 Copilot 编排结构，而不是仅比较“能否恢复”。暂不改变 AGENTS.md 或记录最终 ADR。

## 一手来源

- [官方 agentic 文档：持久化和恢复](https://docs.langchain4j.dev/tutorials/agents/)
- [1.23.0-beta33 发布产物及源码](https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-agentic/1.23.0-beta33/)
- 本地编译并运行的 `RecoveryPoc.java` 与 `recovery-output.txt`。

原型尚未形成确认决策，未提交；待确认后再按一次性分支归档。scratch 文件在忽略的 target 下，可在不运行实验时删除对应 PROTOTYPE-wipe-me 目录。
