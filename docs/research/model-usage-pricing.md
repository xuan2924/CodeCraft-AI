# 用量口径与官方价格核验

核验时间：2026-11-15T02:04:35Z（本次重新读取官方价格页，价格与前次观察一致）。用于 P2-16；文档/SDK 核验不替代真实供应商字段样本。

## 一手证据

- [DeepSeek 当前价格](https://api-docs.deepseek.com/quick_start/pricing)：Flash 每百万 Token 非高峰输入未命中 USD 0.15、命中 0.003、输出 0.60；高峰分别 0.30、0.006、1.20。V4 Pro 分别 0.66/0.022/1.98，高峰 1.32/0.044/3.96。当前 Flash 模型 ID 为 `deepseek-flash`，页面明确列出的 `deepseek-v4-flash`、`deepseek-v4-flash-vision-exp` 别名按 Flash 收费，不从任意名称前缀猜测价格。
- 同页规定高峰为周一至周五 UTC `[01:00,04:00)` 和 `[06:00,10:00)`，中国公共假期除外；其余时段、周末、公共假期为非高峰。不能把调休上班的周末自行改成高峰。该页未给出当前整张价表的精确生效瞬间；[更新日志](https://api-docs.deepseek.com/updates/) 的 2026-10-17 条目说明该次 Flash 更新价格不变，不足以证明任意更早日期的价表。
- [国务院办公厅 2026 放假安排的政府转载](https://zwfw.gansu.gov.cn/huixian/zczx/tzgg/art/2025/art_715c16a75e4d4c289c295e77772c7274.html)，国办发明电〔2025〕7号：1/1–1/3、2/15–2/23、4/4–4/6、5/1–5/5、6/19–6/21、9/25–9/27、10/1–10/7。中国假期使用 Asia/Shanghai 日期；高峰小时使用 UTC。未核验的日历年份不推测。
- [OpenAI Chat Completion usage](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)：`prompt_tokens_details` 是输入细分，cached/cache-write 不应再次加到 prompt 总输入。输出 reasoning 子项同样不重复加到输出。
- [Anthropic 用量口径](https://platform.claude.com/docs/en/api/rate-limits)：`total_input_tokens = input_tokens + cache_read_input_tokens + cache_creation_input_tokens`；[缓存说明](https://platform.claude.com/docs/en/build-with-claude/prompt-caching) 给出 200,000 缓存读取 + 50 普通输入 = 200,050 输入的示例。缓存写入时长会影响价格，缺少时长不能自行按 5 分钟估价。

## 当前 SDK 实证

检查本机已锁定的 LangChain4j 1.23.0 JAR（`javap -c -p`），不是假设 API：

- `OpenAiTokenUsage.InputTokensDetails` 提供 `cachedTokens()` / `cacheWriteTokens()`；OpenAI 内部 `Usage` 只列标准输入/输出及 details，没有 DeepSeek 独有 `prompt_cache_hit_tokens` 字段。若供应商只返回扩展字段，SDK 可能不能交付该细分；缺失必须显示未知，不能默认为零。
- `AnthropicMapper.toTokenUsage` 原样映射普通 `inputTokens`，另外映射 cache read/creation。`AnthropicTokenUsage` 的父类总量只由普通输入和输出构造，因此必须显式加上缓存输入后才是完整上下文/累计总量。
- 不自行解析供应商 HTTP 协议补造字段；继续使用正式 SDK。真实调用若缺少收费必需细分，费用未知而不是套用未命中率。

### 真实检查字段样本（2026-11-15）

显式运行 `ModelConnectionPersistenceIT#realDeepSeekBasicAndSideEffectFreeToolChecksPassAndReuseTheirOriginalResults`，官方 DeepSeek、`deepseek-flash`、OpenAI Chat、非思考；基础 1 次、工具闭环 2 次，总计 3 次，复用 0 次新增请求。两项检查通过。原日志 `%TEMP%/model-usage-live-check.log` 仅输出数值证据，不含凭证或请求正文。

| 请求 | SDK 输入 | 缓存读取 | 缓存写入 | 输出 | 总量 | USD 估算 |
|---|---:|---:|---|---:|---:|---:|
| 基础 | 46 | 0 | null | 23 | 69 | 0.0000207 |
| 工具请求 | 349 | 128 | null | 62 | 411 | 0.000070734 |
| 工具续答 | 448 | 256 | null | 25 | 473 | 0.000044568 |

SDK 此次确实提供 `inputTokensDetails.cachedTokens`，`cacheWriteTokens` 与 `outputTokensDetails` 为 null。规范化保留未知写入与未知普通输入，标统计不完整；但 DeepSeek 的已核验价格仅区分命中/未命中，因此 `(input - cacheRead)` 可完整覆盖未命中（含写入），不要求伪造 write=0。本次估算合计 USD 0.000136002，以账单为准；不外推其他模型/协议字段兼容性。对应数值作为确定性回归样本。

## 实施选择

- 版本化、可审查的官方价表资源，发布时更新，不在请求中抓网页。每次派发保存不可变价格快照和来源；历史不随新价表/配置重标。仅官方预设、精确官方 URL、精确模型及已核验条件可以匹配；自定义地址、未核验模型/时段、缺失必需 usage 均未知。
- 本次观察窗口从 2026-11-15T02:04:35Z 起，保守限定至 2026-12-15 UTC；这是本项目确认覆盖期，不冒充供应商公布的生效日期。更早或过期请求不套用现价。扩展价表须同时核验来源、时间条件和日历；不猜其他供应商价格。
- 金额用十进制计算、以币种分别汇总；只给估算，账单为准。已知小计和未知调用数同时展示，不能把部分已知金额标成完整账单。缓存仍占输入窗口；最新占用与跨请求累计分开。
