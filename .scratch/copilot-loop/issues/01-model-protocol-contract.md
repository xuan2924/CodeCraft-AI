# P2-01：模型 SDK 与协议兼容性

**Type:** decision
**Status:** ready-for-agent
**Acceptance:** accepted（调整后的三协议/DeepSeek 契约，证据见 #19/#20/#22）
**Blocked by:** None
**Spec:** [第二阶段规格](../spec.md)

## What to build

核实正式 LangChain4j 依赖及三协议能力，先为真实 DeepSeek 非思考闭环提供可用结论，再完成思考模式、DeepSeek 预设和自定义服务契约的验证矩阵。用户已取消 OpenAI、GLM、Kimi 三家供应商的本阶段目标。沿用应用自管 Loop，不重新选型、不把 PoC 版本当正式锁定。

## 决策边界

读 [模型接入](../../../docs/design/model-access.md)、[Copilot Loop](../../../docs/design/copilot-loop.md)及关联 ADR；使用 API 前核对最新官方说明和实际依赖。可提前核验，不声称第一阶段业务底座已交付。

## Acceptance criteria

- [x] 清点实际依赖及 SDK 接口，说明正式模块/版本建议和新增依赖理由；区分已核实、待验证与不支持。
- [x] 为 Chat Completions、Responses、Anthropic Messages 核实流式文本/工具参数、调用 ID、推理字段回传、usage、超时取消和 SDK 隐式重试。
- [x] 记录 DeepSeek 非思考模式的最小多轮工具闭环方案；思考模式明确必需字段及停止/steer/压缩后的配对限制，不能静默切换模式；按 ADR 0005 核实用户显式变更协议/模型/思考模式时的历史消息兼容边界，不假定任意配置能无损接续。
- [x] 建立供应商×协议×模式的真实验证清单，不承诺所有组合都支持；确定模型 ID/窗口来源，不复制陈旧示例当现行事实。
- [x] 仅具体疑问未解决时做最小实验，先说明问题与通过条件，得结论即停止；真实调用显式触发并有费用提示/限制。
- [x] 可使用环境变量 DEEPSEEK 的联调 Key，但不输出值、不提交秘密、不隐式作为产品回退；凭证缺失或无效明确未验证。
- [x] 发布可引用的 DeepSeek 子结论及剩余门槛，SDK 缺口或技术路线变更先请用户确认，再同步模型接入设计。研究结果不代替 P2-19/20 的正式业务兼容验收。

## Comments

用户确认三协议属于第二阶段终验目标；先打通 DeepSeek 非思考模式，再验证思考模式。此票交付契约与核验记录，不初始化无业务用途的框架。

### 最终契约验收

后续正式实现与实测已补齐下方历史子结论的门槛：#06/#11 的 OFF、#19 的 ON、#20 的 Responses OFF / Messages OFF 以及原生 SDK 多工具/推理字段夹具；最终支持表见 [矩阵](../../../docs/research/model-provider-matrix.md)。跨协议/思考模式变更的历史转换与完整工具批次边界见模型接入设计、#07/#08，停止/steer/压缩保留原生字段见 #19/#20。模型身份、USER_PROVIDED 窗口、未知价格及保密调用证据均已记录。用户现明确取消 OpenAI/GLM/Kimi 三家目标，原 phase 缺口不再阻塞阶段；既有 SDK 和明确拒绝不变，无未经确认的技术路线变更。本票契约验收通过，以下未验证描述保留为当时的核验记录，当前状态以上述矩阵为准。

### 官方核验与首条链路子结论（2026-11-13 UTC）

用户已授权连续实施 #01–#22，自行核验待决技术项并记录。本子结论解除下游 DeepSeek 非思考链路的具体 SDK 门槛，不把剩余矩阵或正式业务兼容性宣称完成。

- 正式服务 POM 尚无 LangChain4j；采用同版本 **1.23.0** 的 `langchain4j-core`、`langchain4j-open-ai`、`langchain4j-anthropic` 与 JDK HTTP 适配。均为已发布成熟模型 API，不引入 AI Services/agentic 或自研供应商 JSON 协议。版本依据 [Central metadata](https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-open-ai/maven-metadata.xml)及对应发布源码，不继承 PoC 版本。
- 原生类：`OpenAiStreamingChatModel`（Chat Completions）、`OpenAiResponsesStreamingChatModel`（Responses）、`AnthropicStreamingChatModel`（Messages）。文本、thinking、工具片段/完整调用、ID 与 nullable usage 均有 SDK 字段。Anthropic 的签名/遮蔽 thinking、Responses 加密 reasoning 不能只存可见文字。使用 SDK `ChatMessageSerializer`/`ChatMessageDeserializer` 保留 JSON-compatible attributes、thinking、tool ID/参数，不手写通用消息序列化。
- 使用 `StreamingChatModel.chat(ChatRequest)` 的 **Java Flow Publisher**，只订阅一次；从 `onSubscribe` 保存 Subscription，可在首 Token 前取消。回调版只能在 partial context 取 handle，取消还可能抑制终态回调，因此不能依赖其通知业务停止。应用另设整次 wall-clock deadline，并自行收敛取消记录；取消不证明供应商未计费。
- 同步 Chat/Anthropic `maxRetries(0)`；流式及 Responses 发布源码无模型重试循环。SDK connect/read timeout 不等于完整流 120 秒截止，必须外层计时。SDK 请求/响应日志关闭。
- JDK SDK transport 默认可注入 `java.net.http.HttpClient.Builder`，明确 Redirect.NEVER。其缓冲条数不限制未完成 SSE 行或错误正文；拟以 JDK BodySubscriber 的实际字节边界装饰该官方 transport，保留原生协议/取消，不重写供应商通信。需以实际 SDK 接口编译及针对测试验证再交付，不能只靠源码认定实现。
- [DeepSeek 当前官方目录](https://api-docs.deepseek.com/quick_start/pricing)为 `deepseek-flash`（V4.1-Flash-1017）和 `deepseek-v4-pro`，1M 名义窗口、默认思考；首条链路显式 `thinking:{type:"disabled"}`，不复制旧 `deepseek-chat/reasoner` 示例。Chat 适配可 `customParameters` 设置开关；思考模式须 `returnThinking(true)`/`sendThinking(true)`，按[当前规则](https://api-docs.deepseek.com/guides/thinking_mode)保留先前 reasoning_content，不套用旧“清空旧轮 thinking”假设。
- 已知窄缺口：原生 Responses 的 thinking 回填依赖 encrypted_reasoning，DeepSeek Responses 采用明文 reasoning。**不声明此组合兼容**；首条 DeepSeek 使用 Chat Completions。三协议仍须完成，但供应商×协议不是笛卡尔积，未更换 ADR 0001 技术路线。
- 当前进程 DEEPSEEK 非空已仅检查存在性，未展示或读取其值作报告、未发收费请求，不能推导凭证有效。真实费用调用按 P2-06/11 显式触发和记录。

| 目标组合 | SDK/官方结论 | 真实状态/后续门槛 |
|---|---|---|
| DeepSeek / Chat / 非思考 | 原生支持，显式禁用 thinking | 未验证；P2-06 两类检查及 P2-11 业务链路 |
| DeepSeek / Chat / 思考 | 须保留 reasoning_content 全配对 | 未验证；P2-19 |
| DeepSeek / Responses / 思考 | SDK 明文 reasoning 回填缺口 | 不声明支持；不代替其他 Responses 验收 |
| OpenAI / Chat、Responses | 原生适配存在 | 未验证；P2-20 分别留实测记录 |
| Anthropic Messages / 预设或自定义兼容端 | 原生适配存在，签名字段须保留 | 未验证；P2-20 确定支持组合 |
| GLM、Kimi / 官方支持格式 | 目录/窗口/模式尚须逐一核验 | 未验证；P2-20，不凭名称承诺兼容 |

发布源码依据：[core](https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-core/1.23.0/langchain4j-core-1.23.0-sources.jar)、[OpenAI](https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-open-ai/1.23.0/langchain4j-open-ai-1.23.0-sources.jar)、[Anthropic](https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-anthropic/1.23.0/langchain4j-anthropic-1.23.0-sources.jar)、[JDK transport](https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-http-client-jdk/1.23.0/langchain4j-http-client-jdk-1.23.0-sources.jar)。没有运行安装或真实模型实验；下一步核验正式编译、传输上界和取消行为。

### 发布接口落地补充（2026-11-14）

正式 1.23.0 编译及 SDK fixture 已通过；HTTP 字节边界与取消见 P2-06 / `570d05a`。新增原生三协议工厂与单次驱动仍是本地业务切片，**所有真实矩阵保持未验证**。JDK builder 的 `streamingBackpressure(true)` 与 `streamingBufferSize` 不能同时设；使用原生背压，不将事件条数当字节上限。OpenAI Chat 使用 `maxCompletionTokens`，DeepSeek/兼容 Chat 使用 `maxTokens`，Responses 使用 `maxOutputTokens`，Anthropic 使用 `maxTokens` 加显式 thinking budget。Responses `store(false)`、不设置 previousResponseId；思考时请求 encrypted reasoning，DeepSeek Responses 思考缺口继续明确拒绝，不切到 Chat。

官方增量证据：[DeepSeek thinking 参数表](https://api-docs.deepseek.com/guides/thinking_mode)明确 Responses `reasoning.effort=none` 关闭默认思考；[GLM thinking](https://docs.z.ai/guides/capabilities/thinking-mode)明确 `thinking.type=disabled`，且 `glm-5.3` / `glm-5.3-flash` 强制思考不能关闭；Coding Plan/标准端 preserved-thinking 默认不同，启用时要 `clear_thinking:false` 并保留完整 reasoning；[Moonshot K2.5 官方仓库](https://github.com/MoonshotAI/Kimi-K2.5)示例明确官方 API 的 instant 模式为 `thinking:{type:"disabled"}`（不是 vLLM 的 chat_template_kwargs）。工厂保留这些模式区别，已知强制模式不兼容时本地拒绝，不能把无工具 HTTP 200 视为模型可用。此处不承诺任意旧模型接受 `reasoning_effort=none`；实际检查明确失败而不静默回退参数/模式。检索的 raw README URL 返回 404，以上 Moonshot 依据为成功取得的官方 GitHub 页面，不把失败抓取算证据。
