# #10–#12：控制权、保存与浏览器草稿切片

本记录接续 `workspace-mutation-10-11.md`，基线 `dccf112`。仅记录本次可验证改动；**不是 #10–#16 整体完成或生产上线许可**。

时钟保护相关的历史待办已由文末“首期时钟风险取舍”及 ADR 0003 调整；不把旧记录中的专项保护要求继续作为当前阻塞项。

## 实现范围

- V8 持久化控制行、转换意图和文件批次；事务不跨 RPC。Java 校验身份/归属，凭证只存摘要，页面仅持有内存秘密。
- CloseGate → 查询旧批次 → OpenGate 排序；身份撤销捕获固定 fence ticket。最多自动派发一次，未知结果查原回执；NOT_FOUND 后先关闭旧代次再判未提交。
- REST 文件批次、控制权与操作查询；开始要求操作凭证。READY 放弃先关闭 gate；Go 允许关闭后、没有写入证据的 READY 取消，不删除开放 gate 或写入证据。
- Monaco 文本编辑、显式保存、原子创建/重命名/删除、双栏冲突与基线查看。保存快照不覆盖后续输入；删除/重命名未决时锁住相关编辑及草稿恢复。
- IndexedDB 草稿按账号/演练/路径/页面标识隔离，防抖写入，明确备份失败。显式退出前选择返回工作区保存、丢弃全部账号草稿或取消；过期不清理。退出事务同时清除草稿并推进账号 epoch，旧页面在重新登录后也不能重新写回已清除的草稿。
- 查询失败保留已有编辑器与草稿；操作权查询失败明确提示并暂禁写入。路由保存后再次检查新增输入，未解决则不离开。

## 实际验证

环境：Windows 宿主、Docker Desktop Linux/amd64、Java 21、Go 1.26.5。没有将 Docker 内运行存储测试计作“题目测试执行器”。

| 执行项 | 结果与范围 |
|---|---|
| 前端 `typecheck` / `lint` / `test:unit` / `build` | 通过；6 文件、35 测试。Monaco 异步块约 2.7 MB，仍有构建体积警告 |
| Maven `test` | 18 测试通过，Checkstyle 通过 |
| `node scripts/test-workspace.mjs` | 29 个 Go 顶层测试通过；含真实 gRPC、响应丢失进程故障与新增 READY gate 取消约束 |
| Windows `go test ./...`、Linux 目标 `go vet ./...` | 通过；Windows 不含 Linux 存储测试 |
| `node scripts/test-tracer.mjs --workspace` | 最终串行重跑通过：14 项真实 REST/PostgreSQL/Go 测试、浏览器保存/双页面/冲突/草稿/文件操作/退出确认，以及服务重启后内容与修订保持 |
| `node scripts/test-tracer.mjs --identity` | 最终串行重跑通过：13 项身份集成测试、真实浏览器身份流程与服务重启 |

本地日志（未跟踪）：`.runtime/write-final-e2e-retry.log`、`write-final-identity-retry.log`、`write-review-go.log`、`write-review-java.log`。

### 回归与评审

- 删除未决时编辑器仍可输入：红灯 `readOnly=false`；加入相关路径锁后通过。
- 手工确认保存后旧轮询仍触发：红灯查询两次；撤销旧定时器并校验请求身份后通过。
- 后台读取失败卸载编辑器：修正测试异步组件替身后，确认红灯为查询失败后的 textarea 消失；保留缓存编辑器后通过。
- 保存后离开丢失在途输入、丢弃恢复副本误删另一页面草稿：分别先复现，增加脏状态复查及仅本页丢弃清理后通过。
- 退出前未确认、控制权查询失败无反馈：分别先复现，再加入确认流程及错误/重试入口；组件测试通过，退出确认及账号 epoch 栅栏另有真实浏览器验证。
- 已执行独立 Standards / Spec 双轴评审。修复活跃编辑器错误反馈、受控时钟、上述草稿丢失及退出流程问题；控制动作字符串的 enum 建议为非阻断可维护性建议，未扩展重构。

### 必须保留的失败记录

最后收口时并行启动 identity/workspace 两个 tracer，曾出现：

1. IdentityIT 清理夹具未包含 V8 新外键表，13 项报 SQL 错误。已补齐两个显式 TRUNCATE 清单；重新编译并串行执行身份全链路通过。
2. `expiredPreparationAuthorityCannotCreateFilesAfterSandboxRecovery` 一次预期 PREPARATION_FAILED、实际 READY。未修改该测试或产品逻辑；随后串行 boundary-only 和完整 workspace 均通过。**根因尚未定位，不能宣称偶发失败已修复**。该测试涉及 Java/Go 实时时钟及容器暂停，宿主负载/时钟假设尚待验证；亦未排除实现竞争。后续不得仅靠增加 sleep 或放宽断言消除信号。

这两套脚本还共享 Maven target/failsafe 与前端报告目录，后续收口按串行执行；本次没有证明并行是第 2 项的根因。

## 尚未完成的门槛

- 精确注入“Go 写入已提交、Java 记录回执前被杀”的 Java 进程恢复用例仍缺失。已有 Go 响应丢失与整服务重启验证不能替代这个切片。
- 上述准备授权过期偶发结果须定位；可控时钟边界、时钟回退与目标部署卷/断电不在本次通过范围。
- #13–#16 未实现：受限题目测试容器、流式日志/停止恢复、冻结与最终验收、删除/保留/跨存储配额。
- 512 MiB 计量、引用回收、收尾预留、真实 ENOSPC 仍未交付；现有全树复制/累计回执不具备长期生产容量保证。
- 前端复杂草稿逻辑仍需后续文件生命周期/删除联动组合验收；单场/账号删除不能由普通 404 推导。未运行 race detector。

## 前置门槛复查（基线 `7c3427f`）

本轮尚未修复授权过期问题，也未交付 Java 回执落库前 Kill 用例或 #13–#16。

- 串行执行 `node scripts/test-tracer.mjs --workspace --boundary-only`，前 3 次通过，第 4 次原样复现 `expiredPreparationAuthorityCannotCreateFilesAfterSandboxRecovery`：预期 `PREPARATION_FAILED`，实际 `READY`。因此“两套 tracer 并行”不是复现的必要条件；仍不能据此判断产品逻辑或测试时钟谁有问题。
- 新增仅用于诊断的单方法筛选：`node scripts/test-tracer.mjs --workspace --boundary-only --workspace-test=expiredPreparationAuthorityCannotCreateFilesAfterSandboxRecovery`。仍启动独立 PostgreSQL 和真实 Go 容器，未替换 RPC、放宽断言或改变 sleep。连续 4 次单方法通过，不代表偶发问题已解决；尚未得到稳定的最小复现。
- 临时记录 Java 受理时间、持久化授权期限和容器恢复前时间，4 次完整边界运行均通过；这些成功样本不能解释原失败，也未测量 Go 时钟偏差。诊断输出已从源码删除，未改产品逻辑。
- 另一次重复运行在基础设施启动阶段超出命令预算，未得到测试结果；仅清理了该次自建 Compose 项目 `cc-tracer-37100-5c84a6a3` 的容器、网络和卷，没有清理其他项目。
- 本地未跟踪证据：`.runtime/prerequisite-boundary.log`、`prerequisite-repeat-{1,2,3}.log`、`prerequisite-isolated-{1,2,3,4}.log`、`prerequisite-instrumented*.log`。日志可能含环境信息，不作为可公开附件。

### 本轮检查与评审

- `node --check scripts/test-tracer.mjs`、`git diff --check` 通过；错误模式、空方法名、含 `=` 的方法名均在启动基础设施前拒绝（3 项 CLI 检查）。
- `node scripts/verify.mjs` 已执行但未全量通过：脚本契约测试、Maven verify（18 项单元测试，真实集成因环境开关跳过）及 Go 模块校验通过，随后在基线已有的 `internal/workspace/mutation_linux_test.go` gofmt 检查停止。本轮未修改该文件；后续 Go vet/test/race 和 Linux 存储测试未由该命令执行。
- 单独执行前端 lint、typecheck、test:unit、build 均通过（35 项测试）；仍有既有 Monaco 大块构建警告。本轮未运行浏览器端到端或身份集成。
- 独立 Standards / Spec 双轴评审对两文件诊断差异未发现缺陷；Spec 明确前置修复和 #13–#16 仍缺失。评审工具只展示启动时 HEAD 差异，主代理另核对 HEAD 正是 `7c3427fd0262756736e989860a8a29aa43d1323a`，差异仅含本记录及筛选脚本。此评审不等于业务验收通过。

该检查点的下一步是捕获失败运行中 Java/Go 的实际时钟、固定派发期限和准备提交时点；后续证据见下节，不能以更多成功重跑关闭问题。

## Windows / Docker Desktop 双端时钟诊断

用户提出 Java 在 Windows、Go 在 Docker Desktop Linux VM 的时钟差异假设。本轮以临时探针关联同一个 operation：Java 实际派发前的 `Instant.now()`、Go 接收及原子发布检查前的 `time.Now()`，统一输出 Unix 毫秒；另记录持久化期限及容器恢复前后时间。没有调整 sleep、截止判断、测试断言或系统时钟。

串行执行 `node scripts/test-tracer.mjs --workspace --boundary-only`，前三次通过，第四次复现原失败（14 项中 1 项失败，预期 `PREPARATION_FAILED`，实际 `READY`）。失败 operation 为 `4fb676b5-44e7-40e0-b0d2-bb6eac8a681a`：

| 观测点 | Unix 毫秒 | 相对 RPC 固定截止时间 |
|---|---:|---:|
| 数据库授权期限（保留微秒） | 1794233951024.748 | — |
| Java / Go RPC 中相同的 `notAfter` | 1794233951024 | 0 |
| Java 在恢复容器前 | 1794233951548 | 已过期 524 ms |
| Java 实际派发 PrepareWorkspace 前 | 1794233951648 | 已过期 624 ms |
| Go 收到同一 PrepareWorkspace | 1794233950905 | 尚余 119 ms |
| Go 发布前的截止检查附近 | 1794233950924 | 尚余 100 ms |
| Java 完成容器恢复调用后 | 1794233951666 | 已过期 642 ms |

### 结论与边界

- 此次失败证实跨运行时时钟不一致参与了授权越期路径：Go 接收时间比 Java 派发时间小 743 ms，正向网络/调度延迟不能解释这种倒序；Go 看到的期限尚未到达并完成准备，Java 最终显示 READY。743 ms 是两个观测点的差值，不是经过 RTT 校准的精确恒定偏差。
- 固定 `notAfter` 在 Java/Go 一致，没有该次请求被延长期限的证据；微秒转毫秒不足 1 ms，不能解释上述差异。这里不是日志显示时区相差 8 小时，比较的是 Unix 毫秒。
- Java 授权检查后，gateway 先 QueryWorkspace，再在 NOT_FOUND 时派发 PrepareWorkspace；查询等待期间授权可能到期，现有最后防线依赖 Go 对绝对截止时间的判断。仅在 Java 派发前再查一次时间不能消除请求在途跨越截止的普遍问题，也不能替代 Go 提交点的检查。
- `docs/design/task-event-recovery.md` 的“同一宿主 UTC 时钟”前提不能直接套用到当前 Windows JVM + Linux VM 组合。尚未证明偏差来自 Docker pause、VM 同步、宿主校时或其他原因，也未验证时钟回退防护。不能概括为所有 Docker 部署必然失败。
- 原问题不再是“完全没有原因线索”，但**尚未修复**。建议先在同一 Linux VM/宿主时钟域运行 Java 与 Go 复验，再明确当前 Windows 开发链路是否受支持及异常时钟的拒绝准入策略；这些方案尚未实施或改变部署规范。不能通过增加 sleep、重试至绿或直接回滚已提交文件关闭问题。

### 证据与清理

- 本地未跟踪日志：`.runtime/clock-boundary-{1,2,3,4}.log`；失败 Go 日志 `.runtime/cc-tracer-28432-29332ec6/sandbox.log`。上表摘录仅含随机操作标识、时间和结果，不包含凭证、代码或日志正文。
- 临时 `[DEBUG-clock]` 探针已从 Java gateway、Java 集成测试和 Go 存储服务移除；产品源代码及断言恢复至本轮基线。本轮最终差异仅为本文档，不把临时探针作为生产观测机制交付。
- 清理探针后重新打包 Java 成功；`node scripts/verify.mjs` 再次执行，9 项脚本测试、18 项 Java 单元测试通过，仍在既有 `mutation_linux_test.go` gofmt 检查停止，不宣称全量通过。此文档切片未重复前端/浏览器验证。日志：`.runtime/clock-clean-build.log`、`.runtime/clock-final-verify.log`。
- 独立 Standards / Spec 双轴评审未发现文档或时间计算问题；主代理核对评审启动时 HEAD 为 `2e8068f` 且差异仅为本文档。评审不替代修复和集成验收。
- 此诊断未补齐 Java 回执落库前 Kill 测试，未实现 #13–#16，也不表示真实跨端授权恢复验收已通过。

## 同一 Linux VM 时钟域复验（基线 `f93a986`）

用户授权将 Java、Go 放在同一 Linux 时钟域做验证。本轮仅为诊断实验，未改变正式 Compose、日常开发入口或支持范围。

### 实验配置与安全边界

- Java 测试运行于已有 Maven 镜像 `maven@sha256:d6e32a254897415a445654a3c43c30fbc731a4d946cf5c66d1cb9184141c20c1`（Maven 3.9.15，Temurin 21.0.11+10）。Windows 对照 JVM 同为 Temurin 21.0.11+10。
- Java 容器使用 `--network container:<本次 Go 容器>`，Go 地址保持 `127.0.0.1:9090`；PostgreSQL 使用同一独立 Compose 网络中的 `postgres:5432`。Java、Go、PostgreSQL 均在 Docker Desktop Linux VM 内，未调整宿主或 VM 时钟。
- 复制编译后的 classes/test-classes、pom 和已验证的题目发布文件到独立诊断目录；离线复用只读 Maven repository，不挂载整个仓库、开发 `.env` 或 Maven settings。使用临时随机凭证、独立数据库和工作区卷，报告与宿主 target 分离。
- 只执行原 `WorkspaceIT#expiredPreparationAuthorityCannotCreateFilesAfterSandboxRecovery`，不修改 5500 ms 等待、原始状态断言或业务判断。临时 `[DEBUG-clock]` 探针与上轮相同，只记录 Java 派发、Go 接收/发布检查及测试恢复时间。
- 可信 Java 测试容器临时挂载 Docker socket；该挂载本身具有宿主 Docker 管理能力，不宣称它是安全执行用户代码的沙箱。仅在该容器提供名为 `docker` 的诊断适配脚本，校验目标为本次 Go 容器，只映射 pause/unpause 到真实 Engine API `POST /v1.48/containers/{id}/pause|unpause`（成功 204），未模拟暂停。Go 容器隔离配置未放宽，用户代码未获得 socket。接口依据 [Moby v28 API 定义](https://github.com/moby/moby/blob/v28.0.0/api/swagger.yaml)，本机 daemon 支持 API 1.40–1.54。
- 容器内每次执行 `mvn -o -B -ntp -Dit.test=WorkspaceIT#expiredPreparationAuthorityCannotCreateFilesAfterSandboxRecovery failsafe:integration-test failsafe:verify`，预先限定 10 次、失败即停止。每次新建测试 JVM和测试账号，10 次共享本次独立数据库/Go 服务，**不是 10 次完整环境重建或完整 WorkspaceIT 套件**。

### 结果

首次搭建夹具遗漏空库 bootstrap 管理员配置，ApplicationContext 启动失败，未进入目标行为；补充临时管理员凭证后才开始下表 10 次正式重复。该夹具失败不计为授权问题复现，也不计为通过。

10 次均实际执行 1 项测试，0 failure/error/skip，原断言确认 AUTHORIZATION_REVOKED、清理成功、无路径，并允许新登录重新准备成功。关联过期操作的观察如下（毫秒）：

| 次数 | Java 派发时已过期 | Go 接收时已过期 | Go 接收 − Java 派发 | 过期实例进入发布前检查 |
|---|---:|---:|---:|---|
| 1 | 931 | 990 | 59 | 否 |
| 2 | 977 | 1045 | 68 | 否 |
| 3 | 996 | 1064 | 68 | 否 |
| 4 | 947 | 1018 | 71 | 否 |
| 5 | 未派发 | 未接收 | 不适用 | 否 |
| 6 | 943 | 1013 | 70 | 否 |
| 7 | 948 | 1010 | 62 | 否 |
| 8 | 未派发 | 未接收 | 不适用 | 否 |
| 9 | 1006 | 1077 | 71 | 否 |
| 10 | 999 | 1072 | 73 | 否 |

8 次命中原问题所需的“Java 检查授权后等待 QueryWorkspace、过期才派发 PrepareWorkspace”路径，但 Go 此时同样认为期限已过，未发布；其余 2 次没有派发该过期 operation，在 Java 侧收敛。未出现此前 Go 接收时间小于 Java 派发时间的倒序。59–73 ms 包含传输和调度耗时，不是精确时钟偏差测量。

### 结论、未验证项与后续决定

- 此受控样本支持“同一 Linux 时钟域可避免已观测到的跨运行时截止判断分歧”，而非单纯依靠 10 次绿色状态推断。没有延长授权或增加等待。
- 不能据此声称 Windows JVM + Linux VM 模式已修复，也不能证明同一 Linux 环境不发生时钟跳变；时钟回退/无法确认时拒绝准入的设计约束仍未实现验证。底层 Windows/VM 同步偏差的来源仍未知。
- **建议**将截止时间敏感的 Java/Go 开发与验收链路统一到同一 Linux VM/宿主；Windows 可继续运行浏览器/Vite。正式入口迁移、Windows 混合模式的支持承诺、时间异常检测及拒绝准入策略仍需确认和实现，本实验没有默默改变已确认日常开发路线。
- 本轮没有完整 WorkspaceIT、浏览器链路、真实时钟跳变或精确 Java Kill 验证；不解除 #10–#16 的剩余验收门槛。

### 证据与清理

- 本地诊断入口为未跟踪的 `.runtime/linux-clock-probe.mjs`；10 次记录位于 `.runtime/cc-clock-linux-9764-5f231c7b/run-{1..10}.log`，对应 `sandbox.log`；总日志 `.runtime/linux-clock-probe-2.log`。首次夹具失败记录位于 `.runtime/cc-clock-linux-50356-f8f2eda9/run-1.log`。这些是诊断材料，不是正式工具入口或可公开日志包。
- 两次自建 Compose 项目及可信测试容器、网络和卷已清理，临时 `.env`/`java.env` 已删除；未触碰其他项目。临时探针全部移除，业务源码与断言恢复基线，最终差异仅为本文档。
- 清理后执行 `node scripts/verify.mjs`：9 项脚本测试、18 项 Java 单元测试通过；Maven verify 已重新生成不含探针的编译产物；仍在原有 `mutation_linux_test.go` gofmt 检查停止，不宣称全量通过。日志 `.runtime/linux-clock-final-verify.log`。
- 独立 Standards / Spec 评审未发现问题；Spec 复核了 10 份测试结果及 8 组双端时间数据。评审仅覆盖本次文档及既有证据，不代表独立重跑或生产迁移获批。

## 后续收尾实施切片（基线 `59ddab6`）

| 不变量 | 权威依据 / 提交点 | 故障或竞态 | 验证入口 |
|---|---|---|---|
| Java 应用及集成测试与 Go 同时钟域 | 同一 Docker Linux daemon 上的容器 | Windows/VM 截止时间分歧 | 原过期准备 REST 用例，保持等待与断言；容器化 tracer |
| 已提交文件不因 Java 崩溃丢失/重放 | Go 原子提交，Java 待派发记录已持久化 | Go 成功返回后、Java 回执入库前 JVM 非正常终止 | 测试专用子 JVM halt；重启后仅 REST 查询原幂等操作、内容和修订 |
| 开发迁移不暴露宿主服务或 Docker 权限 | Compose loopback 映射与挂载清单 | 容器内绑定地址变化、错误挂载/凭证传递 | 真实 tracer 启停/重启及容器 inspect；题目/Java 应用无 socket |

### 本切片已实现

- `dev.mjs java` 构建并启动固定摘要的 Java 21 Linux 镜像；tracer 的应用 Java、各模式集成测试 JVM 与 Go 均在同一 Docker Linux daemon。应用非 root、只读根、仅 `/tmp` 临时空间，HTTP 宿主映射保持 loopback，不挂载 Docker socket、源代码或 Go 工作区。默认本机 HTTP 绑定仍为 loopback；容器通过显式配置监听内部网卡。Java/Go 两类 gRPC 客户端集中配置 sandbox host，默认仍为 loopback。
- Failsafe 各模式独立复制已编译 classes/test-classes 和报告目录，离线复用只读 Maven repository，不读取个人 settings 或开发 `.env`。可信工作区故障测试容器临时持有 Docker socket，使用真实 Engine API pause/unpause/archive；不宣称该适配脚本隔离了 Docker 管理权限。独立崩溃测试不挂载 socket。
- `WorkspaceCrashRecoveryIT` 启动测试专用子 JVM，经真实 REST 创建演练、获取控制权、开始及写入。测试专用 gateway 调用真实 Go Apply，得到已提交回执后 fsync 记录关联 ID 并立即 `Runtime.halt(137)`，尚未返回给 Java coordinator；仅在崩溃阶段阻止竞争的回执查询先行落库。重启后的恢复仍使用真实 Go，任何再次 Apply 都记录故障并 halt(138)。随后只用 REST GET 核对原 operation 的 SUCCEEDED、修订 1 和完整文件内容，不通过数据库断言、不重发保存。
- 测试故障注入仅位于 `src/test`，不进入应用 jar，不增加生产故障开关。Java 清理异常以 suppressed 保留；测试容器运行和清理命令均有超时，聚合原始与清理错误，并继续删除临时凭证文件。

### 实际执行与失败证据

| 验证 | 结果 |
|---|---|
| 新崩溃测试负对照 | 临时禁用 `pendingMutations` 恢复扫描，已提交操作重启后停在 PENDING，测试预期 SUCCEEDED 而失败；不是夹具/编译失败。源码随即恢复，正常产物重新构建 |
| `node scripts/test-tracer.mjs --workspace` | 14 项 WorkspaceIT + 1 项真实崩溃恢复 + 1 项 StatusIT 全部实际执行、无跳过；浏览器保存、双页面接管、冲突、草稿、文件操作、退出，以及 Go 重建、Java 重启、数据库停服/恢复通过 |
| `node scripts/test-tracer.mjs --identity` | 13 项身份边界 + 1 项 StatusIT 无跳过；浏览器与会话跨 Java 容器重启通过 |
| `node scripts/verify.mjs`（显式设置已声明 LLVM-MinGW 的 CC） | 9 项脚本、18 项 Java 单元、Go vet/测试及 Windows race、Linux 工作区故障测试、前端 lint/typecheck/35 项单元/build 通过；并未把默认跳过的 Failsafe 当作集成通过 |
| 评审修复后的焦点回归 | Maven verify、`--workspace --boundary-only --workspace-recovery-only`、原过期准备单方法用例均通过 |
| 清理故障实验 | 临时外部 Docker CLI 替身令运行退出 17、清理超时；确认 AggregateError 同时保留两项错误，凭证文件仍删除，清理有界。该项仅验证驱动失败路径，不作为真实 Docker/REST 证据 |

- 负对照执行期间父命令被中断，子测试继续完成并留下 Failsafe 失败报告；已收集报告并仅清理该次 `cc-tracer-3660-fe2b4044` 的容器/网络/卷及临时凭证，未清理其他项目。
- 入口搭建初期分别发现 Docker COPY 创建目录权限错误、原强制 loopback customizer 覆盖容器绑定配置，均定位后修复；不把这两次启动失败计入业务用例结果。
- 首次迁移全链路虽然退出成功，但 StatusIT 的启用条件只接受 loopback URL，在容器服务名下实际跳过。新增“所选测试必须实际执行且无跳过”检查后，负对照明确失败；随后允许精确容器地址并复跑，StatusIT 实际通过。早期跳过的运行不算状态集成证据。
- 修正原有 `mutation_linux_test.go` gofmt 阻断，仅格式变动；其后默认旧 MinGW 导致 Windows race 以 `0xc0000139` 退出，按既有文档仅对该进程设置 `.tools/llvm-mingw-20261104-ucrt-x86_64/bin/clang.exe` 后全量本地验证通过，未更改全局工具链或关闭 race。
- 证据：`.runtime/linux-workspace-final.log`、`linux-identity-final.log`、`linux-migration-verify-2.log`、`linux-review-fixes-{java,crash,expiry}.log`、`linux-status-skipped-negative.log`、`linux-cleanup-fault-validation.log`。负对照报告为 `.runtime/cc-tracer-3660-fe2b4044/java-tests-recovery/server/target/failsafe-reports/ai.codecraft.modules.workspace.WorkspaceCrashRecoveryIT.txt`。日志仅作本地证据，不作为可公开凭证包。

### 评审及仍未完成的门槛

- 独立 Standards 评审指出清理覆盖原始错误、Docker 清理无超时两项 P2；均已修复并完成上述焦点/失败路径验证。Spec 评审未发现本切片实现偏离。首次评审因工具无法确认基线而阻塞，不计作有效评审；补齐 HEAD=`59ddab6` 和完整差异证据后重新完成评审。
- 同域迁移和精确 Java 崩溃切片已补齐，不代表 Windows 混合模式被修复；系统时间回退/无法确认时拒绝新写入的保护及受控时钟竞态仍未实现验证。异常检测阈值、异常状态恢复与重启处理尚需形成有效实施方案，不能以同域通过替代。
- Linux 存储测试实际运行但本轮未运行 Linux race；Windows race 不替代它。真实断电/目标部署卷的持久性边界也未新增验证。
- #10–#12 尚未逐项完成全部组合验收，不整体勾选；#13–#16 的受限测试执行、流式停止恢复、冻结最终验收及删除/容量/引用回收仍未交付。

## 后续用户确认

用户在上述复验及运行布局说明后，确认将 Java/Go 日常开发与真实链路验收统一到同一 Linux VM/宿主时钟域，Windows 保留编辑器、Vite 和浏览器。决定已记录为 [ADR 0002](../adr/0002-java-go-shared-linux-clock.md)，并同步有效设计与 README。上文“待确认”保留为实验当时的历史记录，不再表示此运行边界未获批准；启动/验收脚本迁移、时钟异常保护及完整回归仍未交付。本次仅记录决定，没有新增测试通过证据。

## 首期时钟风险取舍（后续确认）

用户在确认没有同域 Linux 跳变故障证据后，决定首期不建设时钟异常检测、准入锁定/人工恢复，也不为此新增单调计时改造，见 [ADR 0003](../adr/0003-accept-shared-system-clock-risk.md)。保留同一 Linux 时钟域、固定持久化截止时间、正常过期拒绝及 RPC 超时机制，不修改系统校时设置。约 1 分钟偏差是用户可接受风险的示例，不是偏差上限保证，也不是新增授权宽限。

上文“时钟异常保护尚未实现”保留为当时事实，但该专项已不属于首期验收门槛；没有将风险接受记作缺陷修复或测试通过。正常时间下的到期竞争、跨进程恢复和 #10–#16 其余验收仍须完成。本次仅修改决定与文档，未新增业务代码或测试执行证据。
