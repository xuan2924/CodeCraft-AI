# 阶段 4：完整面试流程与前端整体重塑

## 解决的核心问题

将系统从“纯粹的编码工具工作台”升级为**完整的 AI 结对编程面试与评估平台**：引入前端全量设计系统重构、平台内置面试官/评测官、真实可信的暂停/恢复机制、可选挑战题目，以及基于证据的评价报告与中文 PDF 导出。

## 关键架构选择

1. **前端 UI 架构重塑**：彻底移除 Naive UI，统一采用 **shadcn-vue + Reka UI + Tailwind CSS + `@lucide/vue`** 源码组件体系；`tokens.ts` 作为唯一色彩源同步配置给 Monaco 与 xterm；**TanStack Vue Query 管服务端缓存，Pinia 仅管布局等极少量共享客户端状态**。
2. **平台 AI 角色与用户隔离（`platformagent`）**：管理员独立配置并检查/发布 `INTERVIEWER` 与 `EVALUATOR` 版本，演练创建时锁定固定版本。平台模型与费用完全归属管理员，用户看不到内部提示词，管理员也无权随意翻看用户私有演练（除非用户主动授权提交反馈包）。
3. **真实可收敛的暂停/恢复闸门（`PAUSING → PAUSED → RESUMING`）**：点击暂停后**不立刻停表**！Java 先关闭新写入准入，通知 Go 关闭工作区 `CloseGate` 并等待所有在途写入、测试、Bash、Copilot 与面试官任务彻底静默，全部收敛后才扣减并持久化剩余毫秒数，恢复后再通过 `OpenGate` 按新当前时刻重算截止时间。
4. **挑战阶段独立不可变 Docker 镜像**：最初考虑把基础与挑战测试放进同一个镜像，但发现开发 `bash` 可以读取镜像里的 `/opt/acceptance` 导致“提前偷看隐藏挑战”。最终改为题包内 `challenge/` 拥有独立验证镜像，基础阶段 Bash 仅跑基础镜像，只有当前版本通过基础验收并幂等进入挑战后才公开挑战题面与验收目标。
5. **基于客观证据的评测与 PDF**：采用百分制四维度（功能完整性 35、代码质量 25、测试验证 20、分析协作 20）+ 显式回顾流程，严禁无证据编造评分；使用 `ReportPdfRenderer` 生成中文报告 PDF。

## 真实踩坑与演进

- **测试夹具遗漏平台准入前置**：接入“开始实现前必须完成个人模型检查”和“创建演练前必须发布平台角色”后，旧的端到端测试在 `start` 阶段集中报 `409`。根因是不能为了过测试而给生产留后门入口，最终统一在测试夹具中走真实前置流程（或仅限测试环境加载的受控桩）。
- **无状态静态校验不足以防伪造**：早期只靠前端禁用按钮限制暂停/挑战阶段操作，随后统一收口到后端 owner/control 锁下的 `ACTIVE` + `deadline` + `gateEpoch` 校验。

## 关键代码入口

- 平台角色与面试流程：`PlatformAgentService.java`、`ExercisePauseStore.java`、`ExerciseChallengeService.java`、`InterviewerService.java`
- 评测、PDF 与反馈：`EvaluationReportWorker.java`、`ReportPdfRenderer.java`、`FeedbackService.java`
- 前端重塑页面：`AppShell.vue`、`WorkbenchLayout.vue`、`PlatformAgentsPage.vue`、`ChallengePanel.vue`、`ReportPanel.vue`
