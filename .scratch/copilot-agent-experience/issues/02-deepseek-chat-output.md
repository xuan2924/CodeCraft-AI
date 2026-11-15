# 02: 完善 DeepSeek Chat 适配与流式呈现

**What to build:** 用户以 DeepSeek Chat Completions 使用思考与多轮工具循环，能区分文本、供应商返回的思考、工具执行及调用错误。补齐实际适配缺口，而非为协议名称迁移至 Responses。

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

**Spec:** [Copilot 编码体验增强](../spec.md)，行为契约 1、8。

## Acceptance criteria

- [x] 核对并完善现有 DeepSeek Chat 的模式、参数、流式文本与思考映射；保留供应商要求的历史字段，显示层不改变协议存储。
- [x] 分片工具参数正确组装，工具调用 ID 与结果保持配对；多轮请求使用必要原始字段，不为统一抽象静默丢字段。
- [x] 不支持的配置、网络错误、模型响应错误与工具执行错误有明确反馈；不隐式更换模型、协议或思考模式，不重发结果未知的收费请求。
- [x] 页面区分文本、可展示的真实思考、工具状态与最终回答；代码块可复制，不伪造模型未返回的思考。流式中断预览不冒充最终回复。
- [x] 用正式适配入口及可控协议响应覆盖分片、思考、工具往返、中断和错误；完成受影响类型/编译、Lint 与自审。

**Acceptance:** accepted（L1；仅本票，不代表其余十一票完成）。

## 验收与范围边界

优先复用现有 SDK；具体缺口及所选技术方案记录在本票。保留其他协议入口但不要求其正常使用实测，不要求补齐 DeepSeek Responses 思考或 Messages 思考实测。真实供应商成功不作为通过门槛，不新增收费验证、实战效果或压缩保真验收；产品已有准备检查不因此自动取消。

## Comments

发布时用户已确认本票范围与依赖；实施结果、技术决定及实际验证统一追加于此。

### 实施决定：优先补齐可见输出而非更换协议

- 排程：本票无依赖，先从本票推进；其余票仍按已批准依赖执行，不改变整体范围。
- 已检查：现有正式 SDK 的 DeepSeek Chat 协议夹具已经覆盖思考、分片双工具、持久化再回填和 usage。当前可见缺口是 driver 只向调用方通知文本分片，历史消息投影没有提供思考字段，页面统一用纯文本展示。
- 备选：更换 SDK/Responses；直接透出 SDK 原消息；维持现有 SDK 并增加明确的展示投影。选择第三项：现有协议已具备所需字段，展示与原始协议分离可以避免暴露 opaque 字段，也不影响其他协议入口。
- 输出组件选型：现有 Vue/Naive UI 不提供 Markdown 词法分析。备选为自研 fence 解析或复用 `marked`；选择锁定 `marked` 18.2.0 的 lexer，仅将代码 token 分块，其余作为文本渲染，不使用 HTML 注入。避免维护通用 Markdown 解析器，新增成本为一个锁版本依赖；不引入高亮或完整富文本渲染体系。
- 视觉处理：沿用现有 IDE 主题与字体；回答正文保持阅读主线，真实思考默认折叠，代码单独区块带语言和复制按钮，错误/未确认预览用明确文字而非只靠颜色。
- 流式保存：增加独立 thinking 预览字段，与文本共用现有总字节预算、租约和序号；不用 thinking 混入回答文本，也不复制 opaque 元数据。完成后同时清空预览，失败时仅保留未确认展示。
- TDD 测试边界按用户自主决策授权落实：正式 ModelCallDriver + 本地 HTTP 协议夹具检查分片类型和后续请求；消息公开投影检查文本/思考分离；前端组件检查实际呈现、复制与中断提示。它们不证明真实供应商或长任务表现，不新增此类验收。

### 交付与验证

- 实现提交 `6d26d73`：保留 Chat/现有 SDK，增加明文思考独立回调、持久化有界预览及历史展示投影；成功清空两种预览，失败保留不完整标识；页面折叠思考、显示代码块并支持复制。原协议消息与 opaque 字段不改变。
- 新入口同时更新非收费浏览器模型替身，避免七参数调用绕过替身回到真实客户端；已有准备检查与预算策略不变。
- TDD：协议回调、展示投影、前端思考解析、嵌套代码复制均先得到失败再修复；持久化思考字段先编译失败，接线后定向集成测试通过。
- `node scripts/verify.mjs --quick` 通过：Java 318、Vue 204、Node 26；Go quick/vet 与 Linux Go compile/vet 通过。42.60 秒，超过 30 秒软目标但在 60 秒硬限内。前端 Lint 为 0 error / 36 个已有范围外 warning；Java Checkstyle 0 violation。
- 独立一次性 PostgreSQL：`CopilotPersistenceIT#partialResponsesStaySeparateFromConfirmedMessagesAndSurviveInterruptedCalls` 两个参数分支通过，验证 38 条迁移、成功清空与失败保留，不是供应商/真实编码验证。容器已停止并自动删除。
- `WorkspaceBrowserModelFixtureTest,ModelCallDriverTest,DeepSeekThinkingProtocolTest,CopilotMessagePresentationTest` 定向测试通过；`npm run build` 通过（既有 Monaco 大 chunk 警告）。
- 独立双轴审查范围 `6b3edb7...6d26d73`：Standards 无发现；Spec 发现 P2 嵌套列表/引用中的代码块缺少复制。已增加两条失败用例并修复递归 token 投影，仍仅文本渲染，不引入 HTML。修复后自审及前端三个定向文件 12 tests、typecheck、目标 ESLint 均通过；不重复全套 quick。
- 本票无剩余功能缺口；未执行真实供应商、实战、压缩保真或收费验证，按已确认边界不构成欠账。
