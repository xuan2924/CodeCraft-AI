# 模型供应商与协议验证矩阵

核验日期：2026-11-15。正式 SDK：LangChain4j **1.23.0**（core / open-ai / anthropic / JDK HTTP）。应用自管 Loop、单次订阅、无隐式协议/模式回退；统一入口是 `ModelCallDriver` / `ModelSdkClientFactory`，不是业务模块各自创建客户端。

**协议存在、SDK 夹具通过、真实供应商通过是三种不同证据。** 本页不宣布四家供应商完整支持，不代表第二阶段全部完成。只有 DeepSeek 凭证可用；其他供应商没有实测身份、用量或费用。

## 实际支持证据

| 供应商 / 模型 | 协议 / 模式 | 官方或 SDK 状态 | 项目实际结果 |
|---|---|---|---|
| DeepSeek / `deepseek-flash` | Chat / OFF | 显式 `thinking.type=disabled` | 基础、工具检查及 Loop 既有证据见 P2-06/11/16/18；不借此证明 ON |
| DeepSeek / `deepseek-flash` | Chat / ON / low | 全部历史 assistant reasoning 原样回传 | P2-19：批准 4、拒绝 3、AUTO 4、摘要及续接 3 次通过请求；另有初轮断言失败，见原票披露 |
| DeepSeek / `deepseek-flash` | Responses / OFF | `reasoning.effort=none`，无服务端会话 | 基础+工具检查 3 次；复用 0 次；读取→提案→审批→重建 runner→已保存文件读取→最终回复 4 次，通过 |
| DeepSeek / `deepseek-flash` | Responses / ON | 供应商明文 reasoning 与 SDK encrypted reasoning 回填不匹配 | **不支持，派发前明确拒绝**；不切回 Chat |
| DeepSeek / `deepseek-flash` | Anthropic Messages / OFF | `/anthropic/v1/messages`；显式 disabled | 基础+工具检查 3 次；复用 0 次；同一审批续接链路 4 次，通过 |
| DeepSeek / `deepseek-flash` | Anthropic Messages / ON | 官方支持 thinking，但忽略 `budget_tokens` | **真实未验证**，不把原生 Anthropic 夹具当 DeepSeek ON 实测 |
| OpenAI / 待选实测模型 | Chat、Responses / 按模型能力 | 原生 SDK 存在；存在下述模型/字段限制 | **无凭证、未验证**；不能宣传 OpenAI 完整支持 |
| GLM / `glm-5.3` 等 | Chat、Responses、Messages / 按模型与账号权限 | 当前目录和账号计划约束见下文 | **无凭证、未验证**；不推定三个端点均可用 |
| Kimi / K3 系列等 | Chat、Responses、Messages / 按模型能力 | 当前目录已不是仅 K2.5；K3 不接受 thinking 开关 | **无凭证、未验证**；不能借旧 K2.5 示例宣布 K3 兼容 |
| 自定义端点 / 用户模型 ID | 用户显式选择协议/模式 | 仅验证统一客户端契约；未知扩展不保证无损 | **逐个连接检查**，不宣称任意兼容服务已通过；窗口 USER_PROVIDED、价格未知 |

DeepSeek 实测使用窗口 **100000（用户配置值，不是实测最大窗口）**，OFF 输出上限 2048/检查 128 与 256，Chat ON 输出上限 4096。当前官方目录标注的名义窗口与用户保守配置不是同一个事实。界面继续标记用户填写来源，不将本页研究值自动覆盖个人配置。

## 可控协议与应用控制证据

- `DeepSeekThinkingProtocolTest`：真实 SDK/driver，分片文本、reasoning、双工具参数及 ID、原生 serializer 往返；无工具 assistant 之后也保留 reasoning。
- `NativeProtocolRoundTripTest`：真实 Responses / Anthropic SDK/driver，分片双工具调用及回填；Responses encrypted reasoning、`store=false`、无 `previous_response_id`；Anthropic thinking signature、redacted thinking、同一 user 中完整 tool_result；原生 usage/cache 值与最终累计输出保留。
- `CopilotPersistenceIT`：Chat OFF/ON、Responses/Anthropic 原生字段形态的控制夹具覆盖停止、steer、原 ID 补结果、worker 重建；仍使用正式事务、落库与 Loop。真实三协议审批续接不重新生成原批次。
- `ModelCallDriverTest`：错误分类、截断不形成工具批次、完整 opaque 字段预算、首 Token 前取消、超时及单次订阅。`CopilotContextPlannerTest`：完整配对及 retained thinking、压缩边界。原生字段夹具不是 OpenAI/GLM/Kimi 的真实验收。
- 仅测试使用本机 HTTP 与 URL-policy 替身；真实请求仍经 HTTPS 校验、无重定向转发、原凭证版本与显式配置。没有增加生产 HTTP 例外。
- 运行期 credit 包含正在关闭的 native transport；协议夹具分别给固定 2/3 次请求留 2/3 个 credit，不通过 sleep/重试绕开资源规则，不更改生产并发预算。

### 已发现并处理的边界

1. Responses 1.23.0 直接拼接 `baseUrl + "/responses"`。末尾 `/` 可形成错误路径，HTTP 夹具红测已复现；工厂只规范化无 query URL 的末尾路径分隔符，不重写查询串、不探测其他地址。
2. 已核实强制思考的 GLM-5.3/Flash、Kimi K3/K3.1 Pro/Highspeed、K2.7 Code/Highspeed 不允许配置 OFF 后悄悄使用供应商默认 ON；工厂明确拒绝。K3 ON 不发送不受支持的 `thinking` 参数。
3. **OpenAI Responses assistant `phase` 是 SDK 缺口。** 当前官方指南要求工具型流程原样回传 phase，丢失会导致把前言当最终回答。1.23.0 的 `OpenAiResponsesClient` 没有 phase 映射，且把 reasoning 提取为单个 encrypted item，不能声称完整任意 output-item 往返。当前 Maven latest/release 仍为 1.23.0。对已知 phase-aware 的 GPT-5.4/5.5/5.6、GPT-6/6.1 家族 Responses 组合明确拒绝；GPT-6 Astra / GPT-6.1 Sol 的 Chat 工具调用也由官方明确不支持，直接拒绝。**这不是缩减验收目标后的完成声明。** 对应支持仍阻塞，需要原生 SDK 补齐/发布并验证；若要改用另一正式 SDK，须另行确认技术路线，不在此自研供应商 wire 或静默换协议。

## 当前官方目录与来源边界

- [DeepSeek Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing)：`deepseek-flash` / `deepseek-v4-pro`，名义 1M；[Thinking](https://api-docs.deepseek.com/guides/thinking_mode)规定工具场景回传全部 reasoning，effort low/high/max。价格另见 [费用核验](model-usage-pricing.md)，本页不复制单价。
- [DeepSeek Responses](https://api-docs.deepseek.com/guides/responses_api)：Base URL `https://api.deepseek.com`，无 previous_response_id/conversation；OFF 显式 none。官方支持不等于 SDK 支持明文 reasoning。
- [DeepSeek Anthropic](https://api-docs.deepseek.com/guides/anthropic_api)：HTTP `/anthropic/v1/messages`，thinking budget 被忽略。**本项目 LangChain4j Base URL 填 `https://api.deepseek.com/anthropic/v1`**，SDK 追加 `/messages`；不要直接照搬 Python SDK 的基础路径规则。不使用 `claude*` 自动映射名称冒充真实模型身份。
- [GLM-5.3](https://docs.z.ai/guides/llm/glm-5.3)：名义 1M、仅思考，effort low/high/max；[Thinking](https://docs.z.ai/guides/capabilities/thinking-mode)说明 preserved-thinking 与计划差异。模型页端点表、示例及账号订阅限制不完全相同，不自动互换 Coding Plan/标准地址；没有对应账号实测时保留未验证。
- [Kimi 参数目录](https://platform.kimi.ai/docs/api/models-overview.md)：`kimi-k3`、`kimi-k3.1-pro`、`kimi-k3.1-pro-highspeed` 名义 1M，always-on preserved thinking，不接受 `thinking`；K2.7 Code/Highspeed 为 256K 且强制思考，K2.6 为 256K、可关闭。[当前 Thinking](https://platform.kimi.ai/docs/guide/use-thinking-models.md)要求完整 reasoning 回传；K3 推荐 max_tokens≥16000，不把现有 2048-token 检查预算的可用性当成已实测。旧 Moonshot quickstart 已导向当前 Kimi 目录，不能继续把旧页面名当当前型号。
- [OpenAI reasoning](https://developers.openai.com/api/docs/guides/reasoning)、[Responses 迁移](https://developers.openai.com/api/docs/guides/migrate-to-responses)：effort/工具能力因型号而异；stateless encrypted reasoning 和 assistant phase 必须分别看待，保住前者不代表后者完整。模型目录动态页面本次未取得可靠完整窗口表，不编造 OpenAI 模型/窗口已验证记录。
- 不把官方名义窗口、用户配置窗口、平台字节保护上限互相替代。新模型身份/能力须再次核验；未知模型不套用已知价格。

SDK 版本与不可变源码：[Maven metadata](https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-open-ai/maven-metadata.xml)、[OpenAI 1.23.0](https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-open-ai/1.23.0/langchain4j-open-ai-1.23.0-sources.jar)、[Anthropic 1.23.0](https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-anthropic/1.23.0/langchain4j-anthropic-1.23.0-sources.jar)。

## 实际验证记录与继续条件

- `%TEMP%/native-protocol-roundtrip.log` 为尾斜杠红测，`native-protocol-roundtrip-green.log` 为修复后通过；`native-protocol-controls-green.log` 包含原生 SDK/控制回归。
- `%TEMP%/deepseek-alternate-protocols-live.log`：Responses OFF / Anthropic OFF 各 4 次真实请求；`deepseek-alternate-checks-live.log`：各 3 次真实请求并验证复用零次。以上新增共 **14 次**，与 P2-19 的 14 次通过请求分开。
- Anthropic 日志中的原生 `inputTokenCount` 不含 cache read/create；应用 `usage_facts` 将它们合并为实际输入占用。不能把日志原始总数当统一 UI 总数，也不能二次相加。未核验协议/地址的价格仍未知。
- 支持声明的剩余阻塞：OpenAI/GLM/Kimi（以及真正 Anthropic 服务）的凭证与具体模型闭环；OpenAI phase 原生 SDK 缺口。现有统一协议实现和 DeepSeek 三格式证据可以交付，但 **P2-20/阶段供应商完整目标不能标为完成**。后续应先确认正式 SDK 缺口处置及可用的目标供应商身份，再做对应最小付费验收，不重跑已通过矩阵、不默认新增重型故障矩阵。
