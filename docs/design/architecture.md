# 架构与首期范围

状态：本文件及关联专题记录用户已确认的设计；各文件的“待决与实现验证”不是已批准方案。正式服务尚未实现，设计、研究与一次性 PoC 不代表生产能力。

## 技术路线与职责

- 产品面向 AI 结对编程面试中的真实工程演练与评估；首期仅限可信受邀用户。
- Java 主后端 + Go 执行服务 + Vue Web IDE。Copilot、Interviewer、Evaluator 是 Java 内部职责，不拆为三个服务。
- Java 21、Spring Boot 3、Maven、LangChain4j；PostgreSQL + MyBatis + Flyway。
- Go 使用 Docker SDK，通过 gRPC + Protobuf 与 Java 通信。
- Vue 3、TypeScript、Vite、Naive UI、Tailwind CSS、Vue Router、Pinia、TanStack Vue Query、Monaco Editor、xterm.js。
- 浏览器通过 REST + SSE 访问 Java；测试日志经 Go gRPC 服务端流、Java SSE 转发。首期无 WebSocket 或交互式 shell。
- Java 拥有题目、演练、身份、审批、事件和报告业务数据；Go 管理工作区、文件工具、执行、日志及资源回收，不访问业务数据库。
- 浏览器不直连 Go、Docker 或模型供应商；模型凭证由用户/管理员提交给 Java 后安全保管，不回显完整值、不下发沙箱。Go RPC 仅供受信任内部调用方使用。
- 前端 Router 管理路由，Pinia 管理共享客户端状态，Vue Query 管理服务端缓存；Naive UI 提供控件与主题，Tailwind 用于布局和轻量样式。

## 演练边界

一次演练是一个用户对一个固定题目版本的独立尝试，拥有独立工作区、对话、测试记录及报告。题目后续发布不改变已有演练。

每个用户最多一场未结束演练。离开页面后可以回来；结束后只读，再练从题目初始工程创建新演练，不覆盖旧记录，不提供旧代码分叉继续或自动成绩对比。生命周期与计时见 [面试与评测](interview-evaluation.md)。

首期仅运行平台维护的 Java/Maven 题目，不接受任意仓库上传或运行时安装外部依赖。题目以版本化文件维护、验证后发布，暂不建设可视化出题后台。

## 配置权、隐私与费用

| 职责 | 配置者 | 凭证与费用 |
|---|---|---|
| Copilot 及上下文摘要 | 用户 | 用户 Key，用户承担 |
| 面试官 | 管理员 | 平台 Key，平台承担 |
| 评测官 | 管理员 | 平台 Key，平台承担 |

用户不能配置面试官/评测官；管理员后台不提供用户 Copilot 私有配置、Skill、对话、代码或报告的查看和修改入口，也不回显凭证明文。后台管理账号及必要运行状态；用户主动授权的反馈包是有限例外，见 [面试与评测](interview-evaluation.md#反馈)。

这不是对服务器管理员的端到端加密隔离承诺。Evaluator 按职责读取必要证据，也不等于管理员可以浏览整场演练。平台安全与资源约束始终生效，任何 Agent 配置都不能覆盖。

## 实现顺序与范围

1. Java/Maven 题目、演练工作区、编辑与测试。
2. Copilot 工具循环、审批、Diff、steer、上下文压缩与用户 Skill。
3. 确定性面试流程、平台 Agent 配置与基于证据的评测。

账号管理后台、模型配置测试及授权反馈属于已确认首期能力。暂不引入 Redis、消息队列、向量数据库、AST 索引、容器预热池、多语言执行、企业组织或多人协作体系。

本阶段允许同机部署，仅限可信受邀用户，安全与剩余风险见 [工作区与执行](workspace-execution.md#部署与隔离)。独立机器/VM 不作为当前前提，扩大试用范围前重新评审。

## 按专题实现

- 身份、页面操作权、REST/gRPC/SSE 与恢复：读 [接口与事件](api-events.md)。
- 工具循环、停止、steer、压缩：读 [Copilot Loop](copilot-loop.md)。
- 模型协议、Key、连接测试、用量与费用：读 [模型接入](model-access.md)。
- Skill 编辑、启停、加载与发布：读 [Skill 配置](skills.md)。
- 提案审批与模式切换：读 [审批](approval.md)。
- 文件版本、写入、测试容器、保留与删除：读 [工作区与执行](workspace-execution.md)。
- 题目、计时、挑战、报告与反馈：读 [面试与评测](interview-evaluation.md)。
- 草稿、编辑器、日志及用户操作呈现：读 [Web IDE](web-ide.md)。

## 待决与实现验证

- 工程目录尚未初始化。候选为 `backend/server/`、`backend/sandbox/`、`backend/proto/`；Java 业务模块、Go 包及前端 feature 边界结合首个纵向功能确定，不把候选目录当作已有工程。
- Maven Wrapper、MapStruct、虚拟线程，以及前端 Vitest + Vue Test Utils、Playwright、包管理器仍需初始化时确认。
- Monaco/xterm 的原生封装与 Vite worker 集成待验证；SSE 客户端结合已确认的 Cookie 身份和恢复契约选择，不因草案 POST 示例锁定库。
- 依赖选择兼容版本并锁定，以实际清单和脚本为准；PoC 依赖不构成正式版本决定。
- 配额、保留期限、备份目标与容量参数尚未定，不从“受控试用”推导无限资源。
