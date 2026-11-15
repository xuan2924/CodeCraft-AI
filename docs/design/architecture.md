# 架构与首期范围

状态：第一阶段工程底座与第二阶段 Copilot 已达到约定验收，当前范围及证据见 [阶段验收](../../.scratch/copilot-loop/issues/22-stage-acceptance.md)；第三阶段尚未实现。本文件及关联专题区分已确认产品目标、当前实现和真正待决项，不能把历史验证记录中的当时缺口当作当前待办，也不能把设计或一次性 PoC 当作生产能力。运行见 [README](../../README.md)，文档层次见 [导航](../README.md)。

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

第二阶段已按 [规格](../../.scratch/copilot-loop/spec.md)与 [验收记录](../../.scratch/copilot-loop/issues/22-stage-acceptance.md)收口：DeepSeek 非思考/思考真实链路、运行控制、压缩、用户 Skill、用量费用、三协议声明组合及数据删除联动。用户已取消 OpenAI/GLM/Kimi 三家供应商的本阶段目标；保留三协议和自定义连接契约，不推定任意组合兼容，也不重新选型。第二阶段入口仍是计时工程练习，开始前须真实模型检查；下一阶段是面试官、挑战、评测、平台 Agent 配置和授权反馈。已确认的产品边界继续有效，具体 FSM/评分/平台发布方案须按相关待决项设计，不把规划当成已有功能。

账号管理与个人模型配置/检查已交付；授权反馈属于已确认但尚未实现的第三阶段范围。暂不引入 Redis、消息队列、向量数据库、AST 索引、容器预热池、多语言执行、企业组织或多人协作体系。

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

- 工程目录已随任务 06 初始化为 `backend/server/`、`backend/sandbox/`、`backend/proto/` 和 `frontend/`。状态、身份、工作区与执行模块按功能建立；后续业务模块与 feature 边界仍按功能扩展，不提前搭建无用途框架。
- 已确认 Maven Wrapper、npm + package-lock.json、Vitest + Vue Test Utils、Playwright；暂不引入 MapStruct、不启用虚拟线程，按实际需求再评估。
- 日常开发与真实链路验收已改为 Java/Go 同处一个 Linux VM/宿主时钟域；Windows 可保留编辑器、Vite 和浏览器，不再以 Windows JVM + Linux VM 混合模式作为截止时间敏感的业务基线。变更原因与取舍见 [ADR 0002](../adr/0002-java-go-shared-linux-clock.md)。PostgreSQL 继续由 Docker 提供，Go 工作区使用持久化具名卷，题目仍在独立受限容器执行。启动/验收脚本已迁移到 Linux Java 容器（含集成测试 JVM），证据见 [#10–#12 验证](../validation/workspace-write-browser-10-12.md)；首期按 [ADR 0003](../adr/0003-accept-shared-system-clock-risk.md) 接受共享系统时间调整的风险，不建设专项时钟异常保护；正常截止与重启恢复仍须实现并覆盖 L1，额外真实验证按需安排。
- 工具链决定票已验收：前端以精确依赖覆盖清除本次审计告警，Windows Go race 显式使用兼容 C 工具链。任务 06 已交付正式清单/脚本与 Linux CI 配置；当前没有远程仓库，CI 仅为预置配置，不作为本地 Dev Done 前提；Linux 隔离及业务链路证据按对应功能记录，不生成默认全套验证待办。精确版本、覆盖策略与证据见 [工程工具链](engineering-toolchain.md)。
- Monaco 封装与 Vite worker 已有 #10–#12 证据；xterm 与 SSE 客户端已在 #14 按约定边界收口。后续客户端继续遵循 Cookie 身份和恢复契约，不因草案 POST 示例锁定库。
- 依赖选择兼容版本并锁定，以实际清单和脚本为准；PoC 依赖不构成正式版本决定。
- 第一阶段 #16 与第二阶段 #21 已完成保留、计量/预留、引用回收及删除验收，见 [工作区与执行](workspace-execution.md#保留与删除)：已结束演练默认保留 30 天，每用户最多 20 场历史；AI 记录进入同一 512 MiB 预算，个人连接/Skill 原件独立。不新增固定全局磁盘预算/水位或灾难备份；正常重启对账与磁盘不足失败安全已有 L1 证据，不等于验证了任意部署容量或断电恢复。
