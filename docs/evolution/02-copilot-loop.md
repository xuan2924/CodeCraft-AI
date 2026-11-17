# 阶段 2：持久 Copilot 循环与审批恢复

## 解决的核心问题

在阶段 1 底座上接入真实的 AI 结对编程闭环：**读取代码 → 提出修改提案 → Diff 人工/自动审批 → 原子应用 → 触发受控测试 → 观察结果并续答**，且支持中途停止、方向调整（`steer`）、上下文压缩、个人 Skill 与多模型配置。

## 关键架构选择

1. **应用自管 Loop（而非 LangChain4j AI Services 自动循环）**：做过三轮 PoC 验证后确认，即便用实验性 `agentic` 工作流包裹，应用仍要自己承担工具批次、审批暂停与消息配对责任，因此选择 **LangChain4j 模型 API + Java 自管 Loop**（[ADR-0001](../adr/0001-copilot-application-owned-loop.md)）。
2. **工具批次串行执行 + `ASK / AUTO` 审批策略**：批次内工具按顺序执行；遇到写操作且处于 `ASK` 模式时释放 Worker 线程并持久化待审批提案，用户批准后再重验基线版本、权限与容量并原子应用。`AUTO` 模式只免除逐次人工点击，其余安全与基线检查完全一致。
3. **模型多连接与不自动联动**：用户可在个人中心建多个连接（每连接共享 Base URL/Key/协议，下挂多个模型配置），在演练中显式切换“连接 + 模型”，切换在当前完整工具批次收敛后生效；个人配置修改不静默波及正在运行的演练（[ADR-0005](../adr/0005-live-model-connection-updates.md)、[ADR-0008](../adr/0008-model-revisions-and-history-boundaries.md)）。
4. **自定义模型地址仅校验 HTTPS + 禁重定向**：保留内网 HTTPS 可用性，配置 `Redirect.NEVER` 防止凭证随 3xx 泄露，明确接受可信内网场景下的剩余 SSRF 风险（[ADR-0004](../adr/0004-model-url-https-only.md)）。
5. **Skill 任务快照隔离**：个人 Skill 原件独立于演练（限额 50 个 / 合计 5 MiB），Copilot 任务启动时固定已启用快照，首轮只提供名称与描述，通过 `load_skill` 按需加载正文，运行中改原件或 `steer` 不破坏当前快照。

## 真实踩坑与演进

- **DeepSeek 思考模式（Thinking）协议回传**：开启思考模式并携带 tools 时，必须原样保留并回传历史 assistant 轮次的 `reasoning_content`（包括无工具轮次），同时把 UI 展示与底层协议字段解耦，避免简化渲染时误删供应商必需字段。
- **跨协议切换导致历史消息不兼容**：不同协议/供应商对工具调用和原生推理签名不互通。最终确立规则：必须等当前完整工具批次配对结束，再把旧批次降级转换为带来源标注的只读文本证据，不伪造签名也不重做工具。
- **供应商目标收敛**：最初计划兼容 4 家厂商全矩阵，后期收敛为专注 **DeepSeek（Chat/Responses/Messages 实测）+ 自定义连接（三协议原生支持）**，把通用协议契约与真实测试成本解耦。

## 关键代码入口

- Loop 与工具：`CopilotCoordinator.java`、`CopilotRunner.java`、`CopilotProposalService.java`、`CopilotSteerService.java`、`CopilotCompactionService.java`
- 模型与加密：`ModelInvocationService.java`、`ModelSdkClientFactory.java`、`ModelCredentialCipher.java`
- Skill：`CopilotSkillService.java`、`CopilotSkillContext.java`
