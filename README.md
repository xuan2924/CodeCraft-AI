# CodeCraft-AI

面向可信受邀用户的 AI 结对编程面试平台：Java 负责业务、授权与持久任务，Go 负责私有工作区和 Docker 执行，Vue 提供 Web IDE。

已实现受邀账号、题库与收藏、工作区/草稿、隔离测试、Copilot 多会话与 ASK/AUTO 审批、开发 Bash、代码历史撤销/回滚、Skill、上下文压缩、子任务和联网资料工具；完整面试流程包含平台角色配置、澄清、暂停/恢复、可选挑战、冻结验收、回顾评测、中文 PDF 与授权反馈。**存在接口不等于任意模型或部署环境都已验证。** 当前供应商承诺为 DeepSeek 与自定义连接契约，详见 [模型矩阵](docs/research/model-provider-matrix.md)。

- 新开发者从 [开发导航](docs/README.md) 开始，不必先读历史票据。
- 当前职责、产品与隔离边界见 [架构](docs/design/architecture.md)。
- 本次历史清理依据、取舍及流程建议见 [全历史复盘](docs/research/history-and-simplification.md)。

## 环境

- JDK Temurin 21.0.11、Go 1.26.5、Node 24.9.0、npm 11.6.0。
- Maven 3.9.15 使用 `backend/server/mvnw` / `mvnw.cmd`，无需全局安装。
- Docker Engine / Docker Desktop **Linux containers** 与 Docker Compose；PostgreSQL 由 Compose 提供。
- 契约生成支持 Windows/Linux x64。Java/Go 服务及跨端集成测试运行在同一个 Linux daemon 时钟域；Windows 可运行编辑器、Vite、浏览器及本地编译/单测。Go 非 Linux 桩仅服务编译，不是产品运行入口。

## 初始化与运行

在根目录复制 `.env.example` 为未跟踪的 `.env`，填写数据库、初始管理员和 `IDENTITY_FINGERPRINT_KEY`。凭证用安全随机工具直接写入本地文件，不放命令参数、聊天或 Git。配置按字面量解析，不支持变量展开/命令替换；**不要 `source .env`**。

```sh
npm --prefix frontend ci
# Linux；Windows PowerShell 使用 ./scripts/generate-proto.ps1
./scripts/generate-proto.sh
node scripts/dev.mjs postgres
```

发布当前首题（购物车 1.1.0，45 分钟，含一轮可选挑战）：

```sh
node scripts/publish-problem.mjs
```

此命令验证基础和挑战各自的初始工程、参考解及隔离条件，生成 `.runtime/problem-publication.json`。发布绑定本机 Docker daemon 和不可变镜像 ID；不代表跨主机签名分发。按 `.env.example` 设置 `PROBLEM_PUBLICATION_FILE` 和随机 `SANDBOX_RPC_KEY`，再启动：

```sh
node scripts/dev.mjs sandbox
node scripts/dev.mjs java
node scripts/dev.mjs web
```

访问 **http://localhost:5173**（或配置的 `WEB_PORT`），始终使用 `localhost` 和 Vite 入口，避免 Cookie/CSRF 跨源问题。服务宿主端口只绑定 loopback，当前环境不应直接公开或配置公网反向代理。Go 在线不表示题目执行已就绪。

- 首次启动创建初始管理员；已有管理员时不重置密码。登录 `/account` 完成强制改密，再邀请普通用户。临时密码只展示一次，需可信交付。
- 管理员先在平台角色设置发布 INTERVIEWER/EVALUATOR 配置；新演练固定对应版本。普通用户在 `/models` 保存连接、模型并显式检查（可能收费），从题库创建演练；开始实现前必须满足个人模型检查与执行准备条件。
- 修改 Java/Go 代码后重新运行对应 `dev.mjs` 入口。Java 容器不挂源码、Go 工作区或 Docker socket；题目容器也不获得 socket。
- 关闭页面不停止 Copilot 或结束演练。结束会关闭作答、核对在途操作并冻结；之后只读，重新练习创建新演练。
- 数据库/Go 私有数据在具名卷持久化。**不要用 `docker compose down -v` 修复启动问题。** 数据卷不是备份；改变 `.env` 密码不改变旧卷内密码。已发布题目身份不可覆盖，若已有同版本旧发布，重新发布修改后的题包应使用新版本，不能手改回执或删除开发库绕过冲突。

## 模型凭证与可选联网

`MODEL_CREDENTIAL_ACTIVE_KEY_ID` 和 `MODEL_CREDENTIAL_KEYS` 仅传 Java。密钥环是逗号分隔的 `ID:Base64`，每项解码恰为 32 字节；active ID 必须存在。它是加密供应商 Key 的部署密钥，不是供应商 Key。丢失后不能解密旧凭证；无配置时明确失败，不回退环境中的模型 Key。

只保留 `DEEPSEEK` 与 `CUSTOM` 适配，协议仍可显式选择 Chat Completions、Responses、Anthropic Messages。自定义地址必须 HTTPS 且不跟随重定向，但不做内网目标隔离；运营方可接收请求和凭证。自定义模型需逐项检查，不凭模型名字推断功能或价格。部署密钥轮换的完整顺序见 [模型接入](docs/design/model-access.md#部署加密密钥轮换维护)。

联网资料工具使用 Tavily Search/Extract：`.env` 中可设置 `COPILOT_WEB_API_KEY`，可选 `COPILOT_WEB_BASE_URL`（默认 `https://api.tavily.com/`）及 `COPILOT_WEB_TIMEOUT`（默认 `20s`，最大 30 秒）。缺配置明确不可用，Key 不进入 Go/题目容器；资料 credits 与模型 Token 分开，未知费用不记为零。

## 日常验证

```sh
node scripts/verify.mjs --quick
# 按受影响范围定向执行
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run test:unit -- src/workspace/useExerciseTimer.test.ts
npm --prefix frontend run build
# Java：在 backend/server 下；Windows 改用 mvnw.cmd
./mvnw -Dtest=CopilotHttpTest test
# Go：在 backend/sandbox 下
go test ./internal/execution -count=1
```

quick 包括 Node 契约、Java 编译/Checkstyle/单测、Go 快速测试/vet/格式及 Vue 类型/Lint/组件测试，不依赖 Docker 或收费模型。热缓存 30 秒是优化目标，默认硬超时 60 秒；负载较高时可显式设置 `CODECRAFT_QUICK_TIMEOUT_SECONDS=180`（范围 31–600）。首次编译/下载与热缓存计时分开。

按需入口，不是每票必跑清单：

```sh
npm --prefix frontend run test:ui           # 组件浏览器夹具
npm --prefix frontend run test:integration # IndexedDB 浏览器集成，无后端
node scripts/test-execution.mjs            # Linux Docker 执行边界
node scripts/test-tracer.mjs --smoke --identity --workspace
node scripts/test-tracer.mjs --workspace --boundary-only
node scripts/verify.mjs --all               # 显式重型整套
```

真实浏览器/跨端测试需先生成 Java jar、前端 dist 并安装匹配 Chromium；tracer 创建独立临时库/卷，不读取开发 `.env`。测试协调容器可使用 Docker socket，因此是可信夹具，**不是用户沙箱**。真实模型测试必须单独显式选择并确认收费，不进入 quick/CI。详细命令与分层边界见 [工具链验证契约](docs/design/engineering-toolchain.md#验证命令契约)，历史证据见 [validation](docs/validation/README.md)。

GitHub 主分支 push/可选 PR 跑 quick，纯文档跳过；nightly 是非收费 smoke，heavy 仅手动触发。不要求强制 PR，不自动上传可能含凭证的诊断产物，不承诺公开部署或生产 SLA。

## 镜像管理

```sh
node scripts/clean-docker-images.mjs          # 默认只预览
node scripts/clean-docker-images.mjs --apply  # 明确确认后执行
# 可额外保护正在使用的镜像/发布
node scripts/clean-docker-images.mjs --keep IMAGE --publication FILE
```

仅清理当前内容寻址标签，保护当前构建、现存/停止容器及其发布引用；不再自动处理旧验证别名。不删除容器、卷、其他项目镜像或构建缓存，也不用 force。执行前停止本项目构建/测试启动操作；没有有效当前引用时拒绝猜测。历史格式镜像若仍存在，由维护者确认归属后单独处理。
