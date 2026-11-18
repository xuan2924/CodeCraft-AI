# 面试生命周期、有状态面试官与评价报告

## 核心痛点

把“AI 聊天 + 代码编辑器”做成一场严谨的面试，必须解决三个问题：
1. **计时与状态权在谁**：浏览器倒计时不可信，暂停不能只是“前端停表”。
2. **面试官怎么既智能又不越权**：既要结合多轮上下文和候选人代码追问，又不能提前泄露挑战需求或擅自改代码/改分。
3. **评价报告怎么有理有据**：避免模型看一眼聊天记录就胡乱打分，而是要绑定冻结代码、权威测试与协作轨迹。

## 完整生命周期状态机

```text
PREPARING ──▶ READY（澄清阶段：只读看题 + CLARIFY 提问，不计时）
                │ 点击“开始实现”（需平台角色已发布 + 个人模型检查通过）
                ▼
              ACTIVE（实施计时中，stage = BASE → 可选进入 CHALLENGE）
                │ ▲
     请求暂停 ▼ │ 显式恢复（重算 deadlineAt）
    PAUSING ──▶ PAUSED ──▶ RESUMING
                │
    主动结束 / 到期 ▼
             CLOSING（关闭 Go 闸门，核对在途写入并冻结代码）
                │
                ▼
          FINAL_TESTING（针对冻结版本跑最终受控验收，最多 3 次环境重试）
                │
                ▼
              ENDED（只读；提交/跳过回顾 → 生成评价报告与中文 PDF）
```

## 核心机制设计

### 1. 真实可收敛的暂停与恢复（`ExercisePauseStore`）

- **收敛后才停表**：点击暂停进入 `PAUSING` 时倒计时**仍在继续**；Java 先关闭写准入，通过 Go `CloseGate` 等在途保存、测试、Bash、Copilot 和面试官全部静默收敛后，才按 `deadlineAt - now` 向下截断持久化剩余毫秒数并进入 `PAUSED`。
- **绝不隐式恢复**：刷新或重新登录不会自动恢复计时；必须用户显式点击恢复，经 Go `OpenGate` 确认后再按当前时刻加上剩余毫秒数写入新 `deadlineAt`。

### 2. 有状态面试官（`CLARIFY / HINT / DEFENSE`）

- **纯问答无写工具**：使用管理员发布的 `INTERVIEWER` 平台模型版本（演练创建时锁定），与用户 Copilot 的上下文和费用完全隔离。
- **多轮记忆与态势注入**：每次派发自动注入本场最近最多 8 轮有效问答、当前 `stage`、工作区修订与最新受控测试摘要。
- **三种正交模式**：
  - `CLARIFY`：需求澄清（`READY` 与 `ACTIVE` 阶段均可用）。
  - `HINT`：按 `(exerciseId, topic)` 统计已成功发布的提示次数，自动从 **`L1 思路方向 → L2 模块定位 → L3 关键逻辑`** 渐进提示，失败或幂等重放不升级。
  - `DEFENSE`：仅 `ACTIVE` 阶段可用，在事务外优先读取候选人最近变更过的可编辑源码文件（不足 4 个再按字典序补齐，始终排除只读 `AGENTS.md`）与最近变更摘要作为不可信证据，发起架构与边界追问；若排队期间因模型检查过期等导致 Worker 领取时准入拒绝，立即收敛为 `FAILED` 终态并提示原因，避免长时间假排队。

### 3. 可选挑战与最终验收

- 当当前已保存 `revision` 通过基础受控验收且题目含挑战包时，用户可幂等进入 `CHALLENGE`（共用剩余时长，不可退回）。进入前隐藏挑战题面与测试镜像对用户和 Copilot 完全不可见。
- 演练结束后对冻结版本做有限预算的最终验收（至多 3 次尝试 / 10 分钟墙钟预算）；若遇平台基础设施故障导致不可用，保留冻结代码和已知证据，**不把平台故障算作代码 0 分**。

### 4. 回顾、评价报告与授权反馈

- 结束演练后可填写或跳过自我回顾，随后触发 `EvaluationReportWorker` 按 **百分制四维度（正确性 35 / 代码质量 25 / 测试验证 20 / 分析协作 20）** 生成报告，支持导出中文 PDF。
- 报告内嵌确定性的 **Session Timeline（演练时间轴）** 与 **Context Trace（上下文工程轨迹）**，**严禁按提案拒绝率、AI 用量或提示次数机械扣分**；`FILE` 证据仅采集受支持语言的可编辑源码文件（排除 `AGENTS.md` 等说明文档）。

## 关键源码入口

- 生命周期与暂停/挑战：`ExerciseService.java`、`ExercisePauseStore.java`、`ExercisePauseCoordinator.java`、`ExerciseChallengeService.java`
- 平台配置与面试官：`PlatformAgentService.java`、`PlatformCheckWorker.java`、`InterviewerService.java`、`InterviewerWorker.java`、`InterviewerContextCollector.java`
- 报告、PDF 与反馈：`EvaluationReportWorker.java`、`ReportEvidenceCollector.java`、`ReportTimelineReader.java`、`ReportPdfRenderer.java`、`FeedbackService.java`
