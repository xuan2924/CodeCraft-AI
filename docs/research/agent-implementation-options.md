# Agent 实现方式与工具循环能力核查（精简版）

## 太长不看（TL;DR）

- “SDK”不是一种东西。它可能是**模型客户端**、**工具循环**、**工作流运行时**或**完整编码应用**，四个层级要分开比较，不能把前者的能力边界套在后两者上。
- 当前官方文档已明确：LangChain4j 的 agentic 模块支持**人工审批挂起 + 持久化恢复**；Spring AI 同时提供**自动循环**和**调用方接管**两种机制；Pi 和 OpenAI Agents SDK 有成熟运行时机制，但分别是 TypeScript/Node 和 Python，不能直接替换你的 Java 后端。
- 本报告**不改变**既定路线（Java 21 + Spring Boot 3 + Go 沙箱 + Vue，REST/SSE，Java 持有模型凭证）。手写循环并不“更差”，但要把理由写对——不是“LC4j 不会循环 / 不能审批”。

## 一、先把四层分清楚（最重要）

| 层级         | 负责什么                                    | 例子                                                               |
| ------------ | ------------------------------------------- | ------------------------------------------------------------------ |
| 模型适配     | 请求 / 消息 / 工具 Schema / 响应 / 流式事件 | LangChain4j、Spring AI 的 ChatModel；Pi 的 ai                      |
| 工具循环     | 模型请求 → 调工具 → 回填结果 → 再请求       | 手写；AI Services；Spring AI advisor；Pi agent-core；OpenAI Runner |
| 工作流与恢复 | 多步骤编排、人工等待、检查点                | LangChain4j agentic；OpenAI RunState；你自己的业务状态机           |
| 完整编码宿主 | 会话 / 文件 / 编辑 / 终端 / UI / 凭证发现   | Pi coding-agent                                                    |

> 用模型适配库 + 自己管循环，仍然是“用 SDK”；用自动循环，也不等于把权限、事务、业务状态交给模型。

## 二、各方案速览

| 方案                   | 工具循环                          | 审批 / 挂起                              | 重启恢复                              | 接入性质         |
| ---------------------- | --------------------------------- | ---------------------------------------- | ------------------------------------- | ---------------- |
| LC4j ChatModel（手写） | 应用自管                          | 应用保存待审批工具、停下                 | 应用持久化消息 + 状态 + 结果          | Java 内，现路线  |
| LC4j AI Services       | 内置循环（默认串行，可并发）      | executor 可拦截，无通用 durable 暂停 API | ChatMemoryStore ≠ 运行栈              | Java 内，可 PoC  |
| LC4j agentic           | 工作流 + planner                  | Pending / Suspended 两种挂起             | 自定义 scope store + 稳定 ID + 检查点 | Java 内，实验性  |
| Spring AI 1.1          | 内部执行 / ToolCallAdvisor / 自管 | 应用接管可实现                           | 应用负责                              | Boot 3 候选      |
| Spring AI 2.0 / 2.1    | ToolCallingAdvisor + 工具预算     | 2.1 专用整批确认协议                     | 应用存对话 / 待决请求，无状态         | Boot 4 / 开发版  |
| Pi agent-core          | StreamFn + 事件循环（默认并行）   | beforeToolCall / UI confirm / block      | JSONL 会话恢复                        | Node/Bun，只借鉴 |
| OpenAI Agents(Py)      | Runner 自动循环                   | needs_approval + RunState                | 可序列化运行状态                      | Python，只借鉴   |

## 三、逐个说清楚

### LangChain4j

- **低层 ChatModel 不自动调工具**：它返回“要调哪个工具”，你执行后把结果拼回历史再请求。
- **AI Services 自动循环**：框架反复调用工具。默认一轮（含一个或多个工具调用）上限 **100** 轮；默认串行，可开并发。
- **流式边界**：`TokenStream` 有工具前 / 后回调和取消入口，但它**不是**可持久化的审批接口；并发流式模式下工具可能在完整回复到达前就开始执行。
- **agentic 已支持恢复**：`PendingResponse`（内存等待）和 `SuspendedResponse`（挂起并释放线程）两种；配好 `AgenticScopeStore` SPI 后可 checkpoint + 重启恢复。但**整个模块仍 experimental**，且这不是对任意外部副作用的 exactly-once 保证。

### Spring AI（必须按版本看，不能混用）

- **1.1**：默认模型内部执行；`ToolCallAdvisor` 可接管；`internalToolExecutionEnabled(false)` 后应用自己用 `ToolCallingManager`。兼容 Boot 3.4 / 3.5。
- **当前 2.0**：`DefaultChatClient` 自动挂 `ToolCallingAdvisor`；可关闭自动注册自己调 manager；文档要求 Boot 4.0 / 4.1——**不是现有架构的无成本替换**。默认每工具每 turn 40 次、总计 150 次。
- **2.1-SNAPSHOT（开发版）**：`requiresConfirmation` 整批确认——只要一轮里有工具需确认，**整批不执行**，应用存对话、普通请求续跑。这是无状态协议，不是 1.1 已有能力。
- 注意：`ToolCallAdvisor`（1.1）和 `ToolCallingAdvisor`（2.0）**名字不同，不是同一拼写**，别互相替换。

### Pi（固定 commit `d57c2d2` 核查）

- 模型适配、agent runtime、会话宿主**明确分离**。
- 此 commit 的 agent-core **默认并行**执行工具（先 preflight 顺序完成，再并发）。`beforeToolCall` 可返回 `{block:true}` 阻止。
- coding-agent 默认持久化会话（JSONL），扩展能在工具前 `confirm` 拦截——**有进程内人工确认**，但不能据此说“自带跨进程审批恢复”。
- **是 Node/Bun 运行时**，不是 Java API；扩展与宿主同进程同权限，不是沙箱。

### OpenAI Agents SDK（Python）

- `Runner.run / run_streamed` 自动循环、`max_turns` 限轮（不是工具次数也不是金额预算）、`needs_approval` 产生 `interruptions`、`RunState.to_json()` 可序列化后在另一进程恢复。
- 官方明确：快照**不**自动认证用户或保证内容完整，应只从可信服务端存储恢复，必须做用户校验、原子消费、防重放、副作用对账。
- **是 Python**，不是 Java 库；引入会新增 Python runtime 与 Java 协议边界。本次只作机制参照，不建议擅自引入。

## 四、对本项目的建议（简化）

1. **保持现有路线，但把理由写准**：手写循环的合理性是“应用已掌握 patch 状态、版本、预算、审批恢复协议”，不是“LC4j 不会循环 / 不能审批”。代价是要自己处理好流式参数聚合、工具消息配对、重试、取消。
2. **用同一假模型 / 假 Go adapter 对比 AI Services**：若其 executor 边界、事件完整性、停止 / 恢复能承载业务契约，自动循环可减少通用编排代码；若必须拦住整批未执行工具并长期挂起、或精确持久化每次副作用，先证实生命周期可控再采用。
3. **agentic 在多步骤恢复需求出现时再评估**，不是单个 Copilot 的前置条件；它的工作流恢复也替代不了 Interviewer 的确定性 Java FSM。
4. **Spring AI 先按 1.1 / Boot 3 范围对照**，不为新功能默认升级 Boot；2.1 的无状态审批协议有借鉴价值但不据此变更路线。
5. **借鉴 Pi 的分层思路而非搬整个宿主**：值得迁移的是模型适配与循环解耦、工具 preflight、结构化事件、steering / follow-up 分离、上下文变换；不是引入 Node 服务、默认 bash 工具或把模型凭证下放沙箱。
6. **所有方案共用一套 Go 工具边界**：Java 以受控 Schema 暴露 glob/grep/view/patch/tests，executor 转发 gRPC；patch 先形成“含基线版本的服务端待决实体”，批准后校验用户 / 权限 / 版本 / 状态并幂等应用。`auto` 与 `ask for approve` 只改变何时授权，不扩大工具权限。
7. **分离三种生命周期**：REST 负责任务创建与审批；SSE 只订阅事件；模型 invocation 与测试不因浏览器重连重跑。数据库要单独保存运行状态、pending tool/patch、剩余预算、workspace version、幂等键、已完成结果与事件游标——这些不是任何框架的聊天 memory 能替代的。

## 五、几个易踩的坑

- ❌ “agentic 只有内存等待不能 durable resume” → 配 SPI 后**明确有** checkpoint / recoverability，但要区分 Pending 与 Suspended。
- ❌ 把 `ToolCallAdvisor` 当 `ToolCallingAdvisor` 用 → 两个版本名字不同。
- ❌ 只读 TokenStream 接口注释就认为“工具前必过审批屏障” → 并发流式 handler 可在完整回复前执行工具，审批屏障必须按模式核对。
- ❌ 旧印象“Pi 工具一律串行、steering 会中断当前批次” → 本次 commit 默认并行，steering 在本轮工具之后注入。

## 六、还缺什么（风险 / 待补证据）

- 没有编译 / 运行任一 SDK，没有性能 / 费用 / 代码量结论。
- 未实测：LC4j 新能力的最早稳定版、所有 agentic planner 恢复组合、scope 存储失败语义、AI Services 每工具 checkpoint 粒度。
- 未验证各 provider 在取消、半截工具参数、多工具返回、流断开后的计费一致性。
- “Go 已写成功、Java 未落库即崩溃”的恢复窗口必须测试；没有证据证明框架天然保证跨数据库 / 沙箱 exactly-once。
- 许可已查根 LICENSE（LC4j / Spring AI = Apache-2.0，Pi / OpenAI = MIT），但 SBOM、第三方服务授权、数据出境 / 遥测尚未审计。
- 文档与源码均滚动更新，正式实现前必须固定 BOM / 包版本与 SHA 重跑契约测试。

## 七、最小高价值 PoC（建议先后顺序）

先做一个共享测试夹具，再对接“手写”与“AI Services”两个 Java 循环实现，而不是并行引入所有运行时：

1. 一轮 `[只读, apply_patch, run_tests]`：验证审批前绝不写、拒绝补齐 tool result、不误跑依赖未批准 patch 的测试。
2. 在“等审批 / Go 已完成但未落库 / 模型流到一半”三处杀进程：验证恢复不重写、不丢结果，结果不确定时停止自动重放。
3. 重复审批、模式切换、并发编辑、基线冲突、多文件 patch 失败：验证业务幂等与不部分应用。
4. 流式并发工具 + 最小轮次预算、最大单工具次数、超时与用户取消：验证每个实际工具入口都受硬限制、Go 进程确实终止。
5. 上下文压缩与重启：验证请求 / 结果成对、待审批 patch 与证据引用保留、SSE 重连不重触发调用。
6. 只有上述结果表明自动循环不足或工作流 checkpoint 有独立收益，再用固定稳定版验证 agentic；Spring AI 1.1 作可选对照，不先升级 Boot。
