# 不可变工作区、Docker 沙箱与题包隔离

## 核心痛点

如果多个写入源（用户手动保存、AI 修改提案、开发 Bash 命令、代码历史回滚）和受控测试直接操作同一个磁盘目录，并发时必然出现文件撕裂、测试跑到一半代码被改、甚至用户通过软链接越权读宿主文件或提前偷看隐藏挑战测试。

## 关键设计决策

### 1. 不可变目录树与原子提交（`commit-*`）

Go 不在原目录就地修改文件，每次变更（创建/修改/删除/重命名）都在同一文件系统下完成：

```text
获取工作区串行锁 → 校验 gateEpoch 与预期修订(revision)
  → 在 stage-* 目录完整准备新文件树与 manifest
  → fsync 后原子 rename 为下一个 commit-* 目录（唯一可见提交点）
```

- **唯一版本号 `revision`**：仅当文件内容或结构真实改变时递增；相同内容保存不递增。任何写操作都会严格校验基线版本，不存在静默自动合并。
- **为什么用 `manifest.json` 原子替换而不引入嵌入式 SQLite**：工作区提交必须与源码文件树在同一个 Linux 文件系统内靠单次目录级 `rename(stage-* → commit-*)` 达成原子可见，避免数据库提交成功但目录树半成品的双状态分裂；单个源码文件限制为 `1 MiB`（防止超大单文件撑爆内存与 Diff 视图），而含幂等写回执的 `manifest.json` 独立上限为 `16 MiB`（达到上限报错 `ResourceExhausted`，不丢弃已有回执），避免高频保存因历史回执累积而阻塞正常编辑。
- **稳定测试副本**：运行受控测试时，Go 在协调锁下从确定的 `revision` 复制一份独立副本供容器使用，原工作区立刻释放给用户继续编辑，互不阻塞。

### 2. 路径、链接防御与开发/验收隔离

- **全面禁用链接**：题包与工作区禁止任何符号链接、硬链接和 Windows junction，每次读写在 Linux 下逐级校验真实路径与演练归属，禁止目录穿越。
- **可编辑范围锁定**：发布契约和 Java 写入层按服务端语言档案限定根目录、保护根构建描述文件（可编辑根下的同名普通文件仍可写和预览）和各级 `AGENTS.md`。缺省 `JAVA_MAVEN` 仍为 `src/main/java`、`src/test/java`；`PYTHON_PYTEST` 为声明式 `src`、`tests`，`executionSupported=false`，创建演练即拒绝，不交给当前 Go/Maven 执行器。
- **双模式 Docker 执行**：
  - **权威受控测试（`run_tests` / 手动验收）**：完全禁网，只读挂载镜像内权威测试 `/opt/acceptance` 与离线 Maven 仓库，用户 `src/test/java` 不参与权威判定；带 CPU/内存/PID/tmpfs 与 30s 保护性超时。
  - **开发 Bash（`bash`）**：运行在独立可写容器副本中，每次执行建立独立 `internal` Docker bridge，仅连接该命令与 Go 协调器；不同命令不共享二层网络，无公网直连路由。通过内置 HTTP / HTTPS CONNECT 代理下载白名单依赖，执行后按可管理范围同步改动并推进 `revision`。
- **有界执行准入**：Java 保留 `workspace_execution_admission` 短事务行锁，测试（含最终验收）和命令共享默认 4 个槽位，同时按演练排他；Go 再按 exerciseId / workspaceId 排他。Compose 用 `SANDBOX_EXECUTION_CONCURRENCY` 同时配置两端（1–64）。副本准备仍在原协调锁内，容器编译/执行并行；未知副作用与失败清理仍阻断新准入，恢复期间不盲目重跑。`Wait` 等待所有已接收工作退出。
- **Egress 边界**：`SANDBOX_EGRESS_ALLOWLIST` 为精确 `域名:80/443` 列表，默认仅 Maven Central / npm 的 HTTPS 端点。每次建连先解析全部 DNS 答案，拒绝私网、回环、链路本地、元数据、未指定及保留地址，再直拨已验证 IP（不二次解析、不继承上游代理）。命令注入大小写 HTTP(S)_PROXY，清空 NO_PROXY；不遵循代理的工具不能直连，Maven 等工具可使用其原生代理设置。权威测试始终 `NetworkMode=none`。
- **部署与清理**：Compose 为 RPC 固定绑定默认网 `172.29.0.2:9090`，代理单独监听 3128，不发布到宿主端口；运行网络仅可访问协调器代理，不能访问 RPC、Java、PostgreSQL 或其他命令。`172.29.0.0/24` / `172.30.0.0/24` 须避开宿主已有网段。每次执行网络有确定名称和 owner/volume/execution 标签，在持久登记后创建，容器确认移除后断开协调器并删除网络；重启对账孤儿网络，陌生归属失败关闭。不配置 Egress 时命令默认禁网。
- **系统调用边界**：自定义 seccomp 通过 `go:embed` 内嵌，默认拒绝，仅放行工具链所需调用；clone 限制 namespace 标志，clone3 返回 ENOSYS 允许 libc 回退。ptrace、BPF、io_uring、挂载、命名空间切换、内核模块、keyctl 等均不可用。保留 `CapDrop=ALL` 与 `no-new-privileges`。

### 3. 双阶段题包隔离（Base vs Challenge）

- 为防止开发 `bash` 在基础阶段偷读镜像中的 `/opt/acceptance` 挑战测试，题包内 `challenge/` 拥有**独立的离线验证镜像**，两张镜像的 `initial/` 工程必须逐字节一致。
- 发布时通过 `scripts/publish-problem.mjs` 验证初始工程预期失败、参考解全通过以及禁网隔离，生成绑定本机镜像 ID 的 `.runtime/problem-publication.json`。

管理员 `/api/admin/problems` 可列出含下架版本的元数据；`validate` 和 `publish` 接收有界 publication artifact JSON，而非服务器文件路径。Python 维护流程只生成 `static-contract-only` 回执，绑定题包及发布输入 SHA，不声称 Docker 镜像、平台、执行环境或测试实绩。artifact 仅接受无 BOM 的严格 UTF-8，拒绝 JSON 键值内 NUL 或孤立代理项。发布与上下架均按管理员与 `Idempotency-Key` 幂等隔离，相同发布键绑定原始请求字节摘要，相同版本不能覆盖不同 artifact。`/{id}/availability` 仅改变新建准入，不替换已运行实例的题包，也不会自动安装镜像或刷新 Go 节点的本地受信任题包清单；进行中的演练通过按版本 ID 查询题面保持独立快照；目录页 `executionAvailable` 综合 `NodeStatus.loaded_package_sha256` 反映当前健康节点是否已真实加载该基础题包（空列表代表未加载，不包含仅供挑战的子镜像）。

### 4. 跨端 512 MiB 统一容量预算（ADR-0006）

- Java 业务记录 + Go 磁盘文件/日志共享单场 **512 MiB** 预算：其中 **256 MiB** 固定预留给结束冻结与最多 3 次最终验收尝试（每次 56 MiB），普通开发最多使用其余 256 MiB。
- Java 在事务内先预留额度，待副作用完成后再以 Go 返回的带 `meterEpoch` 与单调序号快照结算，乱序快照不会错误拉低已占账本。
- 已结束演练默认保留 30 天，每用户最多保留 20 场历史；删除时先在 Java 撤销访问，再由后台按指数退避重试清理物理目录。

### 5. 个人 Dev 会话与交互 PTY

- **复用执行所有权**：终端是 `workspace_command` 的受信任 terminal 模式，不建立第二套准入、额度或恢复账本。每场演练至多一个活跃 Dev 容器，并占用平台执行槽位；权威测试、旧 commands 返回原有 `*_SLOT_BUSY`，编辑器/提案写入返回 `TERMINAL_BUSY`。调用者须先显式同步或结束终端，再重试，不能静默覆盖。
- **文件与缓存**：仅挂载独立 `command-*/tree` 到 `/project`，绝不挂载工作区根、commit、base 或收据。额外屏蔽镜像 `/opt/acceptance`。每工作区 `dev-cache` 持久挂载到 `/cache`（Maven/npm/home），继承原 CPU/内存/PID/seccomp/Egress 隔离。缓存计入原 512 MiB 账本，不享配额豁免；树和缓存合计按 `min(WorkMiB,128 MiB)` 及 20,000 条目监控，仍是周期性监测而非文件系统硬配额。
- **显式提交**：`sync` 先在原 gateEpoch/revision 授权下持久记录提交意图，再停止整个容器；确认所有进程退出后，按普通命令的受保护路径、链接、文件数/大小规则校验并原子提交。提交后结束本会话；新会话从已提交 revision 重建，复用缓存，不复活 shell/后台进程。
- **默认保留草稿**：Ctrl+D、断网、主动关闭、暂停、交卷及撤权不隐式 sync。有修改则记录 `CONFLICT / TERMINAL_DRAFT`，可通过原 commands changes 接口读取并人工恢复；无修改为 `UNCHANGED`。已明确请求 sync 的会话仍必须在最终发布时再次通过 gate/revision 校验；关门后拒绝旧授权发布。
- **关门与崩溃**：CloseGate 先持久关闭，再按工作区索引 `pendingTerminals`（启动恢复时重建，与无关工作区和损坏记录隔离）等待目标工作区的终端强杀、进程静默与容器清理证明，才返回成功；重放也重复核查。同节点上无关工作区的活跃执行同样不再阻塞已静默工作区的 `PruneExecution` / `DeleteWorkspace` 物理回收。Freeze 使用同一屏障，DeleteWorkspace 先写墓碑，终端清理回调在墓碑下只能拒绝提交并收敛。15s 内无法确认则返回不可用，不能把失败当完成。Java 暂停/收尾主动关闭对应 WS；身份撤销由持续鉴权及 Go gate 双重收敛。Go 重启不续接旧 PTY，按原登记停止/清理并保留草稿。
- **真实 PTY**：Docker `Tty + OpenStdin` 提供控制终端，原始字节保留 Ctrl+C/Ctrl+D，resize 使用 Docker resize 触发 SIGWINCH。单次附着；输入/输出片段至多 16 KiB，队列有界，背压终止会话。未附着 30s、输入空闲 5min、总运行 2h 或原授权/演练到期任一先到即终止；heartbeat 不续租写权限或输入空闲期限。

#### HTTP / WebSocket 对接与前端契约（`InteractivePtyTerminal.vue` / `terminalApi.ts`）

1. `POST /api/exercises/{id}/terminal/session`，JSON `{ "expectedRevision": "7" }`；携带现有 Cookie、CSRF、`X-Operation-Credential`、`Idempotency-Key`。返回 `commandId / ticket / webSocketPath / protocol / gateEpoch / expiresAt`，票据最多 30s，仅保存散列。
2. 使用返回路径建 WS，subprotocols 为 `["codecraft.terminal.v1", "ticket.<ticket>"]`。服务端只回显第一个协议；禁止把票据放 URL/query。握手必须有精确匹配 `publicOrigin` 的 Origin，并核验 Cookie 身份、原 session/authVersion、exercise、当前 gateEpoch 后事务性一次消费。
3. 输入使用 binary 帧；控制帧为 `{"type":"resize","cols":100,"rows":40}`、`{"type":"heartbeat"}`、`{"type":"sync"}`。初始窗口 80×24、TERM 固定 xterm-256color；建议 30s 内发送一次 heartbeat，WS 传输空闲上限 60s。
4. 输出 binary 是合并 PTY 字节；文本为 `opened`、`error` 或 `exit`。exit 包含字符串 revision、state、synchronization/reason 及 exitCode/hasExitCode；强制停止时可能没有正常 shell 退出码，不将缺失值解释为成功。
5. 也可 `POST /api/exercises/{id}/terminal/sync`，JSON `{ "commandId": "..." }`，遵循 Cookie/CSRF 与原终端授权。202 表示已请求收敛，不保证当场完成；使用原 `/commands/{commandId}` 查询确认静默、同步及 Java revision 投影完成后再开新会话。已结束的草稿不会由该接口偷偷重新提交。
6. `GET /api/exercises/{id}/terminal/sessions` 返回最近 100 条归属用户审计摘要（创建、握手、关闭、状态与同步 revision），不返回票据/输入正文；同步同时进入原 BASH 变更历史。

Go `ConfigureTerminalEnvironment` 已接入 Java Host LLM Proxy：受信任进程侧 provider 注入两个 Base URL、`CODECRAFT_PROXY_TOKEN` 及与之完全相同的 `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` 临时别名；不从浏览器/proto 接收环境覆盖，不进入 Input/恢复标签/应用日志。Compose 默认启用受控通道，未配置 `SANDBOX_LLM_HOST_URL` 的部署不注入凭证；签发失败拒绝终端启动，绝不退回真实 Key。吊销与审计契约见 [模型接入与安全](model-access-and-security.md#5-容器-cli-的-host-llm-proxy)。

### 6. 多沙箱节点与持久卷亲和

- `SANDBOX_NODES=default@sandbox:9090,secondary@sandbox-secondary:9090` 配置最多 16 个唯一 nodeId/endpoint，必须包含存量默认节点 `default`；未配置时沿用原 SANDBOX_HOST/PORT。延续 ADR-0002，节点处于同一 Linux 时钟域，不宣称跨主机时钟安全。
- V64 将存量演练绑定 default。所有 Java 实例使用相同节点清单；升级先让 Go 提供 NodeStatus，保留并核验原默认节点的数据卷，再启动新 Java。首次采纳 storageId 是迁移信任点，不能借此把旧节点指向新卷。新演练在创建事务中锁定节点候选，以健康、剩余槽位最多、nodeId 稳定排序选择并持久绑定；将未完成准备的绑定作为短期预留计入选择。并发分配在取锁后重新读取预留，避免使用等待锁前的旧快照。
- 节点通过带内部鉴权的 `NodeStatus` 报告真实执行容量、`hostEpoch`、`ready`、持久 `storageId` 与已加载的基础题包摘要列表 `loaded_package_sha256`（字段 6，去重排序，空列表代表未加载任何可执行题包）。Java 每 5 秒探测，连通健康租约 15 秒；超时、不合法状态、卷身份不符都不刷新健康，绑定新演练时仅从已加载目标题包摘要的节点中择优分配。`ready=false` 与失联分开：可达但恢复阻塞的节点拒绝新分配/首次执行，仍允许查询、停止和清理，否则会形成“因待清理不 ready，却无法发清理 RPC”的闭环死锁。每个 Go 私有根以 fsync 保存 `.storage-id`，Java 一旦采纳便不自动更换；普通 RPC 携带该标识，Go 同时核对磁盘标识与请求标识，防止原 endpoint 重启到空白卷后重建错误工作区。该 64 字节节点元数据不归属任何单场存储账本。
- 每场演练及其所有历史 workspace instance 始终回到同一节点：包括 prepare/retry、文件/grep/history、apply/gate、test/command/PTY、停止、计量、prune、collect 与 delete。节点不可用、配置缺失或 endpoint 不匹配即显式不可用，不从另一节点的 NOT_FOUND 推断“未执行”，不重新分配旧演练。
- `hostEpoch` 和 inventory 游标按 node 分开；执行前读取绑定节点 inventory，不再查询默认节点的 epoch。原 Java admission 行锁下额外核验绑定节点健康及已接纳槽位，Go 仍最终限流。V64 移除历史遗留的全局 `one_host_test` 唯一索引，保留同演练排他与持久准入锁。
- Compose 的 `sandbox-multi` profile 提供第二个 Go：独立数据卷、独立 egress 基础网络及地址，运行时命令网络仍按执行创建/回收。两节点共享只读题包发布清单，不共享工作目录。全局 `WORKSPACE_EXECUTION_CONCURRENCY` 与每节点 `SANDBOX_EXECUTION_CONCURRENCY` 分开配置；例如两节点各 4 槽，可显式设置全局为 8。
- 完整拓扑：在 `.env` 设置两个 Redis backend、`ASYNC_BACKEND=rabbit`、所需密码和上述 SANDBOX_NODES，执行 `docker compose --profile redis --profile rabbit --profile sandbox-multi up`。已有绑定节点的 endpoint/卷变更必须经过停派发、原卷与身份核验及显式配置迁移，不能删除绑定或重置 storageId 逃避不可用状态。

## 关键源码入口

- Java 题包与存储：`ProblemPublicationReader.java`、`WorkspaceWriteService.java`、`WorkspaceAiOperationService.java`、`WorkspaceHistoryService.java`、`StorageCapacityService.java`
- 节点路由：`SandboxRoutingStore.java`、`SandboxClients.java`、`SandboxWorkspaceGateway.java`、`node_linux.go`
- Go 磁盘与容器：`mutation_linux.go`、`testcopy_linux.go`、`freeze_linux.go`、`history_linux.go`、`capacity_linux.go`、`runner_linux.go`、`stop_linux.go`
- 发布脚本：`scripts/publish-problem.mjs`、`scripts/validate-problem.mjs`
