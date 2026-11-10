# #13 稳定输入与受限执行：实施中的风险切片

状态：**部分实现，#13 未验收；#14–#16 未开始。** 本轮用户已要求按可验证改动提交；先前“不暂存、不提交”仅描述上轮交付状态。有效规格仍是 `.scratch/workspace-test-foundation/issues/13-stable-test-run.md` 与 `docs/design/`，本文只记录实现和验证，不新增产品政策。

## 当前边界

- `backend/proto/workspace.proto`：内部 PrepareTestCopy / QueryTestCopy；只是稳定输入准备，不登记业务测试、不授权创建容器。
- `backend/sandbox/internal/workspace/testcopy_linux.go`：同保存/CloseGate 协调区检查 revision、epoch、截止；独立文件树与完整回执原子发布；重放固定原输入；损坏/缺失内容与“副本不存在”分开。存在副本时拒绝准备取消，避免丢失执行引用或误报清理完成。
- `backend/sandbox/internal/execution/`：同步、内部 Docker 适配器，持久化登记/结果/有界日志/报告/清理状态；Run 的 Context 是工作进程所有者生命周期，不能直接传浏览器或 RPC Context。尚未接入 Go 服务入口、Java 任务/outbox/槽位或浏览器。
- 每个私有 registry 由 flock 独占；同一 Runner 不排队，只运行一个任务。启动盘点发现未知容器、损坏登记或未确认静默的执行时关闭准入；不自动重放、重启或删除证据。它不是 #14 要求的完整重启重接/清扫实现，也不能替代 Java 的每演练任务槽位和宿主执行代次。
- 固定本机 image ID、`base`、离线 Maven 模板、预装依赖、权威测试与 POM；只挂指定副本的只读 volume subpath，不给整个卷。实际容器非 root、禁网、只读根、drop ALL、no-new-privileges、默认 builtin seccomp，并设置 CPU/内存/swap/PID/tmpfs/shm/core/log 限额。容量值来自显式输入的可信发布配置；首题按现有 manifest 实测。

## SDK 与报告采集

引入 Docker SDK `github.com/docker/docker v28.5.2+incompatible`，复用官方 API、attach/exec 多路流与挂载类型；不自建 Docker HTTP 客户端。依赖锁在 go.mod/go.sum，兼容项参照该版本 vendor.mod，并保留现有 gRPC 要求的 OpenTelemetry 1.45 基线。最初依赖解析选到旧 pkg/errors 导致 SDK 编译失败，按官方 vendor.mod 锁定 0.9.1 后重新验证；不是业务失败红灯。

核验环境：Docker Desktop Linux/amd64、Engine 29.4.0（API 1.40–1.54），SDK 协商 API 1.51；适配器拒绝低于 1.45 或没有 builtin seccomp 的 daemon。

- SDK 官方接口：[attach](https://github.com/moby/moby/blob/v28.5.2/client/container_attach.go)、[exec](https://github.com/moby/moby/blob/v28.5.2/client/container_exec.go)、[mount](https://github.com/moby/moby/blob/v28.5.2/api/types/mount/mount.go)、[vendor.mod](https://github.com/moby/moby/blob/v28.5.2/vendor.mod)。
- 真实参考解首次运行发现 archive/cp 无法从运行容器 tmpfs 收集报告，测试按超时失败；[Docker cp 官方 Corner cases](https://docs.docker.com/reference/cli/docker/container/cp/#corner-cases)明确不支持 tmpfs，建议 exec。
- 修正为固定、非交互 SDK exec 收集器，在容器仍存活时限量读取退出信息和完整 Surefire XML；没有开放任意命令接口。XML 上限 2 MiB，完整检查期望 suite/testcase 名称、计数、跳过及 Maven 退出码。报告异常归环境错误，不信任日志成功标记。
- 报告/结果与日志分离。stdout/stderr 合计按配置保留尾部，单片不超过 16 KiB、最多 4096 片，丢弃字节数可查；超量继续排空。输出流不完整单独记录，不根据日志覆盖结构化结论。
- 报告先落盘，再终止持有 tmpfs 的容器进程、排空输出、发布一次终态，最后移除容器。结果、进程静默、物理清理是不同字段；清理失败不把通过改成代码失败。

## 风险与实际测试入口

| 不变量 | 权威状态 / 提交点 | 故障或竞态 | 已执行公开行为测试 |
|---|---|---|---|
| 稳定版本 | Go 工作区原子提交与 copy 回执 | 保存/复制竞争、旧 epoch/截止 | workspace gRPC 新用例；实际 gRPC 副本送入 Docker、运行期间保存，结果仍关联 revision 0，当前版本为 1 |
| 先登记再创建 | 私有 registry 刷新并原子发布 | 进程在 RUNNING 后被 SIGKILL | 实际子进程死亡、重新创建 Runner，原 ID 可查，新任务 ErrBusy，原任务不重启 |
| 真实隔离 | SDK HostConfig + 镜像权威路径 | 用户代码探测网络/权限/资源、替换练习测试/POM | 实际 Java 源码内探针验证 UID/caps/NNP/seccomp/网络、cgroup 与 tmpfs 写满；用户测试/POM 不替换权威入口 |
| 结果不依赖输出文本 | 完整 XML + Maven exit | 初始断言失败、无报告、日志洪水 | 初始 FAILED、参考 PASSED、缺报告 ENVIRONMENT_ERROR；120 万字符输出后日志截断可见、报告结论保留 |
| 超时/工作进程取消真的终止 | 已登记容器精确 ID、kill + inspect | 超时与取消、第二任务并发 | TIMED_OUT / INTERRUPTED 后 quiescent=true，容器已移除；运行期间新任务不排队 |
| Start 未知不能视为静默 | 未知 Start 意图 + 精确容器删除栅栏 | HTTP 失败后 Start 延迟到达 | 回归先复现“quiescent=true 后迟到 Start 仍返回 204”；修复后迟到请求 404，记录 INTERRUPTED、清理成功 |
| 清理失败不隐藏 | 独立 cleanup 字段及持久化终态 | 对真实 Engine 的 DELETE 注入 500 | 实际执行/证据/kill/inspect 仍走 daemon；结果 PASSED、cleanup=FAILED 持久化，保留已停止容器供恢复 |
| 恢复不信任损坏报告 | Query 重新验证完整证据及边界 | 改坏已完成记录中的 XML | 回归先复现错误恢复 PASSED；修复后查询/重放报不可确认，不启动替代执行 |

真实测试通过 `scripts/test-execution.mjs` 运行：它将 Linux Go 测试二进制放在独立受信任协调容器里，使用真实 SDK、真实 daemon 和一次性题目容器。fixture 自身持有 socket 等同宿主管理权限；用户执行容器不持有 socket。故障注入替换的只有指定 Engine 请求结果，其他操作仍到真实 daemon，不能将这种故障注入描述成 daemon 本身宕机。

## 验证范围与审查

- 已执行整套 `scripts/verify.mjs`（使用仓库说明的当前进程 LLVM-MinGW CC），包括新增真实 Docker 执行套件、全部 Linux 工作区测试、Java 本地 18 测试、Windows Go race、前端 8 文件/57 测试、lint/typecheck/build/audit。
- Java 真实依赖 IT 35 项在 verify 中明确跳过；本切片没有执行新的 Java REST/PostgreSQL 或浏览器链路。Windows race 不覆盖 Linux-only 执行包，不能冒充 Linux 执行 race 证据。
- 独立规范审查未报告硬性规范违例，发现启动保留全部历史证据造成聚合内存放大、测试脚本清理异常遮蔽原错误；已改为只保留 ID 映射，以及独立尝试清理并聚合错误。
- 独立规格审查发现未知 Start 后可能误报静默；以真实延迟请求回归先红后绿修复。相邻恢复检查又补充损坏持久化报告回归，先红后修复。审查修复后的验证单独记录在任务 Comments，不把修复前整套结果冒充最终代码验证。

## 明确未完成

1. Java 身份/归属/操作凭证/截止与业务任务、幂等指纹、持久化派发及全局/每演练槽位；Go StartTest/QueryTask 正式 RPC 与服务生命周期装配；REST 和浏览器保存后运行/轮询/旧版本提示。
2. 编译失败完整分类与旧镜像兼容：新建未发布 `cart-total/1.0.1` 镜像内 EventSpy 仅识别固定 JDK 的部分源码语法/缺符号诊断，其他诊断仍保守报环境错误；既有已发布 1.0.0 不覆盖、不换镜像，尚不支持新分类。不能因此宣称完整满足“编译失败属于 FAILED”。
3. 稳定副本的执行引用登记、终态后的临时副本回收、持久化配额/回执保留。适配器不擅自删除工作区所有者的代码证据。
4. 容器创建响应未知的主动对账、完整重启观察/宿主代次、主动停止协议、日志流/SSE、清理重试（#14）；当前只保守占槽与拒绝盲重放。
5. 磁盘不足/落盘中途进程死亡矩阵、实际 daemon 故障与恢复、慢消费者、Linux race、部署宿主复验；正常副本 gRPC 测试与实际 Docker 测试不替代这些证据。
6. #15 冻结/结束/最终验收与 #16 容量/保留/删除/阶段整体验收。没有开放运行按钮，没有伪造流式日志或 AI 报告。

## 后续编译证据切片

- 已核实本机 `.runtime/problem-publication.json` 保存 1.0.0 发布记录，因此新增 1.0.1，不改旧包、不迁移既有演练。新版本源码与测试规则不变；镜像构建用既有 Maven API 编译受保护 EventSpy，无新增下载依赖。维护成本是随固定 Maven/compiler/JDK 版本维护采集器和有限诊断格式白名单。
- 核对 Maven 3.9.15 `ExecutionEvent` / `EventSpy` / `MojoExecutor` 和 compiler 3.14.1 `AbstractCompilerMojo` 官方源码。`MojoFailed` 的外层是 `LifecycleExecutionException`，需要检查其 cause；`CompilationFailureException` 本身不足以证明用户代码失败。
- 原始语法错误回归先失败为 `ENVIRONMENT_ERROR`。新采集器先按异常类型分类，独立规格审查指出权限/I/O 错误同样使用该异常；真实 Docker 夹具将 `/work/target/classes` 改成不可写而保留 `/work` 可写，先复现错误的 `FAILED`，再收紧为仅接受带固定源码位置的已知源码诊断及完整续行结构。该回归现为 `ENVIRONMENT_ERROR`。未知、混合或本地化诊断不出具代码失败标记。
- 这不是依赖日志关键词：输入来自 Maven 编译失败异常的诊断正文，但仍依赖固定版本的诊断文本格式，不宣称已获得通用结构化 javac 诊断代码。Go 将有限证据与日志分开持久化，查询/重放重新校验；语法错误、验收 API 缺符号、日志截断、证据损坏拒绝与容器清理通过真实测试。
- 新版本维护者验证命令支持显式包路径及独立证据目录：`node scripts/validate-problem.mjs problems/cart-total/1.0.1 .runtime/validation-cart-1.0.1`。已实际通过，不把验证 receipt 当作业务发布。默认无参数仍验证 1.0.0；新版本 README 已修正相应命令。
- 风险映射新增：编译结果的权威依据为固定镜像采集的正向源码诊断与受控退出码；权限/I/O、未知诊断、落盘证据损坏均不允许判通过/代码失败。测试入口为 `TestRestrictedExecutionCompilationFailure` 与 `TestRestrictedExecutionUnwritableCompilerOutputIsEnvironmentError`。
- 最终验证：`scripts/verify.mjs` 使用既有 LLVM-MinGW CC 全部通过，日志 `.runtime/verify-13-compilation.log`；包含真实 Docker 执行全套、Linux 工作区 gRPC 全套、Java 本地测试、Windows race、前端 8 文件/57 测试、类型检查/lint/build/audit。另执行 Linux `go vet ./...` 与 `go mod verify` 通过。Java 真实依赖 IT 明确跳过；没有新增 REST/浏览器运行证据。
- 独立审查：规范轴未报告硬性代码违例，修复新包 README 指向旧版验证命令；规格轴输出 I/O 误判已复审通过。对有限诊断白名单和旧版本兼容不足的提示仍有效，不将其列为已完成。
- 本轮没有接入正式 StartTest/QueryTask、Java 业务槽位或浏览器。#13 仍未完成，不能把编译证据切片称为全票交付。
