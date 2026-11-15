# P2-20：三种协议与供应商支持矩阵

**Type:** implementation
**Status:** needs-info
**Blocked by:** P2-01 模型 SDK 与协议兼容性；P2-03 模型连接安全与准备规则；P2-14 恢复与竞争场景验收；P2-15 自动与手动上下文压缩；P2-16 上下文占用、用量与费用
**Spec:** [第二阶段规格](../spec.md)

## What to build

完成 Chat Completions、Responses、Anthropic Messages 正式适配，落实 DeepSeek、GLM、Kimi、OpenAI 预设及自定义连接，按实际支持组合发布有证据的矩阵。

## 开工门槛

第一阶段达到 Dev Done（功能、L1 与代码审查完成，不含远程 CI 或重型验证）；P2-01/03 给出 SDK/协议/安全结论，控制、压缩和 usage 路径具备。读 [模型接入](../../../docs/design/model-access.md)。实现前复核官方资料，不推定每个供应商支持每种协议。

## Acceptance criteria

- [ ] 三种协议通过统一客户端入口调用，模块不自行创建供应商客户端；缺配置或不支持能力明确失败，不静默探测切换或回退。
- [ ] 四家预设与自定义配置按已核实模型身份/窗口/能力呈现，不硬编码研究时的过期价格或假设兼容范围。
- [ ] 每个承诺支持组合验证基础连接、工具调用/回填、多轮/多工具、流式参数及必要推理字段；HTTP 200 不算闭环通过。
- [ ] 同一应用控制语义下验证审批、停止、steer、恢复、压缩和错误路径；协议差异在适配层显式处理，不丢字段凑通用消息。
- [ ] usage/缓存字段适配与缺失提示、费用来源和自定义未知价格正确；真实请求沿用 P2-03 的 HTTPS 地址校验、Key 更新及安全规则；不额外要求内网目标/DNS 重绑定防护，剩余 SSRF 风险见 ADR 0004。
- [ ] 记录矩阵的 SDK/模型/协议身份、核验日期、实际场景、通过/失败/未验证；不能仅通过 DeepSeek 就宣布其他供应商全部通过。
- [ ] 缺少凭证列为未验证并阻塞相关支持声明；无法满足阶段目标时提交缺口和方案请用户确认，不自行降级范围。

## Comments

本票不依赖 P2-19，可在共同前置完成后并行推进；DeepSeek 思考专门控制场景仍须 P2-19 独立验收。真实调用不默认进入每次 CI 回归。

### 当前交付、核验与明确阻塞（2026-11-15）

集中矩阵见 [供应商与协议证据](../../../docs/research/model-provider-matrix.md)，逐项区分官方文档、原生 SDK 夹具和真实供应商。四家预设/自定义仍由用户显式填写实际身份与窗口，标 USER_PROVIDED；不将动态文档或名义窗口自动写回配置，不新增臆造价格。

- 新增真实 SDK 的 Responses / Anthropic 双工具流式往返，验证 encrypted reasoning、thinking signature、redacted thinking、回填 ID、无服务端历史以及原生 usage/cache。三协议控制夹具验证停止/steer/worker 重建保留 opaque 字段；压缩测试确认 retained 字段保留但不塞入摘要指令。
- Responses 末尾斜杠造成双斜杠错误路径已红测复现并修复。只规范化无 query 的末尾路径分隔符，不改用户查询串、不探测其他地址。
- 重新读取当前 Kimi/GLM 官方资料：Kimi K3/K3.1 Pro/Highspeed、K2.7 Code/Highspeed 强制思考，扩展明确 OFF 拒绝；不沿用旧 K2.5 兼容性推断。原有 GLM-5.3/Flash 拒绝保持。
- 当前 OpenAI 指南说明 Responses assistant `phase` 对工具流程连续性重要；正式 1.23.0 缺少往返映射，Maven 最新也仍为 1.23.0。已知受影响 GPT-5.4/5.5/5.6、GPT-6/6.1 Responses 组合明确拒绝；官方不支持工具的 GPT-6 Astra / GPT-6.1 Sol Chat 也拒绝。**保留 SDK 支持缺口，未自研协议、未切 SDK、未将目标降级为完成。** 继续条件是原生 SDK 补齐并验证，或另行确认另一正式 SDK 路线。
- DeepSeek `deepseek-flash` Responses OFF、Anthropic OFF 均完成正式 Loop 审批续接，各 4 次真实请求；个人基础/工具检查各 3 次，复用零次。两协议新增通过请求共 14 次，硬上限为每条 Loop 8、每组检查 3，全部显式 opt-in。实际 Key 不输出。Go 仍是确定性夹具，不称全进程 E2E。
- 真实 Anthropic SDK inputTokenCount 不含 cache read/create；UI/费用使用原有 `usage_facts` 规范化口径，不把原始日志计数误当占用。未核验协议/地址仍价格未知。

定向结果：`%TEMP%/provider-matrix-directed.log` 通过原生三协议、工厂能力拒绝、driver/transport、上下文规划、usage，以及 PG 三协议停止/steer；`deepseek-alternate-protocols-live.log`、`deepseek-alternate-checks-live.log` 为真实证据。协议夹具曾因单 credit 尚被关闭中的 transport 持有而得到 RUNTIME_BUSY，已将夹具固定 credit 设为其固定调用次数 2/3；不改生产并发控制、不加等待或重试。

**未完成/阻塞：** 没有 OpenAI、GLM、Kimi 或真正 Anthropic 凭证，不能补造这些供应商的通过记录；OpenAI phase SDK 缺口尚在。此票不能标为完整供应商支持，第二阶段也不能据此宣称全完成。先继续 #21/#22 的无依赖部分，最后集中交接缺口与处置选项。提交 `d3be51c` 的独立 Standards / Spec 审查均无新增问题，明确确认这些是已披露交付缺口而非已完成证据。
