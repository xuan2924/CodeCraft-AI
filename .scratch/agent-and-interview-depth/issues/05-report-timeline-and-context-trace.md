# 05: 评价报告演练时间轴回放（Session Timeline）与客观上下文工程轨迹

**What to build:** 在演练结束后的评价报告中，提供按时间排序的演练关键事件时间轴回放（Session Timeline）与客观的上下文工程工具使用轨迹（Context Engineering Trace），帮助训练者直观复盘人机协作过程，且不引入任何诱导刷分的拒绝率或修改率扣分规则。

**Blocked by:** 02: Pi 风格精炼编码工具链（read/grep/find/edit/write）与 Prompt 减负, 04: 有状态智能面试官闭环（多轮记忆、分主题渐进提示与代码证据答辩）

**Status:** ready-for-agent

- [x] 后端报告读取接口 `GET /api/exercises/{exercise}/report` 在 `ExerciseReportResponse` 中返回只读确定性字段 `timeline`（聚合澄清/提示/答辩问答、开始实现、工作区代码变更、受控测试完成、进入挑战、最终冻结等关键节点，携带时间戳、类型、标题、摘要、关联工作区版本与来源 ID）与 `contextTrace`（统计本场使用的 `@引用` 次数、`Skill` 加载次数、`steer` 纠偏次数、`BTW` 旁支次数、澄清/答辩次数及各主题最高 `HINT` 等级）。
- [x] `ReportEvidenceCollector` 将客观演练时间轴与分主题提示使用记录纳入有界报告证据目录，供评测官在分析 `COLLABORATION` 与 `VALIDATION` 维度时引用，明确禁止按提案拒绝率、AI 使用量或提示使用次数机械扣分。
- [x] 前端 `ReportPanel.vue` / `ReportPage.vue` 渲染「演练时间轴回放（Session Timeline）」与「上下文工程轨迹（Context Trace）」卡片，支持从时间轴节点关联查看对应证据或修订信息，通过前后端单元测试与构建验证。

## Comments

### Backend delivery (2026-11-17)

- ExerciseReportResponse now exposes the exact timeline/contextTrace contract. Authorized report reads aggregate retained interviewer admissions, implementation start, workspace changes, completed controlled tests, challenge entry and confirmed freeze with deterministic timestamp/kind/source-ID ordering. No source code or prompt text is embedded in timeline metadata.
- Context trace aggregates trusted admitted @reference counts, successful load_skill calls plus explicitly loaded skills, applied steers, admitted BTW tasks, successful CLARIFY/DEFENSE answers and each topic's maximum successful HINT level. Failed hints do not count; historical @references without the new durable count remain zero (not guessed from user text). Copilot reads go through the approved public CopilotReportTraceReader rather than workspace SQL crossing the module boundary.
- ReportEvidenceCollector adds at most 64 timeline nodes/8192 UTF-8 bytes and 8192-byte context evidence, explicitly marks sampling/truncation scope, and retains the existing global evidence budget. Evaluator trusted prompt prohibits mechanical penalties based on proposal rejection rates, AI volume or hint usage. Validator permits these objective citations for collaboration/validation but still requires final-test/code evidence for correctness/quality.
- Added ReportTimelineReaderTest and isolated PostgreSQL ReportTimelinePersistenceIT; updated report collector/store/service, evaluator validator/worker and related constructor tests. Self-review checked source IDs/revisions, retained-history semantics, no user-spoofable reference parsing, authorization-before-read, and evidence bounds. Targeted git diff --check passed. Maven deliberately not run concurrently; parent owns serial validation and records results.
- Retained/pruned test history is not reconstructed; scope evidence declares this limit. Frontend and migrations remain with their respective workers. No commits or staging performed by the child worker.

### 后端 L1 收口（2026-11-17，pi-backend）

- 全量 Java 编译、Checkstyle 与 469 项测试通过；一次性 PostgreSQL 时间轴/轨迹 mapper 集成 2 项通过，可信引用计数迁移与 Copilot 受理回归已验证。
- 评测排队、显式重试和确定失败重生成接入事务提交后唤醒，仍通过原 generation/授权/冻结版本检查。
### 全栈前端与联调收口（2026-11-17，agy-frontend + leader）

- 前端已于 `fe4f244` 完成 `reportApi.ts` 与 `ReportPanel.vue` 演练时间轴与上下文工程轨迹展示及单测（`reportApi.test.ts` / `ReportPanel.test.ts`）。
- 核对 `ExerciseReportResponse` 的 `timeline` / `contextTrace` 与前端解析契约完全匹配；全栈 `node scripts/verify.mjs --quick` 与 `test:ui`（22/22）全部通过。
