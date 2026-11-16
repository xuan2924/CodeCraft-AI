# 工程初始化与验证工具链

本文维护已确认的工具链约定与验证入口，不维护功能任务进度。可执行入口见 [README](../../README.md)，当前依赖版本以工程清单和锁文件为准；下文初始化版本及环境探针结论是历史基线，不要求回退当前版本。任务完成情况以对应票据为准。

## 已确认的初始化方式

- 正式目录采用 `backend/server/`（Java）、`backend/sandbox/`（Go）、`backend/proto/`（共享 `.proto` 契约），前端在 `frontend/`。任务 06 已随状态查询建立这些目录，不创建空业务框架。
- 日常开发及真实链路验收中的 Java/Go 统一到同一 Linux VM/宿主时钟域；Windows 可运行编辑器、Vite 和浏览器。该用户已确认的变更替代原“本机 Java + Linux Go”路线，原因见 [ADR 0002](../adr/0002-java-go-shared-linux-clock.md)；正式 `dev.mjs java` / `test-tracer.mjs` 已采用 Linux Java 容器，应用通过 Compose 私网连接 PostgreSQL/Go，宿主仅 loopback 映射；集成测试独立容器/报告目录，复用固定摘要的 Java 21/Maven 镜像。未开放远程调试端口，不继承诊断夹具共享网络或应用 socket 权限。
- Docker 继续提供 PostgreSQL，Go 私有工作区放在 Linux 具名卷；保留任务 09 已确认的 Linux 原子文件语义，不将 Windows `os.Rename` 视为原子发布。题目代码始终在独立受限容器执行，不获得 Go 存储根或 Docker socket。
- 不要求本机安装 PostgreSQL 或 psql；可通过数据库容器执行 psql。数据库端口仅绑定 `127.0.0.1`，数据用具名卷持久化，凭证放未跟踪的 `.env`，测试库与开发库隔离。数据卷不是备份。
- 前端唯一包管理器为 npm；提交 `package-lock.json`，干净安装用 `npm ci`，不生成 yarn/pnpm 锁文件。`packageManager` 声明 npm 精确版本，直接依赖用精确版本；Node 版本也写入工程说明与 CI。
- Java 使用 Maven Wrapper，同时提供 `mvnw`、`mvnw.cmd`。Windows 首选 PowerShell + `mvnw.cmd`，Linux 使用 `./mvnw`。
- 前端测试采用 Vitest + Vue Test Utils（组件/composable）和 Playwright（真实浏览器端到端），类型检查使用 vue-tsc。
- 暂不引入 MapStruct；少量 DTO 转换显式书写，重复显著后再评估。暂不启用虚拟线程；其不替代连接池、限流、超时和取消，需要真实并发需求与组件兼容证据。

## 版本与锁定

以下是任务 01 验证的精确版本，不是“永远使用最新”的策略。任务 06 已按需落入正式清单；依赖按功能需要落入正式清单，Docker SDK、编辑器、路由和第二阶段模型 SDK 均已接入，以正式 POM/锁文件为准；不把早期候选表或 PoC 依赖当成新的安装任务。正式新增静态检查为 Checkstyle Maven plugin 3.6.0、ESLint 10.14.0、@eslint/js 10.0.1、eslint-plugin-vue 10.12.0、typescript-eslint 8.72.1；选择理由是执行现有规范且避免初始化已停止支持的 ESLint 9。正式依赖树须持续审计。

| 层 | 验证基线 | 锁定与用途 |
|---|---|---|
| Java | Temurin 21.0.11 | `release=21`，开发/CI 固定 JDK 补丁版本 |
| Maven | 3.9.15；Wrapper plugin 3.3.4、only-script | Wrapper 分发地址使用 Maven Central，提交 SHA-256 校验值；插件版本写入 POM，不继承个人镜像地址 |
| Java 服务 | Spring Boot 3.5.16、MyBatis starter 3.0.5 | Boot parent 管理 Spring/JDBC/测试基础依赖；不改为 Boot 4 |
| 模型 SDK | LangChain4j 1.23.0 | 正式 core/open-ai/anthropic/JDK transport，同版本锁定；应用自管 Loop，不引入 agentic；组合边界见模型支持矩阵 |
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

任务 01 当时验证的其余前端库**候选**版本（当前采用情况以正式清单/锁文件为准）：Naive UI 2.45.3、Tailwind CSS / @tailwindcss/vite 4.3.4、Vue Router 4.6.4、Pinia 3.0.4、TanStack Vue Query 5.106.1、xterm 6.0.0。它们完成 npm 安装解析，但未逐个验证运行时接口。Monaco 基线采用 0.57.0，必须搭配下述 DOMPurify 覆盖；其 Vite worker、diff 和悬浮内容净化已做最小浏览器验证。后续编辑器与 xterm 的实现证据分别见 #10–#12、#14 记录；本段候选版本核验不代替功能完成证据。

新增依赖的理由限于已确认职责：Wrapper 保证 Maven 版本；Protobuf 插件生成唯一契约；Flyway PostgreSQL 模块支持迁移；Vue Test Utils/jsdom 提供组件环境；Playwright 提供真实浏览器测试。第一阶段不因技术路线中出现 LangChain4j 而提前添加模型 SDK、MapStruct 或其他通用框架。

### 前端重构依赖与展示验证

前端重构 01 采用 shadcn-vue 手工源码安装（`frontend/components.json`），共享控件位于 `src/components/ui/`，不是额外运行时组件库。已锁定 Reka UI 2.11.0、Pinia 3.0.4、Tailwind CSS / Vite 插件 4.3.5、`@lucide/vue` 1.73.0、motion-v 2.12.0、`@vueuse/core` 15.0.0、class-variance-authority 0.7.1、clsx 2.1.1、tailwind-merge 3.7.0；版本以 package.json / npm 锁文件为准。`lucide-vue-next@1.0.0` 已被官方标记 deprecated，按其提示改用 `@lucide/vue`，不保留双图标库。Motion 按批准清单安装，但当前基础布局不用它添加无必要动画。

`npm run test:ui` 使用独立 `playwright.ui.config.ts` 启动 loopback Vite，在 `ui-tests/` 的纯组件夹具检查窄屏、长文本、弹窗焦点/关闭、键盘面板调整与 reduced-motion；夹具不进入产品路由或生产构建，不伪装真实 API。此入口不能替代 `test:e2e` / `test:integration` 的真实服务验证。主题对比度由 `tokens.test.ts` 校验正文 ≥4.5:1、控件边界/焦点 ≥3:1。

前端重构 08 已移除 Naive UI、`legacyTheme.ts` 和迁移期全局包装样式；账号、模型、Skill、工作台及 Copilot 复用 `components/ui/Button.vue` 等源码组件，原业务测试保留。`tokens.ts` 同时供 Monaco/Diff 官方主题和 xterm 使用。依赖/锁文件已同步；Java 报告使用 PDFBox 3.0.8 与随包 Apache-2.0 中文静态字体，不依赖宿主字体或远端资源。

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

- `.proto` 只在 `backend/proto/` 维护；Java 与 Go 的 package、服务和字段遵循业务协议；已落地业务 RPC 见 `workspace.proto` 等协议源文件。
- Java POM 将 `../proto` 配为 `protoSourceRoot`，绑定 `compile` 与 `compile-custom` 到生成阶段，输出至 `target/generated-sources/`，不提交生成代码。
- Go 工具安装在项目本地工具目录，固定 `GOBIN`，使用上述精确插件版本；脚本解析同一固定版本 protoc 的 OS 二进制，不从任意 PATH 接受另一个版本。
- Go 输出到 `backend/sandbox/internal/gen/`，采用 `paths=source_relative`，提交生成文件；CI 重新生成后 `git diff --exit-code` 检查漂移。删除字段保留名称和编号，生成文件不手改。
- `scripts/generate-proto.ps1` / `.sh` 已提供统一入口，共用 Node 实现，负责固定版本下载、校验、插件路径、双端输出及失败退出。目前校验支持 Windows/Linux x64。
- `status.proto` 仅用于内部在线查询，`workspace.proto` 承载工作区与执行 RPC；流式日志实施进展见 [#14](../validation/stream-stop-recover-14.md)。

## 数据迁移与启动约定

以下列出当前已落地入口及迁移边界；准确可执行入口和环境要求见 [README](../../README.md)。ADR 0002 的同一 Linux 时钟域路线已落实到 `dev.mjs java` / `test-tracer.mjs`。Windows 本机启动 Java 的旧入口不能作为截止时间敏感业务验收；本地编译、静态检查和单元测试仍可使用。Docker SDK 的运行配置以正式启动脚本和 Compose 为准：

1. 根目录 `.env.example` 仅提供变量名及非敏感默认值；开发者建立 `.env`，提供数据库凭证、端口及工作区路径。Compose 的 `.env` 不会自动注入本机 Java/Go，启动脚本必须显式读取并传递白名单变量，不打印凭证。
2. `docker compose up -d --wait postgres`：检查数据库健康，具名卷持久化；不自动执行 `down -v` 或删除开发数据。
3. 运行双端生成脚本；`node scripts/dev.mjs sandbox` 交叉编译 Linux 静态 Go 二进制，在仅含构建产物的临时上下文构建镜像，再由 Compose 启动。Go 非 root、只读根文件系统、持久化私有具名卷，端口只发布到宿主 loopback。原生 Go 入口仅保留状态链路兼容性，不提供 Windows 工作区保证。Docker SDK/socket 已用于受控执行，Go 是可信执行协调服务；题目容器不得获得 socket。不将 Docker CLI context 选择隐式当成 SDK 配置。
4. `node scripts/dev.mjs java` 先执行 Maven package，将可复现的 jar 放入独立最小构建上下文，按内容复用或构建 Java 镜像；Java/Go 的题目发布产物独立生成受控快照，在启动时只读挂载，不因发布验证时间变化重建程序镜像，再由 Compose 启动非 root、只读根文件系统容器。容器内 HTTP 监听 `0.0.0.0:8080`，宿主仅 loopback 映射，Java 默认本机绑定仍为 `127.0.0.1`；显式传递配置白名单，不挂载开发 `.env`、源代码、Go 工作区或 Docker socket。修改代码后重新执行该入口；单独 `spring-boot:run` 不作为跨端入口。Flyway 执行 `src/main/resources/db/migration/V<版本>__<说明>.sql` 并校验，失败阻止启动。已发布迁移不可修改，不自动 baseline、clean 或忽略校验。
5. `frontend/` 中 `npm ci`、`npm run dev`；Vite 将浏览器 API 请求代理给 Java，浏览器不直连 Go 或数据库。具体端口、健康检查及 Cookie/CSRF 配置由实际配置和任务 02/06 提供。

容器镜像下载发生在环境准备阶段；题目执行阶段仍禁网且依赖预装。Go 创建容器时挂载路径由 Docker daemon 解释，路径映射和 Linux 权限按支持的运行布局实现并测试，不从 Windows 开发宿主推导 Windows 工作区存储支持。不得为联调向题目容器挂载 Docker socket。

## 验证命令契约

开发中优先运行下表的定向入口；完整代码功能收口的默认聚合入口为 `node scripts/verify.mjs --quick`，仅文档修改检查差异与链接即可。验证频率及结果复用遵循根规范，不因内部步骤或提交数量重复运行。quick 汇总编译、静态检查和多语言快速测试，30 秒为热缓存优化目标，60 秒为默认脚本硬超时；超过软目标不代表功能未完成，也不自动授权性能整改。`--all`/`--heavy` 和完整 tracer 仅按明确安排或具体问题运行，不作为逐票默认收口。

用户已确认采用公开 GitHub 仓库与单人开发优先的 CI：主分支 push/可选 PR 只跑 quick，纯 Markdown/票据改动跳过；每天北京时间 03:23 或手动运行非收费黄金路径 smoke。heavy（全量/故障/race 等）仍仅手动选择，不在日常提交、合并或 nightly 自动运行。不设置强制 PR、审批或必过合并门禁；同分支同档运行自动取消旧实例。共享 runner 的 quick 硬超时显式为 180 秒，本地仍默认 60 秒，30 秒热缓存目标不变；安装/首次编译单列准备。CI 仅 contents:read、不持久化 checkout 凭证、不配置供应商 Key、不自动上传 trace/日志/运行目录。远程结果见 [CI 验证记录](../validation/github-ci.md)，不隐含生产发布、SLA 或收费模型验证要求。

下表列出可用检查入口，不是每张票必须全跑的清单；`scripts/test-tracer.mjs` 用隔离真实依赖补充 Failsafe 与浏览器验证。涉及跨端截止的 Java 测试 JVM 也运行于同一 Linux daemon，离线复用只读 Maven repository，各模式复制编译产物并隔离报告，零执行或跳过所选测试失败。可信工作区故障测试容器拥有临时 Docker socket 权限，不能充当用户代码沙箱。迁移及真实链路证据见 [#10–#12 验证](../validation/workspace-write-browser-10-12.md)，专项时钟异常保护已按 [ADR 0003](../adr/0003-accept-shared-system-clock-risk.md) 排除首期范围，不作为未完成的验收门槛；正常时间前提下的过期拒绝与重启恢复测试仍保留：

| 范围 | 单点与快速反馈 | 完整验证 |
|---|---|---|
| Java | `./mvnw -Dtest=<类名> test`；`./mvnw -DskipTests compile` | `./mvnw verify`：JUnit 5/Surefire 单元与边界测试、Failsafe `*IT` 集成测试；配置 Checkstyle 检查手写代码（4 空格、显式 import），排除生成文件；编译器启用 lint |
| Go | `go test ./internal/<包> -run <测试名> -count=1` | 在 Go 模块目录执行 `gofmt -l .`，输出非空即失败；`go vet ./...`；`go test -count=1 ./...`；Linux `go test -race -count=1 ./...`；`go build ./cmd/sandbox` |
| Vue | `npm run typecheck` → `vue-tsc --noEmit`；`npm run test:unit -- <文件>` → `vitest run` | `npm run lint`（ESLint，规则匹配 Vue/TS）；`npm run typecheck`；`npm run test:unit`；`npm run build` → `vite build` |
| 浏览器 | `npm run test:e2e -- <文件>` → `playwright test` | 安装匹配版本的 Chromium；在独立测试数据库/工作区启动真实 Java、Go、PostgreSQL、Docker，再跑全套；不能用前端假 API 代替 |

Java/前端静态检查插件已锁定，任务 06 已实际执行 Checkstyle、ESLint 和 Failsafe。测试分层沿用规格已确认的浏览器、Java REST、Go gRPC 边界；已执行范围与结果以对应功能验证记录为准，不由命令存在推导通过；独立 Java verify 跳过的 IT 不计为通过。

## 使用与证据边界

1. **正式依赖与脚本**：任务 06 已提供真实清单、启动/生成/验证脚本并在正式锁文件上复验；后续新增候选库仍需接口核实和安全审计。
2. **Linux race**：可在显式 heavy 中执行；Windows 通过不代替 Linux 证据。未执行不自动阻塞所有功能的 Dev Done。
3. **浏览器环境复现**：Chromium 安装命令曾超时，但随后日志确认下载完成，实际浏览器烟测通过。正式 CI 仍需可访问的官方下载或经校验的缓存；不能将一个按钮的烟测当完整业务端到端。
4. **真实链路**：复用所属功能已有证据，额外验证按需，不将工具链记录变成后续票的全量回归义务。第一阶段不建设备份及备份恢复验收；正常重启对账、授权与清理恢复仍须实现并覆盖 L1。

环境、执行结果及官方资料见 [任务 01 验证记录](../validation/toolchain-01.md)。
