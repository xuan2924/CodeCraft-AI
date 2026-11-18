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

### 3. 版本化密钥环加密、在线轮换与 SSRF 边界

- **版本化密钥环直接加密 + AAD 绑定**：由于每条凭证仅为 `< 4 KB` 的短 API Key，`AES-GCM` 直接加解密耗时在微秒级、开销远小于数据库行更新，因此不额外维护每行独立的数据密钥（DEK）双层结构，而是直接使用 `MODEL_CREDENTIAL_KEYS` 密钥环中的活动主密钥（`activeKeyId`）进行 `AES-GCM` 加密，并将归属用户/平台版本 ID 与 `keyId` 作为 AAD（附加认证数据）绑定，防止密文跨记录或跨用户替换；Java 内存外（日志、前端、Go 沙箱、Skill 快照）绝不出现明文 Key。
- **不停机平滑轮换**：通过 `MODEL_CREDENTIAL_ACTIVE_KEY_ID` 与 `MODEL_CREDENTIAL_ROTATION_ENABLED=true` 开关，后台低频（每次间隔 60s、每批至多 100 条）在归属者行锁下先用旧 `keyId` 解密校验，再用新活动密钥重新加密并以 CAS 方式更新，坏记录跳过且不阻塞后续轮换。
- **HTTPS + 禁重定向（ADR-0004）**：自定义 Base URL 严格校验为合法 HTTPS（拒绝 userinfo/fragment）并配置 `Redirect.NEVER` 防止 3xx 偷带凭证跳转；基于当前可信受邀用户前提，允许访问内网 HTTPS 网关（明确保留 SSRF 风险边界）。

### 4. 用量与费用记账

- **实测与估算分离**：仅以供应商实际返回的 `inputTokens / outputTokens / reasoningTokens / cacheTokens` 记账，缺失 `usage` 或未知单价时明确标记未知，**绝不估算充数或标零误导**。
- **分账统计**：用户 Copilot/摘要/连接检查费用归用户侧；平台面试官/评测官与发布检查费用单独统计在平台侧；联网搜索（Tavily）credits 单独列示。

### 5. 容器 CLI 的 Host LLM Proxy

- **受控通道**：Compose 中仅可信 Go 协调器配置 `SANDBOX_LLM_HOST_URL=http://java:8080`。执行容器仍在独立 internal bridge；`egress:3128` 仅反代三个精确 POST 路径：`/internal/llm-proxy/anthropic/v1/messages`、`/internal/llm-proxy/openai/v1/chat/completions`、`/internal/llm-proxy/openai/v1/responses`。拒绝 query、编码路径和任意宿主 CONNECT；原 Maven/npm 公网白名单不变。
- **签发与隔离**：Go 在受信任 `ConfigureTerminalEnvironment` 回调内以 RPC 内部密钥调用 Java `/internal/llm-grants`，该签发路径不对执行容器开放。Java 签发绑定 terminal task、exercise、user、gateEpoch 的 15 分钟 HMAC 凭证；签名密钥仅在 Java 进程内，重启全部失效。容器的 `CODECRAFT_PROXY_TOKEN`、`ANTHROPIC_API_KEY`、`OPENAI_API_KEY` 均是该临时令牌；真实模型 Key 只由 Java 解析并加入上游请求。不进入 Input、恢复标签或应用日志；到期需重开终端，不提供无授权续租。
- **鉴权与吊销**：独立 SecurityFilterChain 接受三种代理认证头，不使用 Cookie/CSRF，原 `/api/**` 策略不变。每次调用及长流每 200ms 检查终端所属 session/authVersion、ACTIVE、OPEN、epoch、截止时间、停止/静默状态；数据库异常失败关闭。响应提交前吊销返回 403；提交后的 SSE 尽可能发送 `error/forbidden`，取消上游并断流，不能更改已提交的 HTTP 200。静默上游也主动检测，不依赖新数据到达。
- **转发边界**：只使用演练已选连接/模型，协议不匹配返回 400，不替 CLI 转换历史；覆盖请求中的 model，限制输出到配置上限（至多 16384），禁止多候选 `n>1` 与 Responses 后台任务。真实上游仍遵守 HTTPS/禁重定向并保留连接自身配置的 query 参数（同时拒绝 CLI 入站请求携带 query）；不转发浏览器 Cookie、内部 RPC Key 或任意上游响应头。上游状态显式保留，正文中实际 Key 及其 JSON 转义形式脱敏，不自动重试；流式或非流式响应必须观察到各协议明确的完成事件（如 `message_stop`、`[DONE]`、`response.completed`）才标记 `SUCCEEDED`，提前断流一律记为 `UPSTREAM_INCOMPLETE` 且不按完整成功结算。
- **资源与记账**：32 个在途上游槽位、每用户 5 次/秒突发及 30 次/分钟、每演练 300 次/小时；请求 1MiB、响应总量 16MiB、JSON 8MiB、单个 SSE 事件 1MiB、单次最长 5 分钟。请求开始前通过 `StorageCapacityStore.admitAudit` 在 512 MiB 演练预算内校验并计入未完成记录预留（4096 B，完成后替换为实际行大小）。按供应商累计 usage 合并，不将重复流片段累加；cache read/write 分离，reasoning 为 output 子集单独留存。缺失字段/价格保持未知，中断调用不声明完整费用。
- **审计与兼容展示**：`model_proxy_call` 在转发前写 STARTED，完成后写状态、耗时边界、端点、请求/响应字节摘要、工具信号、usage 与价格快照，不保存原始提示/响应。异常退出留下 STARTED 表示未知，不伪造成功或重试；6 分钟后不再阻止删除。通过 `ExerciseDataContributor` 计入容量并随演练删除。复盘时间线在 `ReportPanel.vue` 标记 `CLI_PROXY`；Copilot 面板将 CLI 与原生请求合并为已有 `CHAT` 分组。

可选 Node CLI：当前题包/主测试镜像不保证含 Node。需要 Claude Code/Codex/Pi 时，在受控发布的开发镜像层预装固定版本 Node LTS 与 CLI；不要把凭证烘焙进镜像或通过 `docker build --build-arg` 传入，不在 `verify --quick` 安装或下载 CLI。CLI 必须使用注入的 Base URL 与临时 Key；协议必须与本场已选连接一致。

## 关键源码入口

- 配置与选择：`ModelConnectionService.java`、`ModelConfigurationService.java`、`ExerciseModelService.java`、`ModelCheckCoordinator.java`
- 调用与客户端：`ModelInvocationService.java`、`ModelSdkClientFactory.java`、`BoundedModelHttpClientBuilder.java`、`ModelConnectionValidator.java`
- 加密与计费：`ModelCredentialCipher.java`、`ModelCredentialRotationCoordinator.java`、`ModelCostCalculator.java`
- 容器 Host Proxy：`LlmProxyController.java`、`LlmProxyService.java`、`ProxyTokens.java`、`ProxyAuditService.java`
