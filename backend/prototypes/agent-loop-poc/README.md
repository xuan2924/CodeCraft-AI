# 一次性 PoC：AI Services vs 手写 Agent Loop

**要回答的问题：** 同一批工具 `[view_file, apply_patch, run_tests]`，能否做到「写入前等待审批」，并把确定的结果配对交回模型？

> 本目录是实验代码，**不是**正式后端，也**不是**最终选型。Java 21 + LangChain4j **1.23.0** + Maven Wrapper 3.9.15。不依赖 Spring Boot、模型服务、数据库、Go/Docker，无网络模型调用、无 API Key。

## 运行

```powershell
cd backend/prototypes/agent-loop-poc
.\mvnw.cmd -q compile exec:java
```

Linux/macOS 用 `./mvnw -q compile exec:java`（**未验证**：本机 Git Bash 下 shell wrapper 报 wget 临时路径错误，改用 Windows wrapper 后成功——那是下载问题，不是 Java 编译失败）。

首次运行会下载 Maven 与依赖。程序串行输出 5 个场景 × 2 种实现，每个场景独立初始化，无需交互；审批由主线程脚本驱动。`run-output.txt` 是 Windows 下的运行记录。SLF4J 无 provider 警告是此控制台程序的正常现象，业务观察走标准输出。

## 哪些是真的

- **真实执行**：AI Services 的工具调度与结果回填、ChatModel 调用入口、手写消息循环、虚拟线程等待与 Future 通知。
- **模拟**：模型固定返回三个无参工具；工作区是内存字符串；测试只加计数，不编译候选人代码。
- **两边共用的 `Workspace`**（版本检查、防重复批准、依赖工具策略）是**应用能力**，不算框架自带能力。
- AI Services 走同步串行默认路径（未开工具并发）；手写 Loop 保存消息、批次、游标，暂停即返回。**这是两种实现的选择，不代表 AI Services 的能力上限。**
- 为缩小实验：工具无参数、固定一个 Patch、单用户、单轮工具批次。**不代表**完整工具 Schema、安全校验或生产幂等。

## 实际观察

| 场景           | 结果（两种实现一致）                                                            |
| -------------- | ------------------------------------------------------------------------------- |
| AUTO           | 改一次、测一次、模型调用两次                                                    |
| APPROVE        | 等待时 writes=0 / testRuns=0 / modelCalls=1；批准后写一次，模型收到三条工具结果 |
| REJECT         | 不写；依赖改动的测试跳过；拒绝与跳过结果都交回模型                              |
| CONFLICT       | 审批期间用户编辑被保留；AI 写入为零；返回冲突并跳过依赖测试                     |
| DOUBLE_APPROVE | 第二次批准被忽略，总写入仍为一次                                                |

```text
AI Services：assistant.chat → 框架循环 → executor → 等 Future（调用未返回）
                                                        ↓ 用户决定
                                              回填结果 → 框架继续

手写 Loop：runUntilPause → ChatModel → 执行只读工具 → 存游标并返回
           用户决定 → runUntilPause → 回填工具结果 → ChatModel
```

**结论：两条路线都能实现这些基本业务行为，不能因为「需要审批」就排除 AI Services。**

依据为日志人工核对；这是可运行实验，不是带断言的回归套件。未比较代码量、吞吐、费用、生产可靠性，也不做框架性能排名。

## 第一轮尚未回答

1. **跨请求挂起与重启恢复**：两边目前都只是内存状态，没做过真实杀进程验证。应比较「可持久化的手写状态」与「框架支持的挂起路径」，而不是「阻塞写法 vs 非阻塞写法」。
2. **写入成功但结果未落库的崩溃窗口**：需要持久化工具任务/回执并做对账，不能自动重放。
3. **未运行**：流式模型、工具并发、多轮重新提案、模式切换、取消传播、预算边界、超时清理。
4. **预算**只用于防脚本意外循环；两边单位不完全相同，不作对等预算验证。
5. **不保证**多文件原子性、跨用户权限、真实 Go 测试进程停止、真实模型的工具格式兼容。

第二轮独立 JVM 三步流程恢复实验见 [RECOVERY.md](RECOVERY.md)。第三轮进一步验证固定多工具批次的审批恢复及消息配对，见 [BATCH-RECOVERY.md](BATCH-RECOVERY.md)，运行 `./run-recovery.ps1 -Batch`。**第三轮的 agentic 方案包裹应用执行器，不是 AI Services 自动循环的透明恢复。** 真实 API 等执行协议确定后再做冒烟验证。

## 来源

- [LangChain4j 工具文档](https://docs.langchain4j.dev/tutorials/tools/)
- [1.23.0 Maven 元数据](https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j/1.23.0/)
- API 已通过下载的 1.23.0 JAR `javap` + 实际编译核实，未使用滚动文档里的未发布 API。

本原型未形成最终决策、未提交。结论确认后再归档到一次性分支，避免整个原型被纳入生产模块。仓库无对应 issue，未虚构关联。
