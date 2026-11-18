# Web IDE 交互、本地草稿与工程验证体系

## 核心痛点

浏览器端最容易丢数据的三个场景：1）网络抖动或刷新时未保存代码丢失；2）AI 或 Bash 刚改完代码，用户本地正在输入产生冲突；3）退出登录或删除演练时跨标签页/多账号草稿清理错乱。同时，跨三端工程需要清晰、快速、不依赖收费大模型的日常验证分层。

## Web IDE 关键设计

### 1. 分层状态与本地草稿保护（`drafts.ts`）

- **草稿与正式代码分离**：编辑器未保存修改仅保存在浏览器同源 IndexedDB（主键为 `[owner, exercise, path, id]`，按编辑器页面实例隔离并记录基线 `revision` 与账号登录代次 `epoch`），输入后 300ms 防抖写入；绝不把本地草稿当作已提交给 AI 或测试的正式代码。
- **冲突处理**：保存或 AI 应用推进服务端 `revision` 时，若本地存在草稿且基线已过期，提供双栏 Diff（本地草稿 vs 当前服务端）由用户手工整理后显式保存，不做危险的自动合并。
- **安全退出与删除墓碑**：暂停或选择“保留草稿结束”前必须确认本地草稿落盘成功（若 IndexedDB 写入失败则拦截状态变更并提示复制）；退出登录前逐份检查当前账号未保存草稿（支持保存后退出、丢弃退出或取消）；当演练在服务端被确认删除时，前端同一 IndexedDB 事务清除本地草稿并写入删除墓碑，防止迟到防抖写入把草稿复活。

### 2. 命令幂等与前端组件结构

- **`useCommand.ts`**：为所有重要变更操作统一生成并持有原始 `Idempotency-Key`；当网络超时导致结果未知时，只允许按原键恢复核对，禁止静默换键重试。
- **`useExerciseTimer.ts`**：集中根据后端权威 `serverTime / deadlineAt / pause.remainingMillis` 投影本地倒计时（`PAUSED` 与 `RESUMING` 读取 `pause.remainingMillis`，`ACTIVE` 与 `PAUSING` 按 `deadlineAt` 走钟），并确保组件卸载时释放定时器。
- **工作台三栏布局（`WorkbenchLayout.vue`）**：基于 Reka UI Splitter 实现题目文件栏、Monaco 编辑器 / CodeDiff / 只读 xterm 日志栏、面试官 / Copilot 协作栏（`CopilotConversations.vue` 容器管理多会话切换与组件隔离）。

## 构建脚本与测试分层体系

工程契约以 `backend/proto/workspace.proto` 为唯一跨端接口定义，通过 `./scripts/generate-proto.ps1`（或 `.sh`）同步生成 Java 与 Go 代码；全套 Java 与 Go 联调与沙箱执行均在 Docker（Linux 容器）下运行（Windows 宿主机无需本地留存 `.tools/llvm-mingw` 等 CGO 编译器；仅当在宿主机运行 `--all` 模式中的 Go `-race` 检测时需自备 Linux/CGO 编译环境）。日常服务可通过 `node scripts/dev.mjs <postgres|sandbox|java|web>` 启动。

| 验证层级 | 核心命令 | 覆盖范围与依赖 | 使用时机 |
|---|---|---|---|
| **日常快速门禁** | `node scripts/verify.mjs --quick` | Protobuf 契约、Java 编译/Checkstyle/单测、Go 测试/vet、Vue 类型/Lint/单测 | **日常开发与收口默认入口**（不依赖 Docker/收费模型） |
| **定向单测** | `mvnw -Dtest=... test` / `go test` / `vitest` | 针对单一模块快速反馈 | 编码过程中高频自测 |
| **浏览器组件/本地集成** | `npm --prefix frontend run test:ui` / `test:integration` | Playwright 组件交互与 IndexedDB 逻辑（无真实后端） | 改 UI 交互、键盘可访问性或草稿逻辑时 |
| **真 Docker 执行边界** | `node scripts/test-execution.mjs` | 验证 Linux 容器禁网、只读挂载、超时终止与清理 | 改 Go 沙箱、Docker 配置或题包执行时 |
| **跨端烟测 / 全套** | `node scripts/test-tracer.mjs --smoke --identity --workspace` | 真实 PG + Java + Go + Playwright 浏览器全链路 | 改跨端协议、鉴权或发布前抽检 |

## 关键源码入口

- 前端路由与工作台：`frontend/src/routes.ts`、`frontend/src/workspace/WorkspacePage.vue`、`frontend/src/workspace/WorkbenchLayout.vue`、`frontend/src/workspace/drafts.ts`、`frontend/src/workspace/useExerciseTimer.ts`
- Copilot 界面：`frontend/src/copilot/CopilotConversations.vue`、`frontend/src/copilot/CopilotPanel.vue`、`frontend/src/copilot/CopilotToolResult.vue`
- 工程脚本：`scripts/dev.mjs`、`scripts/verify.mjs`、`scripts/generate-proto.ps1`、`scripts/test-execution.mjs`、`scripts/test-tracer.mjs`、`scripts/clean-docker-images.mjs`
