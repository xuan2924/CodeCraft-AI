# 架构与首期范围

状态：本文件及关联专题记录用户已确认的设计；各文件的“待决与实现验证”不是已批准方案。内部状态链路及账号登录/密码重置已实现，见 [运行入口](../../README.md)、[账号验证](../validation/account-login-07.md)和[重置验证](../validation/password-revocation-08.md)。工作区与执行尚未实现，设计、研究与一次性 PoC 不代表生产能力。

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

第二阶段已确认 [规格与 22 张任务](../../.scratch/copilot-loop/spec.md)：以第一阶段 #01–#16 全部验收为整体实现入口，决定票可提前核验。先真实 DeepSeek 非思考模式纵向闭环，再完成运行控制、压缩、用户 Skill、用量费用、思考模式与三协议矩阵及数据删除联动。第二阶段使用明确标识的阶段性入口，模型检查须真实通过，不伪装完整面试；面试官、挑战、评测、平台 Agent 配置和授权反馈留到第三阶段。任务规划不代表已实现。

账号管理后台、模型配置测试及授权反馈属于已确认首期能力。暂不引入 Redis、消息队列、向量数据库、AST 索引、容器预热池、多语言执行、企业组织或多人协作体系。

本阶段允许同机部署，仅限可信受邀用户，安全与剩余风险见 [工作区与执行](workspace-execution.md#部署与隔离)。独立机器/VM 不作为当前前提，扩大试用范围前重新评审。

## 按专题实现

- 工程目录、依赖、生成、迁移、启动与验证：读 [工程工具链](engineering-toolchain.md)。
- 身份、页面操作权、REST/gRPC/SSE 与恢复：读 [接口与事件](api-events.md)。
- 工具循环、停止、steer、压缩：读 [Copilot Loop](copilot-loop.md)。
- 模型协议、Key、连接测试、用量与费用：读 [模型接入](model-access.md)。
- Skill 编辑、启停、加载与发布：读 [Skill 配置](skills.md)。
- 提案审批与模式切换：读 [审批](approval.md)。
- 文件版本、写入、测试容器、保留与删除：读 [工作区与执行](workspace-execution.md)。
- 题目、计时、挑战、报告与反馈：读 [面试与评测](interview-evaluation.md)。
- 草稿、编辑器、日志及用户操作呈现：读 [Web IDE](web-ide.md)。

## 待决与实现验证

- 工程目录已随任务 06 初始化为 `backend/server/`、`backend/sandbox/`、`backend/proto/` 和 `frontend/`。当前已交付内部状态与身份模块；后续业务模块与 feature 边界按功能建立，不提前搭建无用途框架。
- 已确认 Maven Wrapper、npm + package-lock.json、Vitest + Vue Test Utils、Playwright；暂不引入 MapStruct、不启用虚拟线程，按实际需求再评估。
- 日常开发与真实链路验收已改为 Java/Go 同处一个 Linux VM/宿主时钟域；Windows 可保留编辑器、Vite 和浏览器，不再以 Windows JVM + Linux VM 混合模式作为截止时间敏感的业务基线。变更原因与取舍见 [ADR 0002](../adr/0002-java-go-shared-linux-clock.md)。PostgreSQL 继续由 Docker 提供，Go 工作区使用持久化具名卷，题目仍在独立受限容器执行。当前启动/验收脚本尚未完成迁移；同一时钟域不免除时钟异常保护及真实业务验收。
- 工具链决定票已验收：前端以精确依赖覆盖清除本次审计告警，Windows Go race 显式使用兼容 C 工具链。任务 06 已交付正式清单/脚本与 Linux CI 配置；远程 CI、Linux 隔离及完整业务链路仍待验证。精确版本、覆盖策略与证据见 [工程工具链](engineering-toolchain.md)。
- Monaco/xterm 的原生封装与 Vite worker 集成待验证；SSE 客户端结合已确认的 Cookie 身份和恢复契约选择，不因草案 POST 示例锁定库。
- 依赖选择兼容版本并锁定，以实际清单和脚本为准；PoC 依赖不构成正式版本决定。
- 任务 16 已确认第一阶段保留、单场限额、引用回收与删除政策，见 [工作区与执行](workspace-execution.md#保留与删除)：已结束演练默认保留 30 天，每用户最多 20 场历史；不新增固定全局磁盘预算/水位，第一阶段不做备份与恢复功能。单场配额、磁盘不足安全处理及重启对账仍须验收，不从“受控试用”推导无限资源；第二阶段已确认演练 AI 记录随演练保留/删除并纳入同一 512 MiB 预算，个人模型连接/Skill 原件独立保留，具体计量与回收仍待实现验证。
