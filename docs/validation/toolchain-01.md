# 任务 01：工程工具链验证记录

执行日期：2026-11-06。任务：[01 工程初始化](../../.scratch/workspace-test-foundation/issues/01-engineering-toolchain.md)。有效方案：[工程工具链](../design/engineering-toolchain.md)。

本记录是验证证据，不是正式服务清单。探针建立在 `%TEMP%/codecraft-toolchain-probe/`，未复用审批 PoC 的 POM、Wrapper 或业务实现，未在正式目录创建通用框架。临时文件不随 Git 交付；本记录保留版本、命令、夹具行为和结果，不声称可从仓库直接执行这些探针。

## 环境清点

| 项目 | 实际结果 | 仍需准备/注意 |
|---|---|---|
| OS | Windows 11 amd64，Git Bash + PowerShell | Linux CI/执行语义仍需验证 |
| Java/javac | Temurin 21.0.11 | 可用 |
| Maven | 3.9.15 | 可用；个人 Maven 镜像配置不可写入正式 Wrapper |
| Go | 1.26.5 windows/amd64 | `CGO_ENABLED=1`，`CC=gcc`；race 运行失败 |
| Node/npm | 24.9.0 / 11.6.0 | npm 提示未知个人配置 `sass_binary_site`，未修改用户配置 |
| pnpm/Corepack | pnpm 不在 PATH；Corepack 0.34.0 | 选 npm 后不需要 pnpm |
| Docker | Desktop 4.70.0；client/server 29.4.0；API 1.54；Linux amd64 | 首次 daemon 不可用；用户启动后复查通过 |
| Compose | v5.1.2 | CLI 可用；正式 Compose 尚未编写 |
| PostgreSQL | 初始没有 psql、没有本地 Docker 镜像；拉取后实际为 17.11 | 不需要人工安装本机 PG；正式 `.env`、持久化卷和备份配置待建立 |
| protoc | PATH 中没有 | Maven 按 classifier 下载，双端共用，无需全局安装 |

环境检查命令：`java -version`、`javac -version`、`mvn -version`、`go version`、`node --version`、`npm --version`、`docker version`、`docker compose version`、`docker image ls`、`go env CGO_ENABLED CC`，以及 PATH 查询。

## 实际执行

### Java、Wrapper、Protobuf 与数据库

临时 POM 使用设计文档中的精确版本，依赖 Spring Web、MyBatis starter、Flyway PostgreSQL、JDBC、gRPC 和 starter-test。未启动 Spring Boot 服务或执行 MyBatis mapper。

生成夹具：

```protobuf
syntax = "proto3";
package probe;
option java_package = "probe";
option java_multiple_files = true;
option go_package = "example.com/toolchain/gen;gen";
message Echo { string value = 1; }
service Probe { rpc Ping(Echo) returns (Echo); }
```

- `mvn -B -ntp verify`：生成消息和 gRPC stub，编译 4 个 Java 源文件成功；首次无测试，不能计为测试通过。
- `mvn -B -ntp org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.15 -Dtype=only-script`：生成成功，但分发地址继承了用户配置的 Aliyun 镜像。
- Git Bash `./mvnw ...`：失败，输出临时 ZIP 路径 `No such file or directory` 和 `wget: Failed to fetch`。未进一步区分下载器、路径或镜像原因。
- 将临时 Wrapper 地址改为 Maven Central 后，在 PowerShell 执行 `./mvnw.cmd -B -ntp -Dtest=DatabaseTest verify`：成功。
- `mvn -B -ntp dependency:tree '-Dincludes=org.flywaydb:*,org.postgresql:*'`：确认 Flyway 11.7.2、PostgreSQL JDBC 42.7.11。

数据库以摘要镜像启动，名字为 `codecraft-toolchain-pg`，仅映射 `127.0.0.1:55432:5432`。随机密码通过临时 `.env` 传递，不写入日志或仓库；`pg_isready` 检查就绪。探针连接 `jdbc:postgresql://localhost:55432/probe`。

测试夹具 `V1__probe.sql` 创建一个 text 表并插入 `ready`；JUnit 检查：首次 Flyway migrate 执行 1 个迁移、第二次执行 0 个、通过 JDBC 读取值为 `ready`、生成的 Echo 消息序列化再解析仍为 `ready`。这只证明迁移和消息基础 API 可用，不代表业务持久化或网络 RPC 验收。

最终在全新临时数据库运行 `./mvnw.cmd -B -ntp verify`，未指定测试过滤：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

每次执行的 EXIT trap 均删除本任务的临时 PG 容器及匿名卷、删除临时 `.env`；最终容器查询无残留。未启动持久化开发数据库。

### Go 生成与 Docker SDK

工具安装：

```text
go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.36.12
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.6.2
```

`GOBIN` 指向探针本地 `bin/`，没有覆盖全局生成器。同一 Maven 下载的 protoc 3.25.9 执行：

```text
protoc -I ../java/src/main/proto --go_out=gen --go_opt=paths=source_relative \
  --go-grpc_out=gen --go-grpc_opt=paths=source_relative ../java/src/main/proto/probe.proto
```

临时模块 `example.com/toolchain` 引用生成目录，执行 `go mod tidy`、`gofmt`、`go vet ./...` 成功。测试检查消息往返，以及 10 秒 context 下 `client.New(client.FromEnv)`、`Ping(ctx, client.PingOptions{})` 返回 Linux engine；结束关闭 client。

```text
DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine go test -count=1 -v ./...
TestGeneratedMessage PASS
TestDockerPing PASS — Docker API 1.54, OS linux
```

`go test -race ./...` 实际失败：`exit status 0xc0000139`。未将其归因于业务数据竞争，也未以普通测试替代 race。Linux race、真实容器创建/隔离/回收和 gRPC 网络往返未运行。

### 前端

临时 npm 项目固定依赖版本并生成 lock；最终 Node 24.9.0 上运行：

```text
npm ci
npm run typecheck       # vue-tsc --noEmit
npm test                # vitest run
npm run build           # vite build
npx playwright test
```

结果：类型检查通过；Vitest 1 文件/1 测试通过；Vite 生产构建通过；Playwright Chromium 1 测试通过。此前也独立执行 `npm test -- probe.test.ts` 和多次类型检查。

组件夹具为 Vue SFC：`ref(0)`，按钮点击递增；Vue Test Utils 挂载、点击、断言文本 `1` 并卸载。浏览器用真实 Vite 服务访问同一页面，点击按钮 `0` 后断言按钮 `1` 可见。没有 Java/Go 请求，不是第一阶段业务 E2E。

Playwright CLI 1.65.0 安装 Chromium 时命令超过 180 秒；随后日志确认 Chromium/headless-shell v1254 下载完成，实际启动测试通过。安装器清理了用户浏览器缓存中的旧 v1248，属于工具自动副作用；没有改动全局 Node/npm。Node 24.15.0 仅在探针 `runtime/` 中临时安装用于核对依赖 engines，最终测试仍使用本机 24.9.0。

Naive UI、Tailwind、Router、Pinia、Vue Query、Monaco、xterm 做了 npm 安装解析，未在探针中导入或测试这些库；SFC 构建成功不能外推为 IDE 集成成功。

## 失败、告警和未验证项

- Vue Test Utils 2.5.1 的部分间接依赖要求 Node `^22.22.2 || ^24.15.0 || >=26.0.0`，本机 24.9.0 不满足；最终探针改用 2.4.11。不能简单宣布“Node 24 全部兼容”。
- 最终 npm audit（Monaco 0.56.0 探针）为 **6 项：5 moderate、1 critical**；其中 production 审计为 1 moderate、1 critical。Monaco 0.57.0 初次试验同样有 DOMPurify critical，不采用自动来回升级/降级建议作为解决证明。
- DOMPurify 告警包含 [GHSA-6688-9rhm-gjv2](https://github.com/advisories/GHSA-6688-9rhm-gjv2) 等；测试依赖链 Vue Test Utils → js-beautify/editorconfig → glob/minimatch 包含 [GHSA-3g3m-2fjr-6pw4](https://github.com/advisories/GHSA-3g3m-2fjr-6pw4)、[GHSA-g3hv-75wg-m249](https://github.com/advisories/GHSA-g3hv-75wg-m249)。`npm audit fix` 尝试未消除，不执行 `--force`。告警是注册表扫描结果，不等于已证明应用可利用；也不能直接忽略。
- Maven Wrapper 的 SHA-256 配置、正式 Compose、静态检查配置、Spring/MyBatis 运行时、Java→Go RPC、Monaco worker、xterm、容器挂载和 Linux 隔离、完整浏览器业务链路未验证。
- 当前正式工程没有类型检查或测试入口，因而不存在可运行的“仓库全套测试”。结尾实际重跑了全部临时探针：Java 1、Go 2、Vue 1、浏览器 1，不能把这些数字当产品覆盖率。Go race 仍失败。
- 下载的 PostgreSQL 17.6（最初版本查询试验）和 17.11 镜像、依赖缓存保留；没有全局卸载或清理用户其他容器/卷。

## 官方依据

接口与兼容性核对以官方文档和发布元数据为依据，不以审批 PoC 版本为依据：

- [Spring Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[Maven Central Boot metadata](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml)。
- [MyBatis Spring Boot starter](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)：3.0 支持 Boot 3.2–3.5、Java 17+。
- [Maven Wrapper](https://maven.apache.org/wrapper/)、[Protobuf Maven plugin](https://www.xolstice.org/protobuf-maven-plugin/usage.html)。
- [gRPC Java 官方示例 POM](https://raw.githubusercontent.com/grpc/grpc-java/v1.85.1/examples/pom.xml)：gRPC 1.85.1、protoc/protobuf 3.25.9 及生成插件配置；[Go quickstart](https://grpc.io/docs/languages/go/quickstart/)。
- [Docker Engine SDK](https://docs.docker.com/reference/api/engine/sdk/)、[Go client API](https://pkg.go.dev/github.com/moby/moby/client)：当前模块路径、New/FromEnv/Ping、默认 API 协商。
- [Flyway PostgreSQL 支持模块](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database)、[PostgreSQL official image](https://hub.docker.com/_/postgres)。
- [Vite guide](https://vite.dev/guide/)、[Vitest guide](https://vitest.dev/guide/)。具体 engines/peerDependencies 以 `npm view <包>@<精确版本> engines peerDependencies --json` 为准；Go 最低版本以 `go list -m -json <模块>@<版本>` 为准。

未新增 ADR：这些工具链选择可局部替换，不满足难以逆转的重要架构决定门槛。已确认决定写入设计专题，未解决的技术问题保留为门槛，不靠把任务标签改成完成来隐藏。
