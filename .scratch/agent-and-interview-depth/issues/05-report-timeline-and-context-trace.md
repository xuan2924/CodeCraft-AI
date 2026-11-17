# 05: 评价报告演练时间轴回放（Session Timeline）与客观上下文工程轨迹

**What to build:** 在演练结束后的评价报告中，提供按时间排序的演练关键事件时间轴回放（Session Timeline）与客观的上下文工程工具使用轨迹（Context Engineering Trace），帮助训练者直观复盘人机协作过程，且不引入任何诱导刷分的拒绝率或修改率扣分规则。

**Blocked by:** 02: Pi 风格精炼编码工具链（read/grep/find/edit/write）与 Prompt 减负, 04: 有状态智能面试官闭环（多轮记忆、分主题渐进提示与代码证据答辩）

**Status:** ready-for-agent

- [ ] 后端报告读取接口 `GET /api/exercises/{exercise}/report` 在 `ExerciseReportResponse` 中返回只读确定性字段 `timeline`（聚合澄清/提示/答辩问答、开始实现、工作区代码变更、受控测试完成、进入挑战、最终冻结等关键节点，携带时间戳、类型、标题、摘要、关联工作区版本与来源 ID）与 `contextTrace`（统计本场使用的 `@引用` 次数、`Skill` 加载次数、`steer` 纠偏次数、`BTW` 旁支次数、澄清/答辩次数及各主题最高 `HINT` 等级）。
- [ ] `ReportEvidenceCollector` 将客观演练时间轴与分主题提示使用记录纳入有界报告证据目录，供评测官在分析 `COLLABORATION` 与 `VALIDATION` 维度时引用，明确禁止按提案拒绝率、AI 使用量或提示使用次数机械扣分。
- [ ] 前端 `ReportPanel.vue` / `ReportPage.vue` 渲染「演练时间轴回放（Session Timeline）」与「上下文工程轨迹（Context Trace）」卡片，支持从时间轴节点关联查看对应证据或修订信息，通过前后端单元测试与构建验证。

## Comments
