# 阶段 6：存储内核 CAS 化、Harness 精度修复与黄金路径收口

## 解决的核心问题

在前五阶段完成完整闭环后，通过全栈源码审查与第一原理梳理，定位出三类阻碍实操流畅度与架构专业度的核心问题：
1. **沙箱存储与观察循环存在“玩具感”与写放大**：每次文件提交重写整棵 `commit-*/tree/` 目录；全局单槽 `gate` 串行化所有工作区与 `NodeStatus` 探针；`Runner.observe` 每 500ms 反复启动 Docker exec 轮询状态并全量重写结果文件；非 PTY 命令每次新建空 `/cache` 导致 Maven 冷启动。
2. **防御与记账机制侵入正常交互热路径**：`ADR-0006` 在 40+ 个调用点（含问面试官、只读工具、切审批模式、取页面凭证）同步跨进程触发 Go 全盘扫描；`CopilotContextPlanner` 将 UTF-8 字节数当作 Token 与 `contextWindow` 取 `Math.min` 导致上下文提前腰斩；按全局 `revision` 失效已读文件和编辑器缓冲区，引发 AI 无意义重读与前端伪冲突。
3. **业务约束与评测采样偏差**：开始实现强绑个人模型 30 分钟易过期检查、PTY 空闲 5 分钟强杀与 Host Proxy 15 分钟令牌不可续租、单用户未结束演练硬编码为 1；面试官 `HINT` 缺少当前失败测试与源码上下文，报告证据采样偏向时间轴头部。

## 关键架构选择与升级

1. **Go 沙箱内核去玩具化（本地 CAS + 细粒度锁 + 可选 S3/MinIO 冻结归档）**：
   - **实例级 CAS Blob 池（`blobs/<sha256>` + `commit-*/manifest.json`）**：文件按 SHA-256 去重落盘，空变更零文件写入；读文件、Grep、历史预览直接读 Blob，仅在测试/命令容器启动时按需物化运行树；向上游统一暴露文件级 `contentSha256`。
   - **锁域拆分与轻量观察**：`NodeStatus` 彻底脱离工作区锁，工作区操作按 `workspaceInstanceId` 细粒度加锁；`Runner.observe` 改为单长驻 `collectFile` 观察协程 + 日志变更驱动落盘（`logs.checkpoint`）；普通 `bash` 复用每工作区有界 `dev-cache`；PTY 空闲超时改为 `SANDBOX_PTY_IDLE_TIMEOUT`（默认 30m）。
   - **可选 S3/MinIO 冻结归档与跨节点恢复（`--profile s3`）**：采用 Go 标准库轻量 AWS SigV4 客户端，热路径零网络依赖，仅在 `Freeze` 时去重归档源码与冻结清单（`archive_sha256`），支持通过受信 RPC `HydrateFrozen` 在健康节点校验恢复冻结快照。
2. **ADR-0006 v2 与 Agent Harness 精度修复**：
   - **轻重准入分流**：`StorageCapacityService` 拆出轻路径 `admitLight`（基于缓存快照 + 增量预留事务内判定，零跨进程扫盘，仅达 80% 水位线或超额回收时触发 Go 校准），重磁盘操作保持真实计量结算。
   - **Token 与字节双轨预算 + `usage` 动态校准**：`CopilotRequestFactory` 拆分 Token 估算与 JSON 编码字节数，结合最近真实 `inputTokens` 动态校准（系数 `0.5~3.0`，统一覆盖聊天与摘要请求）。
   - **文件级 `path + contentSha256` 读前写保护 & Go RE2 单一正则权威**：消除改 A 导致未变文件 B 失效重读；移除 Java/Go 过窄正则封禁，支持 `foo|bar`、`class\s+Foo` 等正常搜索；`copilot_preview` 改为滚动尾段并推送 `toolName` + `toolArgumentBytes` 生成进度。
   - **面试官与报告去偏 + 合理化业务约束**：`ACTIVE` 阶段 `HINT` 接入最新失败测试摘要与相关可编辑源码；报告时间轴保留开局、首次失败/通过转折、最后变更/验证与收尾锚点并做首尾均衡采样；解耦 `READY → ACTIVE` 与个人模型检查（首次调用 Copilot 时校验有效模型，且取消 30 分钟检查过期），`HOST_LLM_PROXY_TOKEN_TTL` 默认放宽至 60m，新增 `V72`（按文件 SHA-256 记录观察基线）与 `V73` 迁移支持 `EXERCISE_MAX_UNFINISHED` 配置。
3. **Vue Web IDE 黄金路径收口**：
   - `editorBuffer.observe` 按 `contentSha256` / 基线内容比对静默推进无关文件版本，彻底消灭伪冲突；
   - `CopilotPanel` 草稿作用域与 `credential` 解耦、修复 `steer` 返回误清新输入、增加中文输入法 `isComposing` 守卫、终态停止空轮询；`CopilotMessageBody` 实现零 `v-html` 的安全 Markdown AST 渲染；
   - `InteractivePtyTerminal` 启动前统一保存草稿、活跃期显式提示锁定状态、冲突横幅支持一键跳转对应 `commandId` Diff；管理台题包校验结果绑定当前输入快照防误导。

## 关键代码入口

- Go CAS、锁与归档：`service_linux.go`、`mutation_linux.go`、`archive_linux.go`、`runner_linux.go`
- Java 容量分流与 Harness：`StorageCapacityService.java`、`CopilotRequestFactory.java`、`CopilotContextPlanner.java`、`CopilotProposalPreparation.java`、`InterviewerContextCollector.java`、`ReportEvidenceCollector.java`
- 前端工作台与 Copilot：`editorBuffer.ts`、`composerDrafts.ts`、`CopilotPanel.vue`、`CopilotMessageBody.vue`、`InteractivePtyTerminal.vue`、`AdminOperationsPage.vue`
