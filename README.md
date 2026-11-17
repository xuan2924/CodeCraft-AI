# CodeCraft-AI

面向 AI 结对编程面试的工程演练与评估平台：Java 主后端负责业务状态、授权与 AI 编排；Go 执行服务负责私有工作区存储与 Docker 隔离运行；Vue 3 提供 Web IDE。

- **理解项目与面试准备入口**：[开发导读与源码地图](docs/README.md)
- **核心概念对照**：[术语表](GLOSSARY.md)

## 环境要求

- JDK Temurin 21、Go 1.26、Node 24.9、npm 11.6（Maven 使用仓库自带 `mvnw` / `mvnw.cmd`，无需全局安装）
- Docker Desktop / Docker Engine（**Linux containers** 模式）与 Docker Compose

## 快速启动

1. **配置环境变量**：在根目录复制 `.env.example` 为 `.env`（已被 Git 忽略），填写数据库账号密码、初始管理员、随机 `IDENTITY_FINGERPRINT_KEY`（32 字节 Hex）、`SANDBOX_RPC_KEY`（32 字节 Hex）及 `MODEL_CREDENTIAL_*` 加密密钥环。`.env` 由脚本按字面量解析，**不要 `source .env`**。
2. **安装依赖、生成契约并启动数据库**：

```powershell
npm --prefix frontend ci
./scripts/generate-proto.ps1        # Linux 用 ./scripts/generate-proto.sh
node scripts/dev.mjs postgres
```

3. **验证并发布题包**（默认发布单文件题 `problems/cart-total/1.1.0` 至 `.runtime/problem-publication.json`；如需发布多文件题可传入 `problems/order-settlement-engine/1.0.0`）：

```powershell
node scripts/publish-problem.mjs
```

4. **分别启动沙箱、Java 后端与前端**：

```powershell
node scripts/dev.mjs sandbox
node scripts/dev.mjs java
node scripts/dev.mjs web
```

5. 打开 **http://localhost:5173**（固定使用 `localhost`，避免 Cookie/CSRF 跨源问题）。

> **体验提示**：
> - 数据库与 Go 工作区数据保存在 Docker 具名卷中，**不要用 `docker compose down -v` 处理启动问题**。
> - 首次启动会自动创建 `.env` 中的管理员账号，登录后需先改密，再到平台角色页面配置并发布 **INTERVIEWER** 与 **EVALUATOR** 模型；普通用户在 `/models` 配置个人模型并检查通过后即可开始演练。
> - 若需体验 Copilot 联网检索（`web_search` / `web_read`），在 `.env` 配置 `COPILOT_WEB_API_KEY`（Tavily）后重启 Java 即可。

## 常用验证与清理命令

```powershell
# 全仓库快速门禁（Protobuf 契约、Java 编译/Checkstyle/单测、Go 测试/vet、前端类型/Lint/单测）
node scripts/verify.mjs --quick

# 按模块定向测试
npm --prefix frontend run typecheck
npm --prefix frontend run test:unit -- src/workspace/useExerciseTimer.test.ts
./backend/server/mvnw.cmd -f backend/server/pom.xml -Dtest=CopilotHttpTest test
go -C backend/sandbox test ./internal/execution -count=1

# 真实 Docker 隔离与跨端烟测（按需手动运行）
node scripts/test-execution.mjs
node scripts/test-tracer.mjs --smoke --identity --workspace

# 安全清理过期构建镜像（先预览，确认后再 --apply）
node scripts/clean-docker-images.mjs
```
