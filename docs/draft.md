# 项目设计文档：CodeCraft-AI (AI Coding 实战演练与评估平台)

> 历史草案，非有效规格。保留原始设想，不据此实现 WebSocket、预热池、示例 RPC 或引用未经验证的指标。当前约束从 [根规范](../AGENTS.md) 的任务入口查阅；Copilot Loop 已确认设计见 [正式设计文档](design/copilot-loop.md)。

---

## 1. 项目定位与核心价值

- **项目背景**：当前大厂及 AI 独角兽的面试正全面转向 **“AI 结对编程（AI-Assisted Coding）”** 模式（如现场使用 Cursor / Claude Code 完成真实业务 Bug 修复或 Feature 开发）。
- **平台定位**：面向 AI Coding 面试的仿真演练与评估沙箱。
- **核心差异化**：
    - 不搞传统的刷题（LeetCode）模式，而是**真实工程级代码库排障**。
    - 采用 **Claude Code 风格的自主探索 Agent**（而非笨重的 AST 预处理）。
    - 三 Agent 协同架构（编程助手 + 状态机面试官 + 评测裁判），提供量化的 AI 协作能力报告。

---

## 2. 整体技术架构与拓扑图

系统采用 **Java 业务与 Agent 大脑 + Go 高性能安全沙箱 + Vue 3/Monaco 现代 Web IDE** 的架构：

```
+---------------------------------------------------------------------------------+
|                    前端 Web IDE (Vue 3 + Vite + TypeScript)                     |
|  - Monaco Editor (代码编辑 & Diff 对比)  - xterm.js (单测实时终端输出)          |
|  - Copilot Chat 面板 (SSE 流式输出)      - AI 面试官对话框 (引导/加需求)         |
+---------------------------------------+-----------------------------------------+
                                        | HTTP / WebSocket / SSE
                                        v
+---------------------------------------------------------------------------------+
|                     主后端服务 (Java 21 + Spring Boot 3)                        |
|                                                                                 |
|  [Agent 编排层 - LangChain4j]                                                   |
|   1. Copilot Agent: 自主代码探索工具链 (Glob, Grep, ViewWindow, Patch等，参考pi设计)           |
|   2. Interviewer Agent: 基于有限状态机 (FSM) 驱动的动态面试官                    |
|   3. Evaluator Agent: LLM-as-a-Judge 结构化评分输出                             |
|                                                                                 |
|  [核心引擎层]                                                                   |
|   - Virtual Thread 并发调度  - 会话上下文滑动窗口与 Token 预算控制               |
+---------------------------------------+-----------------------------------------+
                                        | gRPC (Protobuf, 局域网低延迟)
                                        v
+---------------------------------------------------------------------------------+
|                    沙箱执行器微服务 (Go + Docker SDK)                           |
|  - 容器池管理 (Pool of pre-warmed containers)                                  |
|  - 安全与资源隔离 (Cgroups: CPU 1核, 内存 512M, 限制系统调用, 临时目录只读挂载)  |
|  - 单测编译与执行、实时日志采集与流式返回 (stdout/stderr)                       |
+---------------------------------------------------------------------------------+
```

---

## 3. 核心模块详细设计

### 模块 A：Claude Code / Pi 风格的 Copilot Agent（Java + LangChain4j）

摒弃复杂的静态 AST 预处理，完全模拟 Claude Code / PI 的工具化自主探索流：

#### 1. 核心工具集（Tool Calling）

在 Java 中基于 LangChain4j 的 `@Tool` 实现以下 5 个核心工具(未定稿)：

- **`glob_files(pattern)`**：列出匹配的文件路径（如：`**/*Order*.java`），帮助模型建立目录感知。
- **`grep_code(query, path_filter)`**：在指定路径下检索关键词或方法名，返回 `文件名:行号:内容摘要`。
- **`view_file(path, start_line, end_line)`**：**带行号切片查看代码**。限制单次最多查看 100 行，防止模型盲目 `cat` 全量大文件撑爆 Token。
- **`apply_patch(path, patch_content)`**：支持基于行替换或 Unified Diff 语法修改目标文件。
- **`run_tests(test_target)`**：通过 gRPC 调用 Go 沙箱执行单元测试。

#### 2. 上下文与 Token 控制策略

- **System Prompt 极简注入**：仅注入当前项目的简短 README 概况和工具说明（< 500 Tokens）。
- **滑动截断与观察摘要**：每次 Tool 调用返回的结果（如 Grep 的 20 条日志），若多轮未被引用，在第 4 轮后自动精简为一行占位符，保证上下文窗口始终处于健康状态。

#### 3.极简agent设计借鉴:

- https://github.com/earendil-works/pi
- https://github.com/earendil-works/pi

---

### 模块 B：状态机驱动的 Interviewer Agent（Java）

避免 LLM 自由发挥导致面试流程失控，采用 **FSM（有限状态机）控制流 + LLM 生成表达**：

1.  **State 1: `CLARIFICATION`（需求澄清阶段）**
    - 下发故障现象（如：“线上出现超卖问题，请定位并修复”）。
    - _判定规则_：候选人如果直接开写，扣除“工程沟通分”；若候选人主动询问 QPS、数据库隔离级别，触发面试官给予肯定答复并推进状态。
2.  **State 2: `IMPLEMENTATION`（编码阶段）**
    - 面试官静默旁观，监听沙箱事件。
3.  **State 3: `CHAOS_INJECTION`（动态需求/压力挑战阶段）**
    - _触发条件_：当 Go 沙箱首次返回 `Tests Passed: 100%`。
    - _动作_：面试官突然发难：“_很好，现在业务要求支持每秒 5 万次查询，目前的单机锁方案会成为瓶颈，请改为分布式无锁方案并增加降级策略。_”
4.  **State 4: `REVIEW`（复盘与答辩阶段）**
    - 让候选人挑出刚才 Copilot Agent 给出的代码中有无潜在隐患（考察对 AI 幻觉的辨识能力）。

---

### 模块 C：沙箱执行微服务（Go + gRPC + Docker）

#### 1. 为什么用 Go？

- **常驻轻量**：Go 服务的单内存占用通常在 15~30MB，相比 JVM 的几百兆更适合做底层的常驻守护进程。
- **Docker 原生适配**：Go 语言拥有最官方、最高性能的 Docker 客户端库（`github.com/docker/docker/client`）。

#### 2. Protobuf 契约定义 (`sandbox.proto`)

```protobuf
syntax = "proto3";
package sandbox;
option go_package = "./pb";

service SandboxService {
  rpc ExecuteTest (TestRequest) returns (TestResponse);
}

message TestRequest {
  string session_id = 1;
  string project_type = 2; // e.g. "maven", "go", "python"
  map<string, string> modified_files = 3; // 变动的文件路径及内容
  string test_command = 4; // e.g. "mvn test -Dtest=OrderServiceTest"
}

message TestResponse {
  bool success = 1;
  int32 exit_code = 2;
  string stdout = 3;
  string stderr = 4;
  int64 execution_time_ms = 5;
}
```

#### 3. 安全隔离措施

- **资源限制**：设置 Docker HostConfig（`Memory: 512MB`, `NanoCPUs: 1_000_000_000`）。
- **超时强杀**：Go 内部通过 `context.WithTimeout(ctx, 30*time.Second)` 控制，超时强制 kill 容器。
- **网络隔离**：容器网络设为 `--network none`，杜绝候选人或 AI 代码对外挖矿、发起反弹 Shell。

---

## 4. 前端架构选型与推荐（Vue 3 全家桶）

采用 **Vue 3 + Composition API（`<script setup>`）** 构建，相比 React 避免了复杂的 Hook 闭包和重渲染问题，开发心智负担低、交付速度快，非常贴合后端工程师的开发习惯。

### 推荐技术栈：**Vue 3 + Vite + Tailwind CSS + Naive UI + Monaco Editor + xterm.js**

```
+-------------------------------------------------------------------------+
| Top Bar: 题目倒计时 | 当前面试阶段 (FSM) | [运行测试] [提交面试]           |
+--------------------+--------------------------------+-------------------+
|                    |                                |                   |
|  左侧面板 (双栏):   |       中央主代码区:             |   右侧下部:       |
|                    |                                |                   |
|  [Tab 1]           |   CodeEditor.vue               |   TestTerminal.vue|
|   Copilot 编程助手  |   (monaco-editor 原生集成)      |   (@xterm/xterm)  |
|   (输入指令/看diff)|   支持普通编辑 & Diff 视图切换   |   实时 ANSI 彩色  |
|                    |                                |   单测构建日志    |
|  [Tab 2]           +--------------------------------+-------------------+
|   AI 面试官对话框  | 底部状态: Token 消耗统计 | 当前文件分支                 |
+--------------------+----------------------------------------------------+
```

### 核心组件库与选型考量：

1.  **UI 库首选：Naive UI（或 Element Plus）**
    - _推荐 Naive UI 的原因_：天生具备极高质感的**暗黑模式（Dark Theme）**与紧凑的代码类设计风格，无需写大量 CSS 即可搭建出媲美 Cursor 的高级感界面。
2.  **代码编辑与 Diff 对比：`monaco-editor` 原生集成**
    - _落地方式_：直接通过 `npm install monaco-editor` 引入，在 Vue 3 的 `onMounted` 钩子中挂载到 DOM 容器。
    - _双视图切换_：通过响应式变量切换 `create`（普通文件编辑）与 `createDiffEditor`（对比视图）。AI 生成 Patch 时，原代码与修改代码左右并排高亮展示，提供“Accept / Reject”按钮。
3.  **单测控制台回显：`@xterm/xterm` + `@xterm/addon-fit`**
    - _落地方式_：纯 JS 原生库，在 Vue 3 中封装为独立组件 `TestTerminal.vue`。
    - _核心体验_：完美解析 Go 沙箱通过后端 SSE 转发回来的 ANSI 转义字符（带色彩的高亮、报错红字、绿色 Pass 标志），100% 还原 Linux 终端质感。
4.  **长连接与流式打字机：`@microsoft/fetch-event-source`**
    - _解决痛点_：浏览器自带的 `EventSource` 只支持 GET 请求，无法携带复杂的 JSON Payload。该库允许使用 POST 方法进行 SSE 通信，便于携带会话上下文与历史指令。

---

## 5. 简历书写模版（STAR 法则）

你可以直接参考以下内容整理进简历的项目经历中：

> **CodeCraft-AI —— 面向 AI 结对编程面试的智能沙箱评测平台**
> **核心开发者** | 技术栈：Java 21, Spring Boot 3, LangChain4j, Go, gRPC, Docker SDK, Vue 3, Vite, Monaco Editor
>
> - **项目背景**：针对当前一线科技企业“AI-Assisted Coding（人机协同实战面试）”评估需求，自主研发的一套支持真实工程仓库故障排查、交互式 AI 结对与多维度量化评估的在线系统。
> - **主要工作与技术攻关**：
>     1.  **Claude Code 风格自主 Agent 设计**：基于 LangChain4j 构建结对编程 Agent，摒弃臃肿的静态 AST 预处理，自研轻量级代码库探索工具链（`Glob`、`Grep`、带行号的 `ViewWindow`），通过滑动上下文截断使单次交互 Token 消耗降低 **40%**。
>     2.  **有限状态机（FSM）驱动的动态面试官**：设计基于状态流转的 Interviewer Agent，实现“需求澄清 $\to$ 实施观察 $\to$ 混沌故障注入 $\to$ 方案答辩”的闭环；通过监听沙箱事件动态施加并发与幂等挑战，评估候选人的架构应变能力。
>     3.  **双语言高性能沙箱解耦（Java + Go）**：将高危代码执行剥离为独立的 Go 微服务，通过 **gRPC** 与 Java 主服务通信。利用 Go 封装 Docker SDK 实现秒级容器调度、Cgroups 内存/CPU 资源硬隔离与网络黑名单拦截，保障不可信代码的安全执行。
>     4.  **全栈流式协同与高还原 Web IDE**：基于 **Java 21 虚拟线程** 优化 SSE 长连接与 LLM 阻塞开销；前端基于 **Vue 3 + Monaco Diff Editor + xterm.js** 独立打造支持代码级行比对、实时 ANSI 终端回显的高仿真面试演练环境。

---

## 6. 面试核心 Q&A 防守手册（准备这 3 个必问问题）

### Q1：为什么 Copilot Agent 采用类似 Claude Code 的工具链，而不是用向量数据库做 RAG 或者用 AST 提取语法树？

> **答题话术**：
> “做工程级代码库排障时，传统 RAG（Embedding 检索）由于代码语义切片的断裂，很容易丢失跨文件的调用链信息；而 AST 语法解析虽然结构严谨，但跨语言维护成本极高，容易过度设计。
> 业界最新的 Claude Code 和 SWE-bench 证明：大模型本身具备强大的推理规划能力。我们提供类似 Linux 的 `Grep`、`Glob` 和行号切片 `ViewWindow`，让 Agent 根据报错堆栈自主去‘翻阅’代码，反而能最高度还原真实资深工程师排查 Bug 的行为，泛化性更好，系统设计也更符合奥卡姆剃刀原则。”

### Q2：Go 沙箱和 Java 主服务之间，为什么选用 gRPC 而不是 RESTful HTTP？

> **答题话术**：
> “代码测试执行属于高频、低延迟、强结构化的内部服务间调用。
> 第一，gRPC 基于 Protobuf 二进制序列化，Payload 体积明显小于 JSON，在传输大段代码和测试日志时解析性能更高；
> 第二，通过 `.proto` 强 Schema 契约，自动生成 Java 和 Go 双端 Stub 代码，彻底消除了跨语言联调时的字段类型对齐成本。”

### Q3：AI 面试官（Interviewer Agent）如果发生幻觉、流程错乱怎么办？

> **答题话术**：
> “这正是我们引入 **FSM（有限状态机）** 的核心原因。我们没有把面试主导权完全交给 LLM，LLM 只负责当前状态下的‘语言组织’，状态跳转的决定权（比如：何时判定测试通过、何时切入加需求阶段）完全由 Java 后端确定的事件监听器控制。大模型无法自行越级跳过状态，从而在底层架构上杜绝了面试失控。”
