# 多协议模型接入、凭证加密与用量计费

## 核心痛点

用户需要自带 API Key 并自由切换模型，但不同模型供应商的协议格式、推理字段（如 DeepSeek `reasoning_content`）互不兼容；同时 API Key 属于高敏凭证，绝不能明文落库、回显给前端，或在切换/重放时泄露。

## 关键设计与安全边界

### 1. 三协议原生适配与供应商范围

- **支持协议**：显式选择 `OpenAI Chat Completions`、`OpenAI Responses` 或 `Anthropic Messages`（不静默猜测切换）。
- **供应商预设**：产品化支持 **DeepSeek**（Chat 非思考/思考、Responses 非思考、Anthropic Messages 非思考已完成实测）及 **CUSTOM（自定义 HTTPS 端点）**；早期规划的多厂商专属枚举已在清理阶段合并为统一 CUSTOM 契约。
- **思考模式字段往返**：DeepSeek Chat 开启思考模式并携带工具时，必须把历史 assistant 轮次的 `reasoning_content` 原样传回，同时把前端展示与协议持久记录解耦。

### 2. 多连接管理与演练内切换（ADR-0005 / ADR-0008）

- **个人配置与演练解耦**：用户在个人中心管理多个连接（一条连接共享 Base URL/协议/Key，下挂多个模型配置）。演练内显式选择“连接 + 模型”，修改个人配置不会静默篡改正在跑的演练，需要用户显式应用修订。
- **开始前强校验，演练中免测试切换**：进入演练实施阶段前必须完成一次真实模型检查；开始后在个人已保存模型间切换不再强制重新发收费探测，运行中切换在当前完整工具批次收敛后生效。
- **跨兼容域历史转换**：从不兼容的协议或推理模式切换过来时，必须等当前工具批次完整配对后，将旧批次转为带来源标注的只读文本历史，绝不伪造推理签名。

### 3. 凭证信封加密、密钥轮换与 SSRF 边界

- **信封加密 + AAD 绑定**：供应商 Key 使用 `MODEL_CREDENTIAL_KEYS` 密钥环通过 AES-GCM 加密入库，并将归属用户/平台版本 ID 作为 AAD（附加认证数据）防止密文跨记录替换；Java 内存外（日志、前端、Go 沙箱、Skill 快照）绝不出现明文 Key。
- **不停机平滑轮换**：通过 `MODEL_CREDENTIAL_ACTIVE_KEY_ID` 与 `MODEL_CREDENTIAL_ROTATION_ENABLED=true` 开关，后台低频（每 60s 至多 100 条）以 CAS 方式逐条用新主密钥重包裹密文，坏记录不阻塞后续轮换。
- **HTTPS + 禁重定向（ADR-0004）**：自定义 Base URL 严格校验为合法 HTTPS（拒绝 userinfo/fragment）并配置 `Redirect.NEVER` 防止 3xx 偷带凭证跳转；基于当前可信受邀用户前提，允许访问内网 HTTPS 网关（明确保留 SSRF 风险边界）。

### 4. 用量与费用记账

- **实测与估算分离**：仅以供应商实际返回的 `inputTokens / outputTokens / reasoningTokens / cacheTokens` 记账，缺失 `usage` 或未知单价时明确标记未知，**绝不估算充数或标零误导**。
- **分账统计**：用户 Copilot/摘要/连接检查费用归用户侧；平台面试官/评测官与发布检查费用单独统计在平台侧；联网搜索（Tavily）credits 单独列示。

## 关键源码入口

- 配置与选择：`ModelConnectionService.java`、`ModelConfigurationService.java`、`ExerciseModelService.java`、`ModelCheckCoordinator.java`
- 调用与客户端：`ModelInvocationService.java`、`ModelSdkClientFactory.java`、`BoundedModelHttpClientBuilder.java`、`ModelConnectionValidator.java`
- 加密与计费：`ModelCredentialCipher.java`、`ModelCredentialRotationCoordinator.java`、`ModelCostCalculator.java`
