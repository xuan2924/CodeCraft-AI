# VS Code / Cursor 式编码助手 + 沙箱管理实操手册

本手册梳理两条可直接用于**本地演练与面试现场演示**的操作动线：
- **动线 A**：候选人在工作区使用类 Cursor/VS Code 侧边助手 + 多文件编辑 + 并发/压缩观测。
- **动线 B**：管理员在 CLI 与 `/admin/operations` 完成多文件题包校验、幂等发布、上下架与平台级遥测监控。

---

## 动线 A：工作区编码体验与 Copilot Loop 演练

### 1. 选题与前置准入（`/practice`）
- 入口组件：[CatalogPage.vue](../frontend/src/catalog/CatalogPage.vue)（路由配置于 [routes.ts](../frontend/src/routes.ts)）。
- 支持按语言筛选 `全部 | Java | Python`：
  - `JAVA_MAVEN` 题目（如 `cart-total`、`sliding-window-rate-limiter`）在沙箱镜像可用时允许“开始演练”。
  - `PYTHON_PYTEST` 等尚未接入沙箱执行器的静态契约题目会展示警告徽标 **“该语言暂不支持沙箱执行”** 并禁用启动按钮（[CatalogPage.vue#L125-L135](../frontend/src/catalog/CatalogPage.vue#L125-L135)）。

### 2. 多文件编辑与本地草稿保护（`/workspace`）
- 入口组件：[WorkspacePage.vue](../frontend/src/workspace/WorkspacePage.vue)、[WorkspaceEditor.vue](../frontend/src/workspace/WorkspaceEditor.vue)、[CodeEditor.vue](../frontend/src/workspace/CodeEditor.vue)。
- **操作权抢占**：进入 `/workspace` 后先点击 **「接管操作权」**，顶部出现 **「持有操作权」** 后才允许编辑与保存（防止多标签页竞态写覆盖）。
- **多文件作用域策略**：可编辑根目录不再写死 Java 路径，改为消费后端题目契约返回的 `editableRoots`（未指定时兼容默认 `['src/main/java', 'src/test/java']`），由 [`isEditablePath`](../frontend/src/workspace/WorkspaceEditor.vue#L74-L76) 判定，`README.md` / `pom.xml` / 验收测试保持只读审阅。
- **非入侵式草稿状态**：编辑器顶部工具栏始终可见 `<span role="status" class="backup-inline-status">`（[WorkspaceEditor.vue#L467-L470](../frontend/src/workspace/WorkspaceEditor.vue#L467-L470)），输入后经 300ms 防抖写入浏览器同源 IndexedDB（按账号、演练、路径与登录代次 `epoch` 作用域隔离；若落盘失败会显式阻止“保留草稿并暂停/结束”），页面刷新后仍会提示 **「恢复此草稿」** 而不覆盖远端基线。
- **推荐体验题目**：`sliding-window-rate-limiter` v1.0.0 涵盖三个相互依赖文件：
  - `src/main/java/limiter/QuotaKey.java`
  - `src/main/java/limiter/Rule.java`
  - `src/main/java/limiter/SlidingWindow.java`
  - 以及分层规则说明 `AGENTS.md` 与 `src/main/java/limiter/AGENTS.md`（用于演示项目内分层上下文注入）。

### 3. 侧边 AI 助手、Diff 审批与遥测面板
- **双通道对话**：
  - `MAIN` 主通道：允许调度并行只读窗口（≤4 路 `rereadable` 工具）与 `EXPLORE` 委派（≤3 路只读子任务），提交修改时生成可审查的 **Diff Proposal**，等待候选人批准后再走带 `expectedRevision` 门禁的批写落盘（[CopilotToolExecutor.java](../backend/server/src/main/java/ai/codecraft/modules/copilot/service/CopilotToolExecutor.java#L45-L56)）。
  - `BTW` 旁路通道：仅限只读工具，禁止创建子任务或修改文件（`BTW_READ_ONLY`），适合边查文档边写代码而不打断主任务状态。
- **执行中纠偏（`Steer`）**：主任务进入工具阶段后发送补充指引，系统会在当前并发窗口安全落库后的边界（[`steerAtBoundary`](../backend/server/src/main/java/ai/codecraft/modules/copilot/service/CopilotExecutionStore.java#L97-L112)）撤销后续未执行工具并带着新指令重规划。
- **现场查看 Loop 量化遥测**：
  - 打开助手顶部 **上下文占用环形指示**（[CopilotUsage.vue](../frontend/src/copilot/CopilotUsage.vue)），展开详情即可见 **[CopilotTelemetryCard.vue](../frontend/src/copilot/CopilotTelemetryCard.vue)**（请求 `/api/exercises/{exerciseId}/copilot/telemetry`）。
  - 重点观测指标：
    - `任务数 / 轮次` 与 `工具调用 / 只读窗口（槽位利用率）`
    - `Explore 子任务` 数量
    - `压缩轮次 / 节省字节`（`microCompactedRounds` / `microSavedBytes`）
    - `冲突重试恢复 / Steer`（仅统计先经历 `REVISION_CONFLICT`、重读后同工具重试成功的结果）
    - `聊天轮次 Token（仅 CHAT）`（严格排除 `SUMMARY` 摘要轮次）。

---

## 动线 B：题目沙箱打包、校验与管理台运维

### 1. 本地 CLI 打包与真 Docker 双阶段自检
在发布新题或修改已有题包前，先用项目脚本走通真容器验证（`initial` 必须预期失败、`reference` 必须全绿、包含隔离 / 日志溢出 / 超时终止测试）：

```bash
# 1. 仅校验多文件动态限流器题包（含 base 9 用例与 challenge 14 用例）并产出校验回执 receipt.json（接受 0 或 2 个参数）
node scripts/validate-problem.mjs problems/sliding-window-rate-limiter/1.0.0 ./tmp-receipts

# 2. 校验并生成可发布的题包快照 JSON（内部强制重跑 validate-problem.mjs；JAVA_MAVEN 会构建并绑定本机镜像 ID，PYTHON_PYTEST 则仅验证静态契约，接受 0 或 3 个参数）
node scripts/publish-problem.mjs problems/sliding-window-rate-limiter/1.0.0 ./tmp-receipts ./tmp-rate-limiter-artifact.json
```

> 参考题包结构：[problems/sliding-window-rate-limiter/1.0.0/manifest.json](../problems/sliding-window-rate-limiter/1.0.0/manifest.json) 与 [challenge/manifest.json](../problems/sliding-window-rate-limiter/1.0.0/challenge/manifest.json)。

### 2. 管理端台账、在线预检与幂等发布（`/admin/operations`）
- 入口页面：[AdminOperationsPage.vue](../frontend/src/platformagent/AdminOperationsPage.vue)（侧栏菜单：**「题库与遥测」**，见 [AppShell.vue#L31](../frontend/src/shell/AppShell.vue#L31)；仅对已完成改密且会话有效的 `ADMIN` 账户开放）。
- **核心运维操作**：
  1. **全平台 Copilot 遥测看板**：调用 `GET /api/admin/metrics/copilot`（[CopilotTelemetryController.java#L26-L29](../backend/server/src/main/java/ai/codecraft/modules/copilot/controller/CopilotTelemetryController.java#L26-L29)）按 15s 刷新全平台脱敏汇总（无代码内容、无私有提示词原文，只暴露计数/耗时/字节）。
  2. **题包在线校验（Dry-run）**：粘贴发布 JSON 文本，调用 `POST /api/admin/problems/validate`（[AdminProblemController.java#L34-L38](../backend/server/src/main/java/ai/codecraft/modules/workspace/controller/AdminProblemController.java#L34-L38)）即时解析 `problemId`、`version`、`language`、`executionSupported`、`hasChallenge` 与 `packageSha256`。
  3. **带 `Idempotency-Key` 的幂等发布**：调用 `POST /api/admin/problems/publish`（[AdminProblemStore.java#L47-L56](../backend/server/src/main/java/ai/codecraft/modules/workspace/service/AdminProblemStore.java#L47-L56)）；重复提交同 key + 同包返回首次结果，同 key 换包返回 `IDEMPOTENCY_CONFLICT`（`409`）；超过 1 MiB 返回 `413 REQUEST_TOO_LARGE` 并立即解锁界面。
  4. **即时上下架切换**：调用 `POST /api/admin/problems/{id}/availability` 切换 `available` 状态；下架版本在 `FOR SHARE` 并发开题锁保护下无法被新开演练（[AdminProblemStoreTest.java#L41-L46](../backend/server/src/test/java/ai/codecraft/modules/workspace/service/AdminProblemStoreTest.java#L41-L46)），而进行中的演练通过按版本 ID（`GET /api/problems/{problemVersionId}`）独立加载题面与执行配置，不受目录上下架影响。注意：在线发布到 PostgreSQL 不会自动替远端 Go 沙箱节点拉取镜像或刷新启动清单。
