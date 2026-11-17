---
status: accepted
---

# ADR-0004：自定义模型地址仅校验 HTTPS 且禁止重定向

## 背景

平台允许可信受邀用户填入自定义模型连接的 Base URL（例如公司内部兼容 OpenAI/Anthropic 协议的代理网关或私有部署推理服务）。如果强制限制“仅允许公网 IP/域名”，内网调试与私有网关将无法接入。

## 备选方案

- **方案 A（仅校验合法 HTTPS URL + 禁止 HTTP 重定向）**：保障传输层 TLS 加密与合法 URI 结构，通过 `Redirect.NEVER` 防止 3xx 重定向泄露 Authorization/API Key。
- **方案 B（强制公网 IP + DNS 解析防重绑定检查）**：拦截所有内网地址与回环段。

## 决策与代价

在当前“仅面向可信受邀用户”的前提下，选择 **方案 A（校验 HTTPS + 有效 Host + `Redirect.NEVER`，不做内网 IP/DNS 黑名单拦截）**。

- **收益**：同时支持公共模型供应商与内网私有推理网关，避免复杂的 DNS 防重绑定维护成本。
- **代价与复议条件**：明确保留可信用户将 Java 后端作为跳板请求内网 HTTPS 服务的 SSRF 剩余风险；一旦开放公开注册或多租户使用，必须切换为外网隔离代理或补全目标 IP 校验。

## 关键源码

- `ModelConnectionValidator.java`、`BoundedModelHttpClientBuilder.java`
- 设计详情：[模型接入与安全](../design/model-access-and-security.md)
