# 任务 06：最小真实服务链路验证

> 历史验证记录：保留当时的执行事实、失败及限制，不新增当前验收义务。当前完成边界以根 [AGENTS.md](../../AGENTS.md) 和对应任务最新结论为准；旧“后续门槛”不自动成为待办，已有证据无需逐票重跑。

执行环境日期：2026-11-08。任务：[06](../../.scratch/workspace-test-foundation/issues/06-service-tracer.md)。运行入口：[README](../../README.md)。

## 已确认边界

用户确认：只做仅本机可访问、免登录的内部状态页，不实现账号、模型或完整演练。测试使用已确认的浏览器、Java REST、Go gRPC 边界。状态接口为 `GET /api/internal/status`，200 表示两项依赖可用，503 表示至少一项不可用，响应只有数据库/Go 的枚举状态；Go 在线不等于 Docker 就绪。

正式 Java/Go/Vite 监听 IPv4 loopback；数据库 Compose 端口仅映射 loopback。没有全局安装/升级 Java、Go、Node 或 npm，没有启动/修改开发数据库。依赖与工具均按正式清单锁定。

## 实际执行

| 范围 | 命令与结果 |
|---|---|
| 契约 | Node 入口、PowerShell `generate-proto.ps1`、Git Bash `generate-proto.sh` 均实际执行成功；固定 protoc 3.25.9 与 Go 插件，生成 Java/Go 并可编译；提交后重新生成，`git diff --exit-code -- backend/sandbox/internal/gen` 无漂移 |
| Maven | 由 Maven Wrapper 3.9.15 执行；分发 SHA-256 已锁定，与 Maven Central SHA-512 对照；Checkstyle 0 违规，编译 `-Xlint:all`，打包通过 |
| Java REST 边界 | `StatusHttpTest` **4 项通过**：数据库不可达时区分 Go 可用/不可用/明确 non-serving、gRPC 不响应有 deadline、503 响应不泄露错误详情且 no-store。gRPC 故障为明确测试夹具，不冒充真实 Go |
| Java 真实集成 | 独立 `verify` 中 `StatusIT` **跳过 1 项**；随后真实链路脚本注入隔离依赖，通过 Failsafe 执行该 **1 项**，实际返回两项 AVAILABLE |
| Go | `go mod verify`、gofmt 空输出、`go vet ./...`、`go test -count=1 ./...`、`go build ./cmd/sandbox` 通过；实际 loopback gRPC **1 项**通过 |
| Go race | Windows 使用已核验的 LLVM-MinGW 20261104 UCRT，进程级绝对路径 `CC`；正式 gRPC 测试 **1 项通过**，未关闭 race 或改全局编译器 |
| Vue | 干净 `npm ci`、`npm ls --depth=0`、ESLint、`vue-tsc --noEmit` 通过；Vitest **6 项通过**：503 展示、旧成功失效/重试、加载禁重与卸载取消、3 种非法/不一致响应拒绝 |
| 构建/审计 | Vite 生产构建通过，主 JS 约 175.60 kB（gzip 61.07 kB）；正式锁文件 `npm audit` 为 **0 vulnerabilities**，不是永久安全承诺 |
| 既有脚本回归 | `scripts/problem-contract.test.mjs`、`scripts/validation-cleanup.test.mjs` 合计 **8 项通过**，包含既有 Docker 题目发布/清理夹具，不将其当作本票 Go 执行服务能力 |
| 浏览器 | Chromium 真实生产预览，**5 轮各 1 项通过**；详见下节。桌面与 375px 窄屏截图已人工检查，键盘重试、无横向溢出通过 |

TDD 首轮：Go gRPC 测试先因无实现失败，Java REST 测试先因无 Spring Boot 应用失败，Vue 组件测试先因无状态页失败；分别补足实现后单文件转绿。随后扩充异常路径回归，持续类型检查，最后从干净 npm 安装执行正式套件。

## 真实链路与失败路径

`node scripts/test-tracer.mjs` 使用独立随机 Compose 项目、独立端口、随机数据库凭证和全新卷，启动真实 PostgreSQL、Go 二进制、Java 打包产物和 Vite preview。无浏览器路由替换或假 API：

1. 从空库启动 Java，Flyway V1 创建应用 schema，浏览器查询数据库和 Go 均可用。
2. 停止真实 Go 进程，Java 返回 `{database: AVAILABLE, sandbox: UNAVAILABLE}` / 503，浏览器显示 Go 不可用。
3. 恢复 Go，停止真实 PG 容器，Java 返回 `{database: UNAVAILABLE, sandbox: AVAILABLE}` / 503。
4. 恢复 PG，再重启 Java，Flyway 校验通过，不重复建 schema，两项恢复可用。
5. 停止 Java，代理失败，页面提示无法取得状态，不显示旧成功；键盘重试仍可操作。
6. 保持数据库停止，再启动 Java；迁移无法访问数据库，进程非零退出，没有带病启动。

每轮浏览器检查固定 JSON、HTTP 语义、no-store、内部底座声明和窄屏。评审修正后最后一次运行的临时目录为 `.runtime/cc-tracer-41240-f1238485/`（忽略、不提交），保留本地日志/截图；临时 `.env` 已删除，相关进程/容器/卷已清理，`docker ps -a` 与 `docker volume ls` 按本票项目前缀查询无残留。

## 遇到并修正的问题

- Node 在 Windows 用 `cmd.exe` 调批处理，最初引号转义错误；启用 `windowsVerbatimArguments` 后 Maven 可执行。全套验证又暴露裸 `npm.cmd` 的 `%~dp0` 目录解析问题，改为先通过 `where.exe` 定位绝对路径，已用正式类型检查/完整 npm 命令复验。
- 初选 ESLint 9 安装提示停止支持；核对官方 npm engines/peer 元数据，改为 ESLint 10.14.0 及兼容的 Vue/TypeScript 插件，未改应用框架版本。切换初始开发依赖时旧锁树冲突，重新生成本票尚未提交的锁文件后干净安装通过，未用 `--force` 或 `--legacy-peer-deps`。
- 第一次完整验证的 `CC` 错用了相对路径，被 Go 拒绝；改为绝对路径后完整验证通过。首次解压/冷编译 race 命令超时，复跑通过，未将超时计为通过。
- 用户全局 npm 配置仍提示未知 `sass_binary_site`；未修改用户配置，安装/测试/audit 均正常。

## 未验证与限制

- **Linux 运行、Linux race、远程 GitHub Actions 尚未执行**。已提供固定 action SHA 的 Linux CI、相同生成/验证/真实链路入口；配置存在不等于运行成功。
- 当前机器已验证 Windows x64；其他架构/OS 的 protoc 下载未批准，脚本明确拒绝。
- PowerShell / Git Bash 生成入口均已实际执行；Linux 上的 Bash 与 Maven Wrapper 尚未运行。四个 `scripts/dev.mjs` 启动命令已通过额外本机烟测：以原子新建的临时根 `.env`、独立 Compose 项目与端口启动 PostgreSQL/Go/Java/Vite，实际代理查询两项 AVAILABLE 后清理进程、卷和临时 `.env`，未覆盖已有配置。
- 旧 `backend/prototypes/` 的全套一次性 PoC 未重跑；不以它们替代正式测试，也不把正式测试称为全部历史 PoC 验收。
- 不验证账号授权、SSE、工作区文件一致性、Go Docker SDK、题目执行/隔离、生产部署和备份。内部状态链路通过不表示第一阶段业务闭环交付。

## 独立审查与修正

固定基线 `100f4a6`，初次实现提交 `b18559c`；两名只读 reviewer 并行审查，无代写实现。

### Standards

发现 **2 项 P2**：`StatusService` 捕获依赖错误后缺少服务端诊断；主色在 Naive UI 和 CSS 独立重复。已分别改为在处理边界使用 SLF4J 保留异常原因（不打印配置、不改变公开响应），以及通过 Naive UI `useThemeVars` 将主色传给 CSS 变量。重复主题色的 heuristic 不另计一项。

### Spec

**0 项问题**：未发现本票需求缺失、实现偏差或范围扩张；后续账号/执行能力不纳入本票。评审未独立运行测试，引用的通过结果来自主代理实际执行。

修正后主代理重新运行完整 `scripts/verify.mjs` 与真实 `scripts/test-tracer.mjs`，全部通过；两项规范问题已核对关闭。Linux/远程 CI 未验证的边界不变。

## 官方接口核对

- [gRPC Java 1.85.1 官方 POM](https://raw.githubusercontent.com/grpc/grpc-java/v1.85.1/examples/pom.xml)：与 protoc / protobuf-java 3.25.9 配对及生成插件。
- [gRPC Go basics](https://grpc.io/docs/languages/go/basics/)、安装模块生成接口：`NewClient`、服务注册、deadline 与关闭。
- [TanStack Vue Query](https://tanstack.com/query/v5/docs/framework/vue/overview)：查询缓存和 AbortSignal；Naive UI 与 Vue Test Utils 的实际类型/构建/浏览器行为已在正式工程验证。
- [Maven Wrapper](https://maven.apache.org/wrapper/)：only-script 与分发校验。
- ESLint / Vue / TypeScript 插件版本的 npm 官方元数据，以及 actions 官方 Git 仓库的 tag SHA；不依赖未经核实的最新版本猜测。
