# CodeCraft-AI

AI 结对编程面试平台。目前正式工程提供 **本机内部服务链路检查与受邀账号闭环**：浏览器 → Java → PostgreSQL / Go，以及管理员初始化、强制改密、创建账号、密码重置、登录和退出。AI、工作区及沙箱执行尚未接入；Go 在线不代表 Docker 或执行环境已就绪。

## 环境

- JDK Temurin **21.0.11**、Go **1.26.5**、Node **24.9.0**、npm **11.6.0**。
- Maven **3.9.15** 由已提交 Wrapper 下载并校验，不要求全局 Maven。
- Docker Engine / Docker Desktop（Linux containers）及 Docker Compose；无需安装 PostgreSQL。
- 自动生成入口支持 Windows x64、Linux x64。其他平台须先核对 protoc 二进制和 SHA-256，不从 PATH 随意选用 protoc。
- 全部下载发生在开发/构建阶段；本票不运行题目代码。

## 初始化与本地启动

在仓库根目录操作。复制 `.env.example` 为未跟踪的 `.env`，自行填写 `POSTGRES_USER`、`POSTGRES_PASSWORD`、`BOOTSTRAP_ADMIN_USERNAME`、`BOOTSTRAP_ADMIN_PASSWORD` 和 `IDENTITY_FINGERPRINT_KEY`；不要提交或分享该文件。数据库名/用户名使用小写字母开头的字母、数字、下划线。值按字面量解析，含空格、`#` 或双引号时用单引号包裹；不支持变量展开、命令替换、多行或嵌入单引号。不要用 `source .env`。

```sh
cd frontend
npm ci
cd ..
```

生成共享契约（同时生成 Java 与 Go）：

```powershell
# Windows PowerShell
./scripts/generate-proto.ps1
```

```sh
# Linux
./scripts/generate-proto.sh
```

启动数据库，再在三个独立终端启动服务（Windows/Linux 相同）：

```sh
node scripts/dev.mjs postgres
node scripts/dev.mjs sandbox
node scripts/dev.mjs java
node scripts/dev.mjs web
```

访问 **http://localhost:5173**（若修改 `WEB_PORT`，使用对应端口）。浏览器只通过 Vite 代理访问 Java。Java、Go、Vite 和 PostgreSQL 映射端口均绑定 `127.0.0.1`，**不得改为公网/局域网入口或为其配置公开反向代理**。此页面免登录的前提仅是本机可信联调，不是未来产品鉴权方案。

- 启动脚本显式读取根目录 `.env`，只按服务传递白名单键；前端与 Go 不接收数据库/管理员凭证，不打印配置值。Spring Boot、Go 本身不会自动读取 `.env`。
- Java 启动时 Flyway 在空库创建 `codecraft` schema；重复启动校验已发布迁移。数据库不可达、迁移失败或校验失败会阻止启动。不 baseline、clean 或忽略校验。
- 状态查询通过 MyBatis 实际检查数据库中的 schema，并通过有 1 秒 deadline 的真实 gRPC 检查 Go。数据库查询/连接也有超时；无后台自动重试。
- 首次启动在数据库事务中建立唯一初始管理员；缺少合法初始凭证时失败关闭。已有管理员则跳过，重启或改 `.env` 不能重置密码。初始化成功后可移除初始密码，但必须保留稳定的 `IDENTITY_FINGERPRINT_KEY`（密码变更幂等指纹使用的随机 HMAC 密钥，不入库）。
- **http://localhost:5173/account** 提供账号入口：首次登录必须改密并重新登录，管理员才能创建受邀账号。临时密码只在创建或重置成功时展示一次，需人工可信交付；未知结果使用原请求核对，重放不会重新交付密码。管理员按用户标识重置密码（建号成功时展示标识），撤销目标全部旧登录并强制首次改密；重置自己也会退出当前登录。
- 输入、会话、CSRF 和限流规则见[身份契约](docs/design/api-events.md#账号与登录契约任务-02)。本机代理下请求共享 loopback 限额；浏览器固定使用 `localhost` 和 Vite 入口，避免跨源或混用主机名导致拒绝。
- HMAC 密钥应在本机用安全随机生成器生成并直接写入未跟踪 `.env`，不要粘贴到对话、提交或命令参数。公开部署尚未验收；HTTPS 源配置会强制 Secure Cookie，但不能因此把本机状态接口暴露到公网。
- 停止应用使用各终端 Ctrl+C；数据库使用 `docker compose stop postgres`。开发数据使用具名卷保留；**不要执行 `down -v` 来修复启动问题**。改变 `.env` 密码不会自动改变已有卷中的数据库密码。
- 单独执行 `./mvnw spring-boot:run`（Windows `./mvnw.cmd`）或 `go run ./cmd/sandbox` 时，需自行显式提供环境变量；日常使用上面的启动脚本避免漏传。

## 验证

### 快速反馈

```powershell
cd backend/server
./mvnw.cmd -Dtest=StatusHttpTest test
./mvnw.cmd -DskipTests compile
# Linux 对应 ./mvnw
```

```sh
cd backend/sandbox
go test ./internal/status -run TestCheckReportsServingOverGRPC -count=1
```

```sh
cd frontend
npm run typecheck
npm run test:unit -- src/status/StatusPage.test.ts
npm run lint
npm run build
```

### 全套正式工程检查

先完成 `npm ci` 和契约生成，然后在根目录运行：

```sh
node scripts/verify.mjs
```

执行已有题目脚本测试、Java `verify`（Checkstyle、lint 编译、JUnit、Failsafe）、Go 格式/vet/普通测试/race/构建、Vue lint/类型/组件测试/生产构建及 npm audit。独立运行 Java `verify` 时 `StatusIT`、`IdentityIT` **明确跳过**；真实依赖集成必须运行下一节，不能将跳过称为通过。历史一次性 `backend/prototypes/` 不属于正式套件。

**Windows race 准备**：按 [工具链设计](docs/design/engineering-toolchain.md#windows-race-的-c-工具链) 下载 LLVM-MinGW **20261104 UCRT x86_64**，验证 ZIP SHA-256 为 `552191b5754c49a5a20723276a6fc56541ea1922c4c50b7b669022333fc1b40f`，解压到自己的工具目录。只为当前终端设置：

```powershell
$env:CC = '<工具目录>/llvm-mingw-20261104-ucrt-x86_64/bin/clang.exe'
node scripts/verify.mjs
```

旧全局 MinGW 会导致 race 无法启动，不应关闭 race 或修改全局 Go 配置来绕过。Linux CI 使用 runner 的兼容 C 编译器。

### 真实数据库 / Go / Java / 浏览器

先完成上述构建，并安装匹配的 Chromium：

```sh
cd frontend
npx playwright install chromium
# Linux CI: npx playwright install --with-deps chromium
cd ..
node scripts/test-tracer.mjs
# 账号 REST/PostgreSQL 边界、真实浏览器闭环及会话跨重启
node scripts/test-tracer.mjs --identity
```

该脚本不读取开发 `.env`：生成随机凭证、独立 Compose 项目/具名卷和独立端口，启动实际进程及 **Vite preview 的生产构建**，执行 Java Failsafe 集成测试及 Playwright：

1. 空库迁移后，两项依赖可用。
2. 停止 Go，仅 Go 不可用。
3. 恢复 Go、停止数据库，仅数据库不可用。
4. 恢复数据库、重启 Java，迁移校验后恢复可用。
5. 停止 Java，页面提示无法取得状态，不展示旧成功。
6. 数据库不可达时重新启动 Java，必须非零退出。

浏览器同时检查响应形状、HTTP 200/503、`no-store`、键盘重试和窄屏。没有替换浏览器 API。清理仅针对该次随机项目的进程、容器和卷；删除临时凭证文件，日志保存在忽略的 `.runtime/`。Docker/浏览器/依赖缺失均非零失败，不能算作通过。若终端被强制关闭，检查 `.runtime/cc-tracer-*` 对应的测试项目并只清理该项目，勿清理开发资源。

`--identity` 另外创建独立边界测试数据库，以显式测试时钟验证到期、续期和撤销，使用真实 PostgreSQL 验证事务及并发；生产进程没有测试时钟接口。浏览器实际完成管理员改密、创建用户、用户改密、越权拒绝和退出，并验证 Java 重启后数据库会话仍可用。该模式禁用 trace、截图和视频，避免记录一次性凭证。两个模式顺序运行（Maven/Playwright 的输出目录共用），不能把跳过的测试算作通过。

已有独立服务时，可在 `frontend/` 设置 `TRACER_WEB_URL=http://127.0.0.1:<端口>` 后执行 `npm run test:e2e -- e2e/status.spec.ts`；默认断言两项依赖可用。普通 `npm run test:e2e` 不会偷偷启动假服务。

## 契约与目录

```text
backend/proto/status.proto        唯一 Protobuf 源码，只定义内部状态查询
backend/server/                  Java HTTP 聚合、MyBatis 查询、Flyway 迁移
backend/sandbox/                 Go gRPC 在线检查，无数据库或 Docker 访问
frontend/src/status/             查询缓存、边界校验和状态页
frontend/src/auth/               登录、强制改密、账号管理；凭证不写入浏览器持久存储
scripts/                        初始化、生成、检查与真实链路测试
```

`GET /api/internal/status` 返回 `{"database":"AVAILABLE|UNAVAILABLE","sandbox":"AVAILABLE|UNAVAILABLE"}`。全部可用时 200，否则 503；固定字段，不返回内部连接信息、凭证或堆栈。该内部运维接口不冒充任务 05 的业务 REST/鉴权协议。

Go 生成文件提交，Java 生成文件仅在 `target/`。生成脚本固定插件版本、下载校验 protoc；Wrapper 分发也校验 SHA-256。CI 重新生成后检查 Go 契约漂移，并运行 Linux race 与真实服务链路；**添加了 CI 配置不代表远程 CI 已通过**。

具体决策见 [工程工具链](docs/design/engineering-toolchain.md) 和 [身份契约](docs/design/api-events.md)，执行证据见 [任务 06 验证记录](docs/validation/service-tracer-06.md) 、[任务 07 验证记录](docs/validation/account-login-07.md)和[任务 08 验证记录](docs/validation/password-revocation-08.md)。
