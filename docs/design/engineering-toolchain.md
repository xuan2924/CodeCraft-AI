# 工程初始化与验证工具链

状态：初始化方式已由用户确认；任务 #1 的方案与工具链最小验证已完成。前端依赖告警已通过明确版本覆盖解决，Windows race 已通过进程级指定兼容编译器验证。任务 06 仍须建立正式清单、脚本及真实链路；本任务完成不代表第一阶段业务或 Linux 隔离验收完成。

## 已确认的初始化方式

- 正式目录采用 `backend/server/`（Java）、`backend/sandbox/`（Go）、`backend/proto/`（共享 `.proto` 契约），前端在 `frontend/`。目录随任务 06 的首个真实功能建立，本任务不创建空服务骨架。
- 日常开发在本机启动 Java、Go、Vite；Docker Compose 提供 PostgreSQL，题目代码始终在专用 Docker 容器运行。完整联调/部署再验证应用容器化，不把 Windows 开发成功当 Linux 隔离验收。
- 不要求本机安装 PostgreSQL 或 psql；可通过数据库容器执行 psql。数据库端口仅绑定 `127.0.0.1`，数据用具名卷持久化，凭证放未跟踪的 `.env`，测试库与开发库隔离。数据卷不是备份。
- 前端唯一包管理器为 npm；提交 `package-lock.json`，干净安装用 `npm ci`，不生成 yarn/pnpm 锁文件。`packageManager` 声明 npm 精确版本，直接依赖用精确版本；Node 版本也写入工程说明与 CI。
- Java 使用 Maven Wrapper，同时提供 `mvnw`、`mvnw.cmd`。Windows 首选 PowerShell + `mvnw.cmd`，Linux 使用 `./mvnw`。
- 前端测试采用 Vitest + Vue Test Utils（组件/composable）和 Playwright（真实浏览器端到端），类型检查使用 vue-tsc。
- 暂不引入 MapStruct；少量 DTO 转换显式书写，重复显著后再评估。暂不启用虚拟线程；其不替代连接池、限流、超时和取消，需要真实并发需求与组件兼容证据。

## 版本与锁定

以下是本次验证的精确版本，不是“永远使用最新”的策略。正式清单由任务 06 建立；探针的版本不自动成为安全批准。任何阻断项解决后先复验，再冻结正式清单。

| 层 | 验证基线 | 锁定与用途 |
|---|---|---|
| Java | Temurin 21.0.11 | `release=21`，开发/CI 固定 JDK 补丁版本 |
| Maven | 3.9.15；Wrapper plugin 3.3.4、only-script | Wrapper 分发地址使用 Maven Central，提交 SHA-256 校验值；插件版本写入 POM，不继承个人镜像地址 |
| Java 服务 | Spring Boot 3.5.16、MyBatis starter 3.0.5 | Boot parent 管理 Spring/JDBC/测试基础依赖；不改为 Boot 4 |
| 数据库 | PostgreSQL 17.11 | 本次拉取的 `postgres:17` 解析为 `postgres@sha256:748a18a54d3842d43bc156f0840d78029b8d349e0feca98c12b9c4ce3404a6b3`；正式 Compose 用摘要，不用浮动标签 |
| 迁移/驱动 | Flyway core + database-postgresql 11.7.2；JDBC 42.7.11 | 由 Boot 3.5.16 管理，不分别漂移；PostgreSQL 必须包含 Flyway 数据库支持模块 |
| Java gRPC | gRPC BOM / protoc-gen-grpc-java 1.85.1 | 使用 grpc-netty-shaded、grpc-protobuf、grpc-stub；不为初始化额外加入 gRPC Spring starter |
| Protobuf | protoc / protobuf-java 3.25.9 | 遵循所核对 gRPC Java 官方示例的配对，双端使用同一 protoc |
| Maven 生成 | protobuf-maven-plugin 0.6.1；os-maven-plugin 1.7.1 | 按 OS classifier 下载 protoc 与 Java 插件，不要求全局安装 protoc |
| Go | 1.26.5 | `go.mod` 语言版本至少 1.26.0，CI/本机固定工具链；提交 go.mod、go.sum；Windows race 使用下述兼容 C 工具链 |
| Go gRPC | grpc v1.85.0；protobuf v1.36.12 | Go 插件 protoc-gen-go v1.36.12、protoc-gen-go-grpc v1.6.2，不能用 `@latest` 生成 |
| Docker SDK | github.com/moby/moby/client v0.6.2 | 官方当前模块路径；API 模块经 go.mod 锁定，本次为 v1.56.1；客户端默认协商 API 版本 |
| Node/npm | 本机 Node 24.9.0 / npm 11.6.0 | 当前探针可运行；不要仅凭主版本判断 engines。Node 24.15.0 只在临时目录试装，未升级全局环境 |
| Vue 构建 | Vue 3.5.45、TypeScript 5.9.3、vue-tsc 3.3.14、Vite 7.3.7、plugin-vue 6.0.10 | 安装、SFC 转换、类型检查、构建通过；不自动追随 TS 7/Vite 8 |
| Vue 测试 | Vitest 4.1.12、Vue Test Utils 2.4.11、jsdom 27.4.0、Playwright 1.65.0、@types/node 24.10.0 | 组件及 Chromium 浏览器探针通过；须同时应用下述 overrides，干净安装后 audit 为 0 |

其余已确认前端库的**候选**版本：Naive UI 2.45.3、Tailwind CSS / @tailwindcss/vite 4.3.4、Vue Router 4.6.4、Pinia 3.0.4、TanStack Vue Query 5.106.1、xterm 6.0.0。它们完成 npm 安装解析，但未逐个验证运行时接口。Monaco 基线采用 0.57.0，必须搭配下述 DOMPurify 覆盖；其 Vite worker、diff 和悬浮内容净化已做最小浏览器验证。完整编辑器行为、xterm 生命周期和 Tailwind 插件集成仍需功能级验证。

新增依赖的理由限于已确认职责：Wrapper 保证 Maven 版本；Protobuf 插件生成唯一契约；Flyway PostgreSQL 模块支持迁移；Vue Test Utils/jsdom 提供组件环境；Playwright 提供真实浏览器测试。第一阶段不因技术路线中出现 LangChain4j 而提前添加模型 SDK、MapStruct 或其他通用框架。

### 前端安全覆盖与维护

Monaco 0.57.0 自身固定 DOMPurify 3.4.15，测试工具的间接依赖也尚未使用修复范围。正式 `package.json` 必须保留以下 overrides，并提交由 npm 11.6.0 生成的锁文件；不能只固定顶层库然后丢弃覆盖：

```json
{
  "overrides": {
    "monaco-editor": { "dompurify": "3.4.17" },
    "js-beautify": { "glob": "14.0.4" },
    "minimatch": "11.0.5"
  }
}
```

DOMPurify 为同一主版本补丁更新；glob/minimatch 跨主版本的理由是修复已知安全告警，而非新增产品能力。本次用 Vue Test Utils 的 `html({ raw: false })`、js-beautify CLI 通配符输入及 EditorConfig 缩进验证其实际调用路径。minimatch 根级覆盖统一两个间接消费者，避免 npm 对仅覆盖 editorconfig 的去重树报 `ELSPROBLEMS`；未来新增消费者时必须重新验证，不能把根覆盖当无限授权。

干净 `npm ci` 后 `npm audit` 为 0、`npm ls` 无错误；这只是执行时的已知漏洞扫描，不是永久安全保证。上游修复后优先删除覆盖并复跑审计、类型检查、组件与浏览器测试。Monaco 0.57 的 worker 导入使用其 exports 路径 `monaco-editor/editor/editor.worker?worker`，不能沿用旧版 `monaco-editor/esm/vs/...` 路径。

### Windows race 的 C 工具链

Go 官方要求 Windows race 使用 mingw-w64 runtime 8+。本机 GCC 8.1.0 配套 runtime v6，空测试也在加载时退出 `0xc0000139`；调整 DLL PATH 无效。使用校验后的 LLVM-MinGW **20261104 UCRT x86_64**（runtime 15）并仅设置当前进程 `CC` 为其 `bin/clang.exe`，空测试和原 Go 探针均通过；故意竞争的负对照仍报 `WARNING: DATA RACE`。

下载地址为 [官方发布资产](https://github.com/mstorsjo/llvm-mingw/releases/download/20261104/llvm-mingw-20261104-ucrt-x86_64.zip)，SHA-256：`552191b5754c49a5a20723276a6fc56541ea1922c4c50b7b669022333fc1b40f`。正式初始化在开发者工具目录解压并校验，PowerShell 设置 `$env:CC = '<解压目录>\bin\clang.exe'` 后运行 `go test -race -count=1 ./...`。本次只在临时目录验证，未更改全局 PATH、旧 MinGW 或 `go env -w`；临时目录不能作为长期工具路径。任务 06 的启动/验证说明应明确这一准备步骤，不能让 Windows 开发者继续隐式使用旧 GCC。

## 双端生成约定

- `.proto` 只在 `backend/proto/` 维护；Java 与 Go 的 package、服务和字段属于任务 05 的协议设计，本任务不预定义业务 RPC。
- Java POM 将 `../proto` 配为 `protoSourceRoot`，绑定 `compile` 与 `compile-custom` 到生成阶段，输出至 `target/generated-sources/`，不提交生成代码。
- Go 工具安装在项目本地工具目录，固定 `GOBIN`，使用上述精确插件版本；脚本解析同一固定版本 protoc 的 OS 二进制，不从任意 PATH 接受另一个版本。
- Go 输出到 `backend/sandbox/internal/gen/`，采用 `paths=source_relative`，提交生成文件；CI 重新生成后 `git diff --exit-code` 检查漂移。删除字段保留名称和编号，生成文件不手改。
- 任务 06 提供 `scripts/generate-proto.ps1` / `.sh` 统一入口，负责固定版本下载、校验、插件路径、双端输出及失败退出；这两个脚本目前不存在。
- 当前证明的是同一简单 `.proto` 两端生成和编译，不是 Java→Go RPC、流式日志或契约演进已经验证。

## 数据迁移与启动约定

正式工程的入口由任务 06 实现，以下均为**规划命令，不是当前仓库可运行命令**：

1. 根目录 `.env.example` 仅提供变量名及非敏感默认值；开发者建立 `.env`，提供数据库凭证、端口及工作区路径。Compose 的 `.env` 不会自动注入本机 Java/Go，启动脚本必须显式读取并传递白名单变量，不打印凭证。
2. `docker compose up -d --wait postgres`：检查数据库健康，具名卷持久化；不自动执行 `down -v` 或删除开发数据。
3. 运行双端生成脚本；`backend/sandbox/` 中 `go run ./cmd/sandbox` 启动 Go。Windows Docker Desktop 明确设置 `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`；SDK 不等于 Docker CLI 的 context 自动选择。Linux 使用受信任本地 socket，不暴露未认证 TCP。
4. `backend/server/` 中 `./mvnw spring-boot:run`（Windows `./mvnw.cmd spring-boot:run`）启动 Java；Flyway 执行 `src/main/resources/db/migration/V<版本>__<说明>.sql` 并校验，失败阻止启动。已发布迁移不可修改，不自动 baseline、clean 或忽略校验。
5. `frontend/` 中 `npm ci`、`npm run dev`；Vite 将浏览器 API 请求代理给 Java，浏览器不直连 Go 或数据库。具体端口、健康检查及 Cookie/CSRF 配置由实际配置和任务 02/06 提供。

容器镜像下载发生在环境准备阶段；题目执行阶段仍禁网且依赖预装。Go 创建容器时挂载路径由 Docker daemon 解释，Windows 路径映射和 Linux 权限须独立验收。不得为联调向题目容器挂载 Docker socket。

## 验证命令契约

下表为任务 06 应提供的命令/配置，不代表现有仓库已有脚本或正式测试套件：

| 范围 | 单点与快速反馈 | 完整验证 |
|---|---|---|
| Java | `./mvnw -Dtest=<类名> test`；`./mvnw -DskipTests compile` | `./mvnw verify`：JUnit 5/Surefire 单元与边界测试、Failsafe `*IT` 集成测试；配置 Checkstyle 检查手写代码（4 空格、显式 import），排除生成文件；编译器启用 lint |
| Go | `go test ./internal/<包> -run <测试名> -count=1` | 在 Go 模块目录执行 `gofmt -l .`，输出非空即失败；`go vet ./...`；`go test -count=1 ./...`；Linux `go test -race -count=1 ./...`；`go build ./cmd/sandbox` |
| Vue | `npm run typecheck` → `vue-tsc --noEmit`；`npm run test:unit -- <文件>` → `vitest run` | `npm run lint`（ESLint，规则匹配 Vue/TS）；`npm run typecheck`；`npm run test:unit`；`npm run build` → `vite build` |
| 浏览器 | `npm run test:e2e -- <文件>` → `playwright test` | 安装匹配版本的 Chromium；在独立测试数据库/工作区启动真实 Java、Go、PostgreSQL、Docker，再跑全套；不能用前端假 API 代替 |

Java/前端静态检查插件版本随正式配置锁定，本次未执行 Checkstyle、ESLint 或 Failsafe。测试分层沿用第一阶段规格中用户已确认的浏览器、Java REST/SSE、Go gRPC/Docker 边界；本次仅运行工具链烟测，不宣称业务 TDD 或真实端到端测试完成。

## 后续实现仍需验证

1. **正式依赖与脚本**：任务 06 将本专题版本、覆盖与工具校验转为真实清单及启动/生成/验证脚本；当前不是可直接运行的正式工程。审计与 smoke 必须在正式锁文件上重跑。
2. **Linux race**：Windows 工具链兼容问题已解决，但 Linux CI/WSL 的 race 尚未执行；部署侧验证仍由正式工程 CI 落实，不能用 Windows 通过代替 Linux 验收。
3. **浏览器环境复现**：Chromium 安装命令曾超时，但随后日志确认下载完成，实际浏览器烟测通过。正式 CI 仍需可访问的官方下载或经校验的缓存；不能将一个按钮的烟测当完整业务端到端。
4. **真实链路**：Java→Go RPC、Linux 路径/权限/资源隔离、完整 Vue IDE、备份恢复均未验证，交由相应功能任务，不扩大本任务实现范围。

环境、执行结果及官方资料见 [任务 01 验证记录](../validation/toolchain-01.md)。
