# P2-19：DeepSeek 思考模式完整适配

**Type:** implementation
**Status:** needs-info
**Blocked by:** P2-01 模型 SDK 与协议兼容性；P2-14 恢复与竞争场景验收；P2-15 自动与手动上下文压缩
**Spec:** [第二阶段规格](../spec.md)

## What to build

在同一正式 Loop 上启用并真实验收 DeepSeek 思考模式，验证供应商要求的推理字段、工具消息和控制操作，不用非思考模式成功冒充。

## 开工门槛

第一阶段达到 Dev Done（功能、L1 与代码审查完成，不含远程 CI 或重型验证），P2-01 核实实际模型/SDK/协议字段及适配方案；前置运行控制、恢复与压缩完成。实施时重新核对 [官方思考模式文档](https://api-docs.deepseek.com/guides/thinking_mode)，不依赖旧示例模型名。

## Acceptance criteria

- [ ] 显式配置并记录本次实际思考模式，不自动降级为非思考，不忽略供应商必需的 reasoning_content 或等效字段。
- [ ] 流式文本/推理/工具参数、调用 ID 与回填消息完整保存和重组，参数未完成不执行工具，协议错误不伪装普通回复。
- [ ] 多轮、多工具、审批暂停/拒绝、auto 的真实闭环通过，不重新生成已完成批次。
- [ ] 停止、steer、重启恢复和压缩后的上下文符合该协议要求；需补结果而非任意伪造新工具调用，具体行为依已核验契约。
- [ ] 用量、上下文与存储计量包含实际供应商口径，推理字段不因流分片重复计数；缺失数据仍标未知。
- [ ] 可控协议夹具与真实 DeepSeek 验收分别记录；显式付费触发且不泄漏环境 Key，真实配置不可用时标未验证。

## Comments

此票不替代 P2-20 的完整供应商矩阵。字段和 SDK 缺口须先反馈确认，不静默改选型或缩减支持。

### 核验与实施决定（2026-11-15）

按用户持续推进授权重新读取官方 thinking/tool-calls 文档（`https://api-docs.deepseek.com/guides/thinking_mode`、`/guides/tool_calls`）。当前 `deepseek-flash` 默认思考，OpenAI Chat 显式 `thinking.type=enabled/disabled`；原生 `reasoning_effort` 可取 low/high/max，映射规则以官方文档为准。只要携带 tools，必须回传所有前轮 `reasoning_content`，包含没有工具调用的 assistant 轮；不能套用旧版清空 reasoning 的做法。无 tools 的摘要调用可不回传旧 thinking；温度等参数在思考模式下供应商忽略，不把它们描述为有效调节。

现有正式工厂已设置 returnThinking/sendThinking、显式模式与原生 SDK serializer；本票先补原生流式协议往返证据，再复用正式 Loop 做思考控制验收。不变更 SDK 选型、不补伪造 reasoning、不把 OFF 成功当 ON。确定性边界选用实际 ModelCallDriver/工厂/SDK + 本机 HTTP 流夹具（仅测试的 URL 验证替身，不给产品加 HTTP/TLS 例外），覆盖分片思考/多工具/回填及无工具 assistant 轮；真实调用独立 opt-in 并设测试专属次数上限。

### 实现与验收证据

现有正式 SDK 工厂无需替换。新增 `DeepSeekThinkingProtocolTest`，实际 driver/factory/SDK 处理分片 reasoning、分片双工具参数、ID/结果配对、序列化往返、无工具助手之后的新用户轮次回传，断言原生 cache/reasoning/total usage，文本预览不混入推理。配套 driver 测试覆盖截断不得成为工具批次、终态 opaque 字段预算、超时/取消及错误分类。PG 停止与 steer 用例同时运行 OFF/ON；保留原始 thinking，原 ID 补齐未执行结果，重建 worker 后不重做批次。上下文计划测试覆盖 thinking 原文、完整批次及不可压缩指导。

真实端点 `https://api.deepseek.com`，`deepseek-flash`，Chat，明确 ON/low、输出上限 4096：
- 批准：4 次请求，重建 runner 后继续原批次，确认写入一次并读取已保存结果；native reasoning 长度 `[0,42,0,0]`。
- 拒绝：3 次请求，写入零次、不再提案；长度 `[0,27,0]`。
- AUTO：4 次请求，原提案授权自动写入一次；长度 `[0,27,0,0]`。
- 思考摘要→继续读取→最终回复：3 次请求，摘要 APPLIED，input/output/total 分别 `5560/1006/6566`、`4611/48/4659`、`4701/21/4722`。

上述通过链路共 14 次真实请求；Go 文件/写入仍是确定性夹具，不冒充全进程浏览器验收。首轮三条审批链路已完成业务但被“每轮 reasoning 必须非空”的错误断言判失败，单条硬上限 8（合计上限 24），未输出准确次数，不纳入上述 14 次通过计数。官方契约与返回事实不支持非空保证；修正为原生消息与持久化逐条完全相等，并要求本次 ON 验证至少出现一条真实 reasoning。没有改模式、重造 reasoning 或加生产回退。

显式 `COPILOT_LIVE_DEEPSEEK=1`；可用 `COPILOT_LIVE_DECISION=APPROVE/REJECT/AUTO` 仅运行一个场景，每场最多 8 次；摘要场景最多 4 次。调用次数不是固定金额承诺。证据：`%TEMP%/deepseek-thinking-protocol.log`、`deepseek-thinking-fixture.log`、`deepseek-thinking-controls.log`、`deepseek-thinking-live-approve.log`、`deepseek-thinking-live-REJECT.log`、`deepseek-thinking-live-AUTO.log`、`deepseek-thinking-live-summary.log`。原始摘要证据在忽略目录 `backend/server/target/copilot-summary-live-b29ed0ff-5768-4461-bfac-e0158bac742c.json`。完整受影响 L1 与双轴审查在阶段收口继续执行，供应商矩阵另见 #20。
