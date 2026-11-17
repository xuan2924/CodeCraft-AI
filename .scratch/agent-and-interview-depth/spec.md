# Agent 极简工具链、有状态面试官与真实工程题包升级规格

## 阶段目标与原则

1. **Pi 风格极简强力 Agent 工具链**：去除模型侧冗余参数（如手填 `expected_revision`）与说教式 System Prompt，提供 `read`、`grep`、`find`、`edit`、`write`、`run_tests`、`bash` 极简核心工具，同时完整保留平台特有的 `ASK/AUTO` 审批、`steer`、`BTW` 只读旁支、`delegate_task` 子任务委派、`load_skill`、`read_source`、`read_evidence` 与联网检索能力。
2. **代码可读性与反过度工程化**：通过不可变静态 `CopilotToolCatalog` 与两轴状态模型（执行生命周期 + `outcome_code`）消除散落硬编码、循环依赖胶水类与模糊 `null` 返回；不引入第二套冗余 Outbox 状态机或破坏撤销实时性的 Session 权限缓存。
3. **有状态智能面试官与多文件真实工程题包**：新增 6 类电商促销分摊与幂等结算重构题包 `order-settlement-engine/1.0.0`；重构面试官为支持多轮上下文、实时演练态势感知、分主题渐进提示（`L1->L2->L3`）与基于真实代码证据的 `DEFENSE` 代码追问；报告页新增演练时间轴回放（Session Timeline）与客观上下文工程轨迹。
4. **流式写放大治理与沙箱分段耗时观测**：消除 `PartialCapture` 全文写放大与 `TaskEventTransport` 每秒空轮询，接入事务 `afterCommit` 轻量唤醒与调度池隔离，并在 Go 沙箱记录分段执行耗时。

---

## 跨端接口契约（供前后端并行开发对齐）

### 1. Copilot 工具与提案契约（Ticket 01 & 02）
- 工具目录核心名称：`read`、`grep`、`find`、`edit`、`write`、`run_tests`、`bash`、`load_skill`、`read_source`、`read_evidence`、`delegate_task`、`web_search`、`web_read`（兼容历史消息展示中的 `view_file`、`glob_files`、`propose_changes`、`grep_code`）。
- `edit` 工具输入 schema：
  ```json
  {
    "files": [
      {
        "path": "src/main/java/...",
        "action": "EDIT",
        "edits": [{ "oldText": "...", "newText": "..." }]
      }
    ]
  }
  ```
  其中 `action` 支持 `EDIT`（必填 `edits`）、`CREATE`（必填 `content`）、`DELETE`（省略 `content`）。单次 `edit` 或 `write(path, content)` 均生成标准不可变 `CopilotProposalResponse` / `CopilotProposalDetailResponse`（`files: [{path, kind: CREATE|UPDATE|DELETE, beforeContent, afterContent}]`），前端 Diff 审批接口 `/api/copilot-proposals/{id}` 保持结构兼容。

### 2. 有状态面试官 REST / SSE 契约（Ticket 04）
- `POST /api/exercises/{id}/interviewer/tasks` 请求体扩展（向后兼容仅传 `{text}`）：
  ```json
  {
    "text": "问题或请求内容",
    "mode": "CLARIFY",
    "topic": "discount-rounding"
  }
  ```
  - `mode`：可选枚举 `"CLARIFY"` | `"HINT"` | `"DEFENSE"`，缺省为 `"CLARIFY"`。
  - `topic`：可选字符串（`1..64` ASCII 字母数字及 `_-`），缺省为 `"general"`；当 `mode="HINT"` 时按 `(exerciseId, topic)` 统计本场已成功发布的提示次数并自动分配本次 `hintLevel`（`1` -> `2` -> `3`，达到 `3` 后保持 `3`）。
- `InterviewerTaskResponse` 响应结构（用于 `POST`、`GET .../interviewer/tasks`、`GET .../interviewer/tasks/{task}` 及 SSE `interviewer_snapshot`）：
  ```json
  {
    "id": "uuid",
    "exerciseId": "uuid",
    "question": "...",
    "answer": "...",
    "state": "QUEUED|RUNNING|SUCCEEDED|FAILED",
    "reason": null,
    "platformVersionId": "uuid",
    "phase": "READY|ACTIVE",
    "stage": "BASE|CHALLENGE",
    "mode": "CLARIFY|HINT|DEFENSE",
    "topic": "general",
    "hintLevel": 1,
    "revision": "0",
    "createdAt": "2026-11-17T...",
    "completedAt": "2026-11-17T..."
  }
  ```
  （注：保留 `phase` 字段值等于 `lifecycleState` 以兼容旧断言，新增 `stage`、`mode`、`topic`、`hintLevel`）。

### 3. 评价报告时间轴与客观上下文轨迹契约（Ticket 05）
- `GET /api/exercises/{exercise}/report` 返回的 `ExerciseReportResponse` 新增两个只读字段 `timeline` 与 `contextTrace`：
  ```json
  {
    "timeline": [
      {
        "timestamp": "2026-11-17T...",
        "kind": "INTERVIEWER_ASKED|IMPLEMENTATION_STARTED|WORKSPACE_CHANGED|TEST_COMPLETED|CHALLENGE_ENTERED|EXERCISE_FROZEN",
        "title": "应用 Copilot 修改提案 (r0 → r1)",
        "detail": "COPILOT_PATCH · 2 files",
        "revision": "1",
        "sourceId": "change-or-task-uuid"
      }
    ],
    "contextTrace": {
      "referenceCount": 2,
      "skillLoadCount": 1,
      "steerCount": 1,
      "btwTaskCount": 1,
      "interviewerClarifyCount": 2,
      "interviewerDefenseCount": 1,
      "hintsUsed": [
        { "topic": "discount-rounding", "maxLevel": 2 }
      ]
    }
  }
  ```

### 4. Copilot 流式实时预览 SSE 契约（Ticket 06）
- `/api/exercises/{exercise}/copilot/tasks/{task}/events` 新增非持久瞬时事件 `copilot_preview`（**不带 SSE `id:` 字段，绝不推进持久 `cursor` 或浏览器的 `Last-Event-ID`**）：
  ```json
  {
    "taskId": "uuid",
    "turnId": "uuid",
    "previewEpoch": "uuid",
    "previewSequence": "5",
    "text": "...",
    "thinking": "...",
    "truncated": false,
    "thinkingTruncated": false
  }
  ```
  - `previewEpoch` 为 **UUID 字符串**（同一轮流式调用固定，换 epoch 时重置 `previewSequence` 比对并覆盖）；
  - `previewSequence` 为 **十进制字符串**（从 `"1"` 起单调递增）；
  - `text` 与 `thinking` 为**有界全文替换预览（非增量 delta）**。
- 前端需将 `copilot_preview` 保存在组件瞬态预览状态中（严禁混入权威 `partial.sequence`），仅对当前运行中 `taskId + turnId` 按 `previewEpoch` 变更或同 epoch 下 `BigInt/整数 previewSequence` 单调上升覆盖渲染；重连、切换任务/对话、收到 `copilot_snapshot`（以权威 snapshot 字段覆盖/清空旧 turn 预览）或进入终态时丢弃内存超前预览，保持 REST 与 `copilot_snapshot` 的唯一权威性。

---

## 任务与依赖矩阵

| 票号 | 标题 | 阻塞依赖 (`Blocked by`) | 负责域 |
|---|---|---|---|
| 01 | Copilot 工具目录内聚、两轴结果模型与规划基线预重构 | None | `backend/server` (`copilot` + Flyway) |
| 02 | Pi 风格精炼编码工具链（`read/grep/find/edit/write`）与 Prompt 减负 | 01 | `backend/server` (`copilot`) + `frontend` (`copilot`) |
| 03 | 多文件工程题目包 `order-settlement-engine/1.0.0` 与双阶段受控验收 | None | `problems/order-settlement-engine/1.0.0` |
| 04 | 有状态智能面试官闭环（多轮记忆、分主题渐进提示与代码证据答辩） | None | `backend/server` (`workspace`) + `frontend` (`InterviewerPanel.vue`) |
| 05 | 评价报告演练时间轴回放（Session Timeline）与客观上下文工程轨迹 | 02, 04 | `backend/server` (`workspace`) + `frontend` (`ReportPanel.vue`) |
| 06 | 流式预览写放大治理、事件驱动低延迟唤醒与沙箱分段耗时观测 | 01 | `backend/server` + `backend/sandbox` |
