# CodeCraft-AI

AI 结对编程面试平台。目前提供 **本机工程底座**：服务状态、受邀账号、首题发布、独立工作区、编辑保存/文件操作/草稿，以及稳定版本隔离测试。实时日志、停止与恢复（#14）仍在整票收口；结束冻结与保留删除按 #15/#16 交付。AI 和完整面试流程不在当前已交付范围；Go 在线不代表执行环境已就绪。

## 环境

- JDK Temurin **21.0.11**、Go **1.26.5**、Node **24.9.0**、npm **11.6.0**。
- Maven **3.9.15** 由已提交 Wrapper 下载并校验，不要求全局 Maven。
- Docker Engine / Docker Desktop（Linux containers）及 Docker Compose；无需安装 PostgreSQL。
- 自动生成入口支持 Windows x64、Linux x64。其他平台须先核对 protoc 二进制和 SHA-256，不从 PATH 随意选用 protoc。
- 全部下载发生在开发/构建阶段；维护者发布验证会运行首题代码，浏览器测试入口依赖有效题目发布产物与执行环境。

## 初始化与本地启动

> **Java/Go 同一 Linux 时钟域：** `dev.mjs java` 构建 Java 应用镜像并由 Compose 启动；`test-tracer.mjs` 将应用及 Java 集成测试 JVM 均运行于同一个 Docker Linux daemon。Windows 可保留编辑器、Vite、浏览器及本地编译/单元测试，不再用 Windows JVM + Linux Go 验收截止时间敏感业务。首期信任共享系统时间，接受校时可能影响实际截止的风险，不建设专项时钟异常检测或人工恢复机制，也不为此新增单调计时改造；不修改自动校时、不增加授权宽限。决定见 [ADR 0002](docs/adr/0002-java-go-shared-linux-clock.md)及 [ADR 0003](docs/adr/0003-accept-shared-system-clock-risk.md)。

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

启动数据库后，在独立终端启动 Go、Java 和 Vite（Windows/Linux 同一入口；Java 启动前会执行 Maven package）：

```sh
node scripts/dev.mjs postgres
node scripts/dev.mjs sandbox
node scripts/dev.mjs java
node scripts/dev.mjs web
```

访问 **http://localhost:5173**（若修改 `WEB_PORT`，使用对应端口）。浏览器只通过 Vite 代理访问 Java。Java 在容器内监听 `0.0.0.0:8080`，通过私网访问 `postgres:5432` 和 `sandbox:9090`；其宿主 HTTP 映射、Vite 及 Go/PostgreSQL 的宿主映射端口均绑定 `127.0.0.1`，**不得改为公网/局域网入口或为其配置公开反向代理**。此页面免登录的前提仅是本机可信联调，不是未来产品鉴权方案。

- Java 使用固定摘要的 Java 21/Maven 基础镜像，非 root、只读根文件系统，只提供 `/tmp` 临时空间；不挂载源代码、Go 工作区或 Docker socket。修改 Java 代码后重新运行 `node scripts/dev.mjs java` 构建并替换容器，当前未提供热更新/远程调试端口。
- Go 启动脚本构建 Linux 静态二进制与本地镜像，通过 Compose 启动非 root 服务。私有数据使用 `sandbox-data` 具名卷；新容器可重新挂载，进程锁拒绝同时启动第二个存储所有者。未配置题目发布产物时仅提供在线查询；配置后提供工作区存储及受控测试执行；执行条件以发布产物和实际配置为准。
- 启动脚本显式读取根目录 `.env`，只按服务传递白名单键；前端与 Go 不接收数据库/管理员凭证，不打印配置值。Spring Boot、Go 本身不会自动读取 `.env`。
- Java 启动时 Flyway 在空库创建 `codecraft` schema；重复启动校验已发布迁移。数据库不可达、迁移失败或校验失败会阻止启动。不 baseline、clean 或忽略校验。
- 状态查询通过 MyBatis 实际检查数据库中的 schema，并通过有 1 秒 deadline 的真实 gRPC 检查 Go。数据库查询/连接也有超时；无后台自动重试。
- 首次启动在数据库事务中建立唯一初始管理员；缺少合法初始凭证时失败关闭。已有管理员则跳过，重启或改 `.env` 不能重置密码。初始化成功后可移除初始密码，但必须保留稳定的 `IDENTITY_FINGERPRINT_KEY`（密码变更幂等指纹使用的随机 HMAC 密钥，不入库）。
- **http://localhost:5173/account** 提供账号入口：首次登录必须改密并重新登录，管理员才能创建受邀账号。临时密码只在创建或重置成功时展示一次，需人工可信交付；未知结果使用原请求核对，重放不会重新交付密码。管理员按用户标识重置密码（建号成功时展示标识），撤销目标全部旧登录并强制首次改密；重置自己也会退出当前登录。
- 输入、会话、CSRF 和限流规则见[身份契约](docs/design/api-events.md#账号与登录契约任务-02)。本机代理下请求共享 loopback 限额；浏览器固定使用 `localhost` 和 Vite 入口，避免跨源或混用主机名导致拒绝。
- HMAC 密钥应在本机用安全随机生成器生成并直接写入未跟踪 `.env`，不要粘贴到对话、提交或命令参数。公开部署尚未验收；HTTPS 源配置会强制 Secure Cookie，但不能因此把本机状态接口暴露到公网。
- 停止应用使用各终端 Ctrl+C；数据库使用 `docker compose stop postgres`。开发数据使用具名卷保留；**不要执行 `down -v` 来修复启动问题**。改变 `.env` 密码不会自动改变已有卷中的数据库密码。
- 单独执行 `./mvnw spring-boot:run`（Windows `./mvnw.cmd`）仍需自行显式提供环境变量，但这不是正式跨端启动入口；特别是 Windows JVM 不再作为工作区截止时间敏感业务的运行基线。原生 `go run ./cmd/sandbox` 仅保留在线查询兼容入口；工作区运行方式固定为上述 Linux 容器，不将 Windows 文件系统当作原子发布后端。

## 本机首题发布与工作区

维护者执行 `node scripts/publish-problem.mjs`：重新验证 `problems/cart-total/1.0.0` 的初始工程、参考解及容器约束，再生成未跟踪的 `.runtime/problem-publication.json`。产物绑定输入摘要、Docker daemon 和不可变本地镜像 ID，不是跨主机镜像发布或签名分发方案。

在未跟踪 `.env` 中按 `.env.example` 设置 `PROBLEM_PUBLICATION_FILE` 和随机 `SANDBOX_RPC_KEY`，重新启动 Go/Java。密钥只用于内部 RPC，不打印或发送给浏览器。发布产物不能手工改内容后沿用旧验证回执；修改题目需重新验证、发布新版本。

登录并完成强制改密后访问 `/workspace`：选题、等待准备、查看需求与文件；获取单页面操作权并开始后，可编辑保存、管理文件/草稿及运行已保存版本的隔离测试。开始时由服务器固定截止时间；准备失败可用新实例重试或放弃，READY 也可放弃。结束收尾按 #15 实施，不伪造 AI 检查或未实现能力的成功。

密码重置、改密或退出遇到未确认准备时，先持久化撤销意图、关闭新副作用准入，再在数据库事务外确认 Go 栅栏。Go 不可用返回 202 和可查询操作；页面提示尚未完成，使用原操作重试，不自动重放密码请求。已完成文件保留；过期准备不再发布新文件。

## 个人模型设置（第二阶段入口）

普通用户完成强制改密后，可从账号页进入 `/models` 管理连接及其多个模型。保存不会验证供应商；检查须单独确认费用，可能发起最多三次真实请求。当前不自动修改演练选模，完整第二阶段尚未交付；本地 fixture 不代表真实供应商兼容性通过。

演练显式选择并检查精确模型版本后才能开始计时。Copilot 已支持读取、Diff 整批 ASK/AUTO 审批与受控测试；运行中可用 steer 调整当前任务方向。页面区分已接收、已纳入请求和未生效：当前请求/工具先收敛，旧剩余项不再执行，不回滚已应用代码、不跳过未知写入/测试对账、不自动另起任务。后续模型请求仍可能收费。

使用前由维护者在未跟踪 `.env` 中增加 `MODEL_CREDENTIAL_ACTIVE_KEY_ID` 和 `MODEL_CREDENTIAL_KEYS`。后者是逗号分隔的 `ID:Base64` 密钥环，每个 Base64 值解码后必须恰为 32 字节，active ID 必须存在；由安全随机工具/密钥管理器直接写入，不粘贴到聊天或命令参数。它们是加密用户 Key 的部署密钥，不是供应商 Key；丢失后不能解密现有凭证。启动脚本与 Compose 仅向 Java 传递，不进入前端、Go 或镜像构建产物。未配置时保存凭证明确失败，不回退环境供应商 Key。

`MODEL_CREDENTIAL_ROTATION_ENABLED` 默认 false。仅在全部实例已切到同一新 active ID 且保留旧密钥时，在受信维护实例启用分批轮换；旧引用清零后关闭开关，再移除旧项。完整顺序与安全核对见 [部署轮换说明](docs/design/model-access.md#部署加密密钥轮换维护)。轮换本身不发供应商请求、不改变模型配置修订。

## Docker 镜像复用与清理

Java/Go 镜像只打包稳定程序，发布产物由脚本生成受控快照并只读挂载；不挂整个仓库或 `.env`。相同构建输入直接复用已有镜像，Java 固定构建时间戳、Go 排除 VCS 元数据以避免无关提交导致新镜像。题目验证与执行共用一个内容标签，复用镜像不会跳过发布校验。

```bash
node scripts/clean-docker-images.mjs           # 默认预览
node scripts/clean-docker-images.mjs --apply   # 确认范围后执行
# 未经新脚本构建的当前镜像、额外使用的发布文件可显式保护：
node scripts/clean-docker-images.mjs --keep IMAGE --publication FILE
```

清理前停止本项目构建/测试启动操作（已有服务可继续运行）。脚本只处理本项目已知 Java/Go/cart-total 构建标签，保留 `.runtime/docker-current/` 记录的当前构建、所有现存容器（含停止容器）及其发布数据引用的镜像、开发 `.env` 配置的发布文件和显式指定的引用。不固定保留旧版本；没有当前引用时拒绝猜测。临时测试的历史回执不是保留依据。有效发布使用的题目镜像会保留；若要退役，先停用对应环境和发布引用。已存在规范标签的题目镜像可移除未被容器使用的旧验证/执行别名。

清理不使用 force，不删除容器、数据卷、其他项目镜像或构建缓存。运行配置快照保留在 `.runtime/container-inputs/`，以支持停止容器再次启动；本命令不清理这些小文件。镜像层可能仍被构建缓存引用，镜像数量减少不代表宿主磁盘立即释放同等空间。

## 验证

当前分层与历史实测结果见 [测试分层整改记录](docs/validation/testing-layers.md)。默认以本地 L1 和代码审查收口；核心单测反馈回路控制在 10 秒内，quick 的多语言汇总耗时另计。额外真实链路与故障验证只按明确安排或具体问题最小执行，不逐 issue/PR 跑重型矩阵，也不自动生成 QA 欠账。

当前没有远程仓库；GitHub Actions 仅预置配置，不作为当前 Dev Done 前提。未来启用后，PR/主分支 push 运行 quick 与有限黄金路径 smoke；heavy 仅手动明确选择，无自动合并后/nightly 重型运行。收费模型验证不进入默认 CI。

### 快速反馈

```sh
node scripts/verify.mjs          # 默认 --quick，无 Docker / race / 全量 IT
node scripts/verify.mjs --quick
```

并行运行 Node 快速契约、Java Checkstyle/编译/轻量测试、Go 快速行为/编译/vet、Vue 类型/Lint/单元组件测试。热缓存性能目标为 30 秒，超过只警告；默认硬超时为 60 秒，届时终止自有进程树并失败。可通过 `CODECRAFT_QUICK_TIMEOUT_SECONDS=90` 调整本机或 CI 的硬超时（整数 31–600 秒），不改变 30 秒性能目标，也不自动随历史慢运行放宽。首次依赖下载、首次编译单独准备。原真实 HTTP `StatusHttpTest` 已归入 `StatusHttpIT`，不再启动整个 Spring 应用阻塞快速循环。

定向反馈：

```powershell
cd backend/server
./mvnw.cmd -Dtest=WorkspaceBatchCodecTest,PreparationAuthorizationTest test
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

### 按需全套工程检查

先完成 `npm ci` 和契约生成，然后在根目录运行：

```sh
node scripts/verify.mjs --all    # --heavy 为同义入口；不用于 PR 全量门禁
```

执行已有题目脚本测试、Java `verify`（Checkstyle、lint 编译、JUnit、Failsafe）、Go 格式/vet/普通测试/race/构建、Linux Docker 工作区与受限执行适配器测试、Vue lint/类型/组件测试/生产构建及 npm audit。全量入口还运行无后端的浏览器存储集成、身份/工作区真实 IT 和浏览器链路。Docker 清理失败用例独立为 `scripts/validation-cleanup.docker.test.mjs`，默认快速 Node 入口仅含 8 项契约测试。独立运行 Java `verify` 时 `StatusIT`、`IdentityIT`、`WorkspaceIT` **明确跳过**；真实依赖集成必须运行下一节，不能将跳过称为通过。历史一次性 `backend/prototypes/` 不属于正式套件。

**Windows race 准备**：按 [工具链设计](docs/design/engineering-toolchain.md#windows-race-的-c-工具链) 下载 LLVM-MinGW **20261104 UCRT x86_64**，验证 ZIP SHA-256 为 `552191b5754c49a5a20723276a6fc56541ea1922c4c50b7b669022333fc1b40f`，解压到自己的工具目录。只为当前终端设置：

```powershell
$env:CC = '<工具目录>/llvm-mingw-20261104-ucrt-x86_64/bin/clang.exe'
node scripts/verify.mjs --all
```

旧全局 MinGW 会导致 race 无法启动，不应关闭 race 或修改全局 Go 配置来绕过。Linux CI 使用 runner 的兼容 C 编译器。

### 分层集成与受限执行

```sh
node scripts/test-execution.mjs
# 单用例示例：稳定副本来自真实 gRPC，执行期间保存不改变 Docker 输入
node scripts/test-execution.mjs -test.run=TestGRPCStableCopyIsTheActualDockerInput
```

依赖运行中的 Linux/amd64 Docker daemon（API 1.45+、默认 builtin seccomp）。脚本构建固定题目镜像和 Go 测试二进制，创建独立具名卷；受信任测试协调容器以 UID 10001、socket 所属组 0 访问 Docker socket。**socket 等同宿主管理权限**，测试夹具不是用户沙箱；真正用户执行容器不挂 socket、工作区根卷或参考解，只获得本次副本的只读子目录。脚本只清理本次随机卷标签对应的容器/卷；失败不隐藏清理错误。

#13 已接入正式 Go/Java/浏览器链路；报告通过受控 exec 在 tmpfs 卸载前限量读取，与有界日志独立保留，见 [#13 验证记录](docs/validation/stable-test-run-13.md)。稳定输入并发测试在候选代码输出 OLD 后等待显式释放，保存 NEW 后释放并断言输出仍为 OLD；不再竞争瞬时 RUNNING 状态。

```sh
npm --prefix frontend run test:integration
```

这是独立 Chromium + Vite 的 IndexedDB 集成层，无 Java/Go/数据库；事务、冲突草稿、游标、epoch、迟到写入与清理在此验证。完整 E2E 不注入原生 IndexedDB 实现细节。

### 真实数据库 / Go / Java / 浏览器

先完成上述构建，并安装匹配的 Chromium：

```sh
cd frontend
npx playwright install chromium
# Linux CI: npx playwright install --with-deps chromium
cd ..
# 生产构建（quick 不生成 jar/dist）
cd backend/server && ./mvnw -B -ntp -DskipTests package
cd ../../frontend && npm run build
cd ..
# PR 黄金路径：跳过 Java IT、崩溃和依赖停机矩阵
node scripts/test-tracer.mjs --smoke --identity --workspace
# 单例独立运行（另一个工作区用例无需先成功）
node scripts/test-tracer.mjs --smoke --workspace "--browser-test=save and run"
# 显式按需完整探针（不随每票或 PR 自动运行）
node scripts/test-tracer.mjs
# 账号 REST/PostgreSQL 边界、真实浏览器闭环及会话跨重启
node scripts/test-tracer.mjs --identity
node scripts/test-tracer.mjs --identity --boundary-only
# 工作区 REST、真实浏览器、Go/Java 重启、撤销及故障恢复
node scripts/test-tracer.mjs --workspace
# 仅工作区 REST 边界（含精确 JVM 崩溃恢复）
node scripts/test-tracer.mjs --workspace --boundary-only
# 仅精确 JVM 崩溃恢复 / 单项撤销准备恢复测试
node scripts/test-tracer.mjs --workspace --boundary-only --workspace-recovery-only
node scripts/test-tracer.mjs --workspace --boundary-only --workspace-test=revokedPreparationIsCleanedAfterSandboxRecovery
# 显式收费：需环境变量 DEEPSEEK（兼容 DeepSeek）；真实模型检查、稳定版本测试续答及联动停止，最多 9 次调用
# 凭证仅传给可信 Java 测试容器，不传给 Go/题目容器；独立库/卷在 finally 清理
node scripts/test-tracer.mjs --workspace --boundary-only --copilot-tests
# 显式收费：真实浏览器选择/检查/计时→读取→Diff 批准→写入→Docker 测试→回复与重连观察
# deepseek-flash / thinking OFF；含检查最多 12 次模型调用，不进入默认 quick/smoke/CI
node scripts/test-tracer.mjs --workspace --copilot-browser
```

Java 集成测试使用已有 `~/.m2/repository` 的只读挂载，以离线 Maven 运行预先编译的 classes/test-classes；首次先完成本机 Maven 构建。每次/每种模式的 Failsafe 输出放在其 `.runtime/cc-tracer-*/java-tests-*`，缺失产物/依赖、零执行或跳过所选测试均失败。工作区故障测试容器临时挂载 Docker socket，通过受限命令适配器真实暂停/恢复本次 Go 容器并注入损坏文件；**它是可信测试夹具，拥有 Docker 管理权限，不是用户代码沙箱**。Java 应用及题目容器不获得该挂载。

`--copilot-browser` 还需已安装的 Playwright Chromium；入口先重新打包 Java、构建前端，再运行一个显式收费场景。只有临时 Java 容器额外挂载两个测试类：受属性约束的 BeanPostProcessor 安装真实驱动的 12 次硬上限，受鉴权的测试路由委托正式连接 Service 创建个人配置。若驱动没有实际安装限额器，夹具拒绝启动。供应商 Key 仅通过环境传入该可信 Java 容器，不写临时环境文件，不传给浏览器驱动、Go 或题目容器；正常应用镜像没有这些测试类/路由。浏览器禁用自动 trace/录像/故障截图，只保留不含凭证的业务画面；结束时扫描并移除含明文供应商 Key 的制品（发现即失败）。输出模型身份、结构化结果与用量，不估算价格。场景先以逐次审批增加注释、运行测试，再在第二浏览器通过 AUTO 自动增加另一条注释；核对第一浏览器未保存草稿不被覆盖、切回 ASK 的审计和只读重载。两次修改均故意保留题目初始错误，以真实 FAILED/Surefire 证据验证续答，而不是把聊天文字当作测试通过。供应商或 Docker 不可用时该入口失败，不能报闭环通过。

`--smoke` 仅运行管理员登录/改密/邀请/退出、草稿刷新恢复、保存运行及结果恢复三条黄金路径；镜像构建、题目发布校验和服务就绪属于前置准备，不把准备时间伪报为浏览器交互耗时。

工作区用例通过 tracer 独有、随机密钥保护的 loopback fixture API 在临时库 Seed 独立身份，然后调用正式业务 API 创建并开始独立工作区。没有生产 Seed 路由，不绕过生产限流配置，不共享模块级 Cookie。用例允许并行、独立筛选和本地 `test.only`（CI 禁止 only）；数据库和卷随 tracer 整体销毁。

该脚本不读取开发 `.env`：生成随机凭证、独立 Compose 项目/具名卷和独立端口，启动实际进程及 **Vite preview 的生产构建**，执行 Java Failsafe 集成测试及 Playwright：

1. 空库迁移后，两项依赖可用。
2. 停止 Go，仅 Go 不可用。
3. 删除并重建 Go 容器，确认卷内标记保留和独占锁生效；停止数据库，仅数据库不可用。
4. 恢复数据库、重启 Java，迁移校验后恢复可用。
5. 停止 Java，页面提示无法取得状态，不展示旧成功。
6. 数据库不可达时重新启动 Java，必须非零退出。

脚本还检查 Go 非 root、只读根、私有具名卷、宿主 loopback 端口与无数据库/管理员凭证。浏览器同时检查响应形状、HTTP 200/503、`no-store`、键盘重试和窄屏。没有替换浏览器 API。清理仅针对该次随机项目的进程、容器和卷；删除临时凭证文件，日志保存在忽略的 `.runtime/`。Docker/浏览器/依赖缺失均非零失败，不能算作通过。若终端被强制关闭，检查 `.runtime/cc-tracer-*` 对应的测试项目并只清理该项目，勿清理开发资源。

`--identity` 另外创建独立边界测试数据库，以显式测试时钟验证到期、续期和撤销，使用真实 PostgreSQL 验证事务及并发；生产进程没有测试时钟接口。浏览器保留管理员改密、邀请及退出黄金路径；重置、越权和时间边界由 Java 身份 IT 覆盖，并验证 Java 重启后数据库会话仍可用。失败保留 trace、截图、录屏及 HTML 报告，各次运行使用独立输出目录。这些产物只允许来自已销毁的临时测试凭证；预置 CI 配置限定白名单上传诊断、保留 3 天，不上传 `.env`、数据库或开发凭证。不能把跳过的测试算作通过。`--workspace` 还覆盖选题/准备/只读浏览/开始/放弃、跨用户拒绝、Go 暂停期间的撤销恢复；授权到期使用可控 Clock 快进，以及 16 个损坏清理实例不阻塞新准备。另在独立测试库启动子 JVM：收到 Go 真实提交回执后立即 halt，重启后只用 REST GET 确认原操作/内容/修订，任何重新 Apply 都使测试失败。验证边界见 [#09 记录](docs/validation/problem-workspace-09.md)和 [#10–#12 记录](docs/validation/workspace-write-browser-10-12.md)。

已有独立服务时，可在 `frontend/` 设置 `TRACER_WEB_URL=http://127.0.0.1:<端口>` 后执行 `npm run test:e2e -- e2e/status.spec.ts`；默认断言两项依赖可用。普通 `npm run test:e2e` 不会偷偷启动假服务。

## 契约与目录

```text
backend/proto/                   Protobuf 唯一源文件，含状态、工作区与执行契约
backend/server/                  Java REST/SSE、授权与业务编排、MyBatis、Flyway
backend/sandbox/                 Go 工作区存储与 Docker 受控执行，不访问业务数据库
frontend/src/status/             查询缓存、边界校验和状态页
frontend/src/auth/               登录、强制改密、账号管理；凭证不写入浏览器持久存储
scripts/                        初始化、生成、检查与真实链路测试
```

`GET /api/internal/status` 返回 `{"database":"AVAILABLE|UNAVAILABLE","sandbox":"AVAILABLE|UNAVAILABLE"}`。全部可用时 200，否则 503；固定字段，不返回内部连接信息、凭证或堆栈。该内部运维接口不冒充任务 05 的业务 REST/鉴权协议。

Go 生成文件提交，Java 生成文件仅在 `target/`。生成脚本固定插件版本、下载校验 protoc；Wrapper 分发也校验 SHA-256。预置 CI 重新生成后检查 Go 契约漂移；PR/主分支 push 并行 quick 和 smoke，手动触发默认 quick，可明确选择 smoke 或 heavy（含 Linux race 和全量真实链路）。heavy 不自动随合并或 nightly 运行；**添加了 CI 配置不代表远程 CI 已通过**。

具体决策见 [工程工具链](docs/design/engineering-toolchain.md) 和 [身份契约](docs/design/api-events.md)，执行证据见 [任务 06 验证记录](docs/validation/service-tracer-06.md)、[任务 07 验证记录](docs/validation/account-login-07.md)和[任务 08 验证记录](docs/validation/password-revocation-08.md)。
