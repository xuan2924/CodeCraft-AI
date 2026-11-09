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

## 逐项验收补充（基线 `07674ca`）

沿用规格已确认的真实 REST/PostgreSQL/Go、组件及浏览器测试边界，不新增测试接缝。已有证据复用，新增场景单独报告；本切片仍不是 #10–#12 整票完成。

| 验收条件 / 不变量 | 权威状态与提交点 | 失败或竞态 | 可观察结果 / 测试入口 |
|---|---|---|---|
| #10 接管不得越过未确认写入提前成功 | Go CloseGate 与提交串行化，Java 完成对账后激活控制权 | 暂停真实 Go，写入返回 202 后接管；恢复服务 | `WorkspaceIT#takeoverWaitsForUnavailableSandboxAndReconcilesTheOriginalWrite`：接管未决不交付凭证；恢复后原操作确定成功或拒绝，文件/修订一致，旧凭证拒绝，新页面可保存 |

| #12 丢弃不得覆盖确认之后的新输入 | 浏览器确认内容快照，清理后再次比较 | IndexedDB 延迟期间继续输入，包含丢弃并离开 | `WorkspaceEditor.test.ts` 两个 discard 等待测试：保留新输入，不离开，要求重新确认 |
| #12 返回基线不留下本页过时备份 | 本页备份串行队列及精确删除 | 编辑→备份→恢复原内容→刷新 | `removes its obsolete backup...` 与真实浏览器：无过时恢复入口，不删除其他页面草稿 |
| #11/#12 草稿和离开保护 | 未保存/未决状态与路由守卫 | 保存失败、刷新、组件销毁、脏文件结构操作 | 组件验证保存失败留页、beforeunload 及监听释放；浏览器验证脏文件禁止重命名/删除 |

### 缺陷与验证

独立验收审查发现两项真实草稿缺陷：丢弃等待期间覆盖新输入；恢复基线后遗留本页过时备份。第一项涉及单文件丢弃和丢弃并离开两条路径，分别先加测试取得红灯（预期新输入、实际 `base`），再在确认时捕获内容并在异步清理后复查。第二项先取得未调用删除的红灯，再把清洁缓冲区的本页备份清理纳入串行队列；不清理其他页面副本。保存后离开的最终异步等待也复查脏状态。

| 实际执行 | 结果 |
|---|---|
| Maven `test` | 18 单元测试、Checkstyle 通过；重新编译新增 REST 测试 |
| 新增 takeover 单方法真实集成 | 1 项实际执行，0 失败/错误/跳过 |
| 前端 lint / vue-tsc / unit / build | 全部通过；6 文件、40 项单元测试；保留既有 Monaco 构建体积警告 |
| `node scripts/test-tracer.mjs --workspace` | 15 项 WorkspaceIT、1 项精确 JVM 崩溃恢复、1 项 StatusIT 实际通过且无跳过；浏览器编辑/草稿/文件操作、重启与依赖恢复通过 |

本地日志：`.runtime/acceptance-java-build.log`、`acceptance-takeover.log`、`discard-red.log`、`discard-leave-red.log`、`undo-draft-red.log`、各自 `*-green.log`、`acceptance-frontend-unit.log`、`acceptance-frontend-build.log`、`acceptance-workspace.log`。未公开复制环境日志。本轮没有修改 Go，没有重跑 Go/race 或身份全链路，沿用此前证据。

### 独立评审

Standards 与 Spec 两轴并行审查均未发现本切片阻断问题，结论仅针对上述五文件差异。父代理核对 HEAD 为 `07674ca8ebe8cde8d4155a2023652c37c22988c2`，评审期间无并行修改；审查采用工作树差异，不把空的已提交区间误认为已评审。评审未独立重跑测试，也未将整票缺口宣称完成。

### 尚待逐项收口

- #10 此处原缺口已由下方“#10 逐票验收收口”补齐；#15 结束冻结与状态转换仍不在 #10 验收范围内。
- #11 此处原缺口的补测见下方“#11 逐票验收收口”；既有 Go 原子失败证据保留，另扩展重命名实际子进程故障。
- #12 此处原缺口的补测与保存后退出实现见下方“#12 逐票验收收口”；最终验收仍以该票独立评审结论为准。
- #13–#16 尚未开始；本轮修复的真实数据丢失问题属于其前置任务，不用时钟专项风险扩大阻塞。

## #10 逐票验收收口（基线 `089e9f1`）

本票补充验收，未改变授权及发布规则；初轮新增测试首次即通过，不虚构缺陷红灯。评审后为发布前到期场景增加服务实例私有同步点：只有测试构建导出设置函数，启动服务前配置，生产无 RPC/配置入口且始终 nil；在暂存树 fsync 后、既有截止复核之前等待正常授权到期。沿用真实 REST/PostgreSQL/Go 接缝：SQL 仅设定正常过期及只读状态夹具，所有结果经公开 API 断言，不把夹具状态当作 #15 生命周期实现。

| #10 验收项 | 实际证据 |
|---|---|
| 持久化修订、相同内容不推进 | `savesAtomicBatchesWithControlAndPersistentReceipts`；浏览器保存后 REST 内容复核 |
| 旧基线、保护路径、未开始、只读与截止拒绝 | 强化上述测试：REVISION_CONFLICT / PATH_FORBIDDEN 后原内容和修订不变；新增 `unstartedAndReadOnlyExercisesRejectWritesWithoutChangingTheWorkspace` 覆盖 READY/CLOSING/FINAL_TESTING/ENDED；`expiredExerciseRejectsNewWritesWithoutLosingCommittedReceipts` 覆盖正常过期、新写入拒绝、原成功回执可查询及同键历史结果不重写 |
| 原子写入、丢回执不盲重放 | 真实 Go 原子/崩溃恢复测试；真实子 JVM `committedBatchSurvivesJavaDeathBeforeReceiptPersistenceWithoutReplay`，Go 提交后 Java 落库前终止，重启仅 GET 核对 |
| 页面凭证、第二页、接管、刷新 | 真实浏览器双页面及刷新，localStorage/sessionStorage 不含凭证；REST acquire 重复不交付秘密、第二页拒绝；组件凭证页面作用域及销毁逻辑 |
| 接管与在途写入排序、旧凭证拒绝 | `takeoverWaitsForUnavailableSandboxAndReconcilesTheOriginalWrite`；Go CloseGate/Apply 竞争及延迟旧代次测试 |
| 已准入、完整暂存树准备后到期 | `TestMutationExpiresAfterStagingBeforePublication`：真实认证 gRPC 调用，断言到达同步点时授权有效，等待正常到期后拒绝；重建服务后无成功回执，内容和修订仍为原值 |
| 保存反馈、保存期间新输入 | `WorkspaceEditor.test.ts` 保存等待中输入保留、响应丢失只查原操作，既有浏览器反馈；#12 草稿缺陷修复保留 |
| 跨用户、并发、重启与浏览器冲突 | REST 跨用户返回404；新增 `concurrentSavesFromOneBaselinePublishExactlyOneVersion` 同时发起两个真实 HTTP 请求，唯一成功且内容匹配胜者、revision=1；真实浏览器冲突比较/采用基线/保存；Go 与 Java 重启恢复 |

执行记录：

- 四个定向调用（新增截止、只读、并发及强化冲突方法）分别实际执行 1 项，无失败/错误/跳过。日志 `.runtime/ticket10-{expiry,readonly,concurrent,conflict}.log`。
- `node scripts/verify.mjs` 首次因未设置本终端 CC 使用旧 MinGW，race 启动失败 `0xc0000139`；未改测试/依赖/全局配置，按 README 设置现有 LLVM-MinGW clang 后重跑通过。日志 `.runtime/ticket10-verify.log`（失败）、`ticket10-verify-configured.log`（通过）。通过包含脚本测试、Checkstyle/编译、18 Java 单测、Go 本地/race/Linux workspace 验证、前端 lint/typecheck/40 单测/build/audit。Windows Java verify 的33项集成明确跳过，不计通过。
- `node scripts/test-tracer.mjs --workspace` 实际执行 WorkspaceIT 18、精确崩溃恢复1、StatusIT 1，全部无跳过；完整工作区浏览器、Java/Go 重启及依赖故障恢复通过。日志 `.runtime/ticket10-workspace.log`，资源目录 `.runtime/cc-tracer-41996-ec9f6080`。
- 本轮未跑独立身份浏览器模式；身份完整验收沿用前次记录。没有远程 CI 结论。

### #10 评审补充与复核

- 初轮 Standards：无硬性违规；1 项 P2 重复请求构造建议，已抽取 `buildRequest` 供同步及并发调用共用。
- 初轮 Spec：1 项 P1 发布前到期验证缺口，阻止整票完成，已新增上表真实 gRPC 测试。它只等待正常时间流逝，不调整系统时钟、不新增时钟异常保护或授权宽限。
- 新测试先运行通过；为验证敏感性，临时删除 `publish` 的两项既有发布前检查并保存差异，负对照因 `expired staged write was not rejected: <nil>` 失败。随后恢复检查，定向测试再次通过。日志 `.runtime/ticket10-publish-expiry{,-negative,-restored}.log`，负对照差异 `.runtime/ticket10-expiry-negative-control.diff`。负对照未保留在源码中。
- 发布同步点加入及 Java 测试 helper 整理后再次完整执行 verify 与 workspace tracer，通过。日志 `.runtime/ticket10-final-verify.log`、`ticket10-final-workspace.log`；Java18单测、前端40单测、Go本地/race/Linux、18 WorkspaceIT + crash1 + StatusIT1 和浏览器。集成跳过仍不计入通过数。
- 独立复核完成：Standards 0 项发现；Spec 0 项发现，原 P1 已解除，并确认 #10 七项验收可全部勾选。Standards 为源码只读审查，Spec 同时核对负对照与最终日志；两者未自行重跑测试。本票已完成验收。

#11–#16 不随 #10 收口而视为完成。正常截止准入与只读拒绝已验证；自动收尾、冻结、最终验收、删除和保留仍按后续票顺序实施。专项时钟异常保护仍按 ADR 0003 排除。

## #11 逐票验收收口（基线 `33be869`）

本票只改测试与验收记录，未改生产逻辑。新验收测试首次即通过，不把正常覆盖写成缺陷红绿修复。Go 测试的子进程故障是明确期望的失败，不是宿主测试失败。

| #11 验收项 | 实际证据 |
|---|---|
| 创建/删除/重命名与文件树同步 | 真实浏览器文件操作及 REST `savesAtomicBatchesWithControlAndPersistentReceipts`，每次成功后 UI 与正式路径一致 |
| 重命名整批推进一次修订 | 上述 REST 新增+删除批次从1到2；浏览器从4到5；Go `TestAtomicRenameAndBatchFailure` |
| 路径、链接、跨演练、保护与文本边界 | 新增 `unsafePathsAndBinaryContentCannotChangeWorkspaceFiles` 对遍历/绝对/反斜杠路径、NUL内容返回 INVALID_REQUEST，正式原文件和revision不变；既有 pom.xml PATH_FORBIDDEN 与跨用户 exercise 写入404；Go `TestMutationRejectsDamagedOrLinkedSource` 验证符号链接、目录链接、硬链接、FIFO拒绝；无二进制上传入口 |
| 冲突不得覆盖，同名/缺失明确 | 新增 `failedAndStaleRenameBatchesLeaveTheOriginalTreeIntact`：同名 FILE_EXISTS、缺失 FILE_NOT_FOUND；三项批次失败无半重命名、旧基线 REVISION_CONFLICT 保留最新源内容及revision且目标不存在；#10并发保存及浏览器冲突证据保留 |
| 未保存文件不得静默丢弃 | 真实浏览器脏文件时重命名/删除禁用且明确先保存或丢弃；组件新增未决重命名成功前源buffer不变、输入和草稿恢复锁定、只查询原operation，确认后才切目标；拒绝后解锁且源buffer/草稿保留 |
| 故障、丢回执、重启一致性 | 扩展 `TestMutationProcessFailureRecovery` 为 update/rename × lost-response/write-failure：真实 gRPC 子进程发布后退出86，或RLIMIT_FSIZE触发EFBIG暂存失败后退出87；父进程观察传输未知，重建服务只查询/读取，rename成功只有目标+revision1、失败只有源+revision0，无半应用；Java精确崩溃恢复沿用已验证入口 |
| 浏览器与 Go 故障回归 | 本轮完整 verify Linux工作区测试及 workspace tracer 浏览器通过，非只复用历史结果 |

执行：

- 定向 REST 方法各1项实际执行、无失败/跳过：`.runtime/ticket11-invalid.log`、`ticket11-conflict.log`。
- 组件单文件两次及 vue-tsc 通过：`.runtime/ticket11-editor.log`、`ticket11-editor-final.log`。
- Go 故障定向4子场景通过：`.runtime/ticket11-rename-crash.log`。
- 完整 verify：使用 README 指定的当前终端 CC，脚本/Java18单测/Go本地及race/Linux工作区/前端lint+vue-tsc+42单测+build+audit通过；Windows集成35项明确跳过不计入通过。日志 `.runtime/ticket11-verify.log`。
- 完整 workspace tracer：20 WorkspaceIT + 1精确JVM崩溃恢复 + 1 StatusIT实际执行、无跳过，浏览器编辑/草稿/文件操作及重启/依赖恢复通过。日志 `.runtime/ticket11-workspace.log`，资源目录 `.runtime/cc-tracer-36288-5f8c3b05`。

不声称覆盖真实 ENOSPC、掉电或目标部署持久卷；本票EFBIG故障明确来自暂存写入。#12完整草稿生命周期及#16跨存储计量/删除仍单独交付。独立评审完成：Standards 0 项发现；Spec 0 项发现且确认七项验收全部满足。Spec 核对执行日志，双方未自行重跑测试。#11 已完成验收。

## #12 逐票验收收口（基线 `4153199`，已完成）

### 实现与失败切片

初步只读审查确认“返回工作区保存”不等于保存后退出：没有退出意图、没有覆盖其他页面备份、保存完成也不会退出。本轮先在 AccountPage 组件取得“缺少保存后退出”红灯，再补充账号作用域内存意图、工作区备份处理面板及最终空集合检查。没有把所有备份按时间自动覆盖服务器：用户逐份查看/恢复/比较/保存，或明确丢弃。需要显式获取操作权，失败/未决/新输入均留页。其他演练/不可写的备份不能伪报已保存。

最终退出之前，IndexedDB 在同一 readwrite transaction 中检查账号epoch、确认没有任何该账号备份并封闭旧 writer；期间新备份出现则保留并拒绝退出。封闭后调用真实 logout，但保存退出不再执行无条件全账号清理（已确认空集合，不能删除随后新epoch备份）；显式丢弃退出仍保留既有清理。退出失败/未知或流程卸载时不伪报成功，仅条件释放本次epoch的封锁；页面有效且成功释放时重建已保存的编辑器。账号切换/取消不清草稿，刷新取消内存退出意图。服务端授权、RPC、数据库协议均未修改。

另补“关闭文件”入口：先取得缺少按钮的红灯，再实现脏文件确认提示；关闭仅清选中视图、保留本页buffer及已有备份，重新打开仍有内容。未知操作或退出处理时不能关闭，不承诺未成功的备份已落盘。

### 验收对应

| #12 验收项 | 证据 |
|---|---|
| IndexedDB隔离、基线/内容/时间与防抖 | 既有真实浏览器备份/刷新恢复与账号epoch fence；本轮增加文件切换、关闭重开buffer保留及迟到旧账号读/写回调隔离组件测试 |
| 文案与存储失败 | 原有存储失败组件测试、旧writer失败浏览器断言；关闭视图明确不等于保存/备份，退出核对失败不伪报成功 |
| 恢复/双栏/显式提交 | 真实浏览器恢复后REST仍无本地文本、接管冲突比较后显式保存；新增组件采用revision1后服务端revision2再次冲突，未经再次确认不调用save，确认后仅用revision2提交 |
| 保存清理匹配草稿，保留新输入 | 原有提交快照及丢弃竞态测试；本轮账号面板不替用户选择/丢弃其他页面备份，保存后仍有备份就不退出 |
| 退出保存/丢弃/取消、失败留页、过期保留 | AccountPage显式保存分支；WorkspaceLogout组件覆盖保存失败、未处理多份备份、最终fence失败、成功顺序、退出未知恢复、账号切换；浏览器缺操作权保存后退出失败仍有真实会话，获得操作权后成功保存才退出。丢弃并退出原有浏览器证据保留且组件再次运行，取消本轮浏览器执行 |
| 路由/刷新/关闭、接管保留草稿 | 既有路由保存失败/新输入留页、beforeunload组件测试；本轮真实浏览器关闭取消/确认/重开保留，刷新和双页接管恢复链路仍通过 |
| 销毁与真实恢复 | 新 CodeEditor adapter测试：复用两文件model，销毁释放editor/models/change listener/ResizeObserver，销毁后快捷键不再preventDefault；真实Monaco仍经浏览器编辑/切换验证，资源释放的精确断言使用明确外部替身 |

真实浏览器登录失效场景采用清除 Cookie 后走真实401和同账号重新登录，IndexedDB保留且恢复不提交；并非实际等待服务端TTL到期，后者的身份边界沿用已验收测试。迟到备份场景在真实 IndexedDB readwrite 最终游标开始时注入一条记录（无伪造HTTP响应），验证初步列表为空也不能删除新备份并退出；用户明确删除此份后才完成退出。旧页面仅替换身份查询为旧快照以验证本地writer栅栏，所有保存/退出仍使用真实服务。

### 执行记录与边界

- 红灯 `.runtime/ticket12-save-logout-red.log`（缺少保存退出分支）、`ticket12-close-red.log`（缺少关闭入口）；对应组件及全量最终通过。资源测试初版误用卸载后清空的 emitted 记录，改为 DOM 事件是否仍被拦截；这是测试夹具修正，不称生产缺陷。
- `node scripts/verify.mjs` 全部通过，含Java18单测、Go本地/race/Linux、前端53项单测/lint/vue-tsc/build/audit；Windows35项集成跳过不计通过。日志 `.runtime/ticket12-verify.log`。
- 随后仅新增关闭文件交互，重跑前端完整54项单测、lint/vue-tsc/build，以及真实 workspace tracer；不重复无关Go本地验证。日志 `.runtime/ticket12-final-unit.log`、`ticket12-final-build.log`、`ticket12-final-workspace-ready.log`。后者实际执行20 WorkspaceIT + crash1 + StatusIT1，无跳过，全部浏览器及重启/故障链路通过；资源目录 `.runtime/cc-tracer-49756-857b47d2`。
- 中间浏览器失败均记录，不用重跑掩盖：第一次preview旧dist，构建后纠正；一次登录click后尚未完成即读REST，改为等待登录后导航；追加重登录次数触发真实429，合并已有退出/旧页场景以复用save-logout后的登录，未改限流；一次文件尚未加载就输入，改为等待源码并明确断言输入已进入编辑器。日志依次 `ticket12-browser.log`、`ticket12-browser-built.log`、`ticket12-browser-current.log`、`ticket12-final-workspace.log`。
### #12 初轮评审整改

- Standards发现P1：无ACTIVE编辑器时，面板在seal等待期间卸载会遗留封锁。新增延迟seal→unmount测试先红后绿；面板独立拥有路由与beforeunload保护，finally即使已卸载也以本次sealedEpoch调用条件释放。`releaseDraftLogout`同事务核对epoch和blocked，不改变后续actor状态。新增被更新epoch取代时不重开/不重建测试。
- Spec发现P1：seal后另一页重新登录可开启新epoch并写草稿，旧signOut的全账号清理会误删。保存退出改为显式 `signOut('saved')`，返回服务器退出是否确认，绝不在该分支调用clearDrafts。它原本已封锁空集合，不需要事后删除任何内容；后续新epoch草稿必须保留。真实浏览器延迟真实logout响应，在IndexedDB中布置重新登录后的epoch+备份，释放响应后断言备份仍在，同账号重新登录可恢复且服务器不被覆盖。该fixture模拟本地resume状态，不宣称在延迟窗口真正执行第二次HTTP登录。
- 该误删测试另做负对照：临时恢复saved分支的全量清理，浏览器期望1条备份实际0条而失败。差异与日志 `.runtime/ticket12-late-login-negative.diff`、`ticket12-late-login-negative.log`。恢复修复后通过，负对照未留源码。
- 两轴均发现P2：关闭文件未释放Monaco model。新增close/reopen adapter测试先红，再在空selection时卸载并dispose当前model，不创建empty-path model；另存buffer仍可重开重建，切换文件与组件销毁行为不变。
- 定向红/绿日志：`ticket12-seal-dispose-{red,green}.log`、`ticket12-close-model-{red,green}.log`。最新前端57项单测、lint、vue-tsc、build通过；完整workspace tracer20+crash1+StatusIT1及浏览器通过，无跳过。日志 `.runtime/ticket12-review-unit.log`、`ticket12-review-build.log`、`ticket12-review-workspace.log`。期间类型检查指出测试guard返回类型被推断为true字面量，补boolean标注后再执行；不是业务缺陷。
- 整改独立复核完成：Standards 与 Spec 均0项发现、无阻断；Spec确认七项验收均满足。两轴核对源码、负对照及最终日志，未自行重跑测试；lint由父代理实际执行通过。#12已完成验收，#13–#16未实施。

## 首期时钟风险取舍（后续确认）

用户在确认没有同域 Linux 跳变故障证据后，决定首期不建设时钟异常检测、准入锁定/人工恢复，也不为此新增单调计时改造，见 [ADR 0003](../adr/0003-accept-shared-system-clock-risk.md)。保留同一 Linux 时钟域、固定持久化截止时间、正常过期拒绝及 RPC 超时机制，不修改系统校时设置。约 1 分钟偏差是用户可接受风险的示例，不是偏差上限保证，也不是新增授权宽限。

上文“时钟异常保护尚未实现”保留为当时事实，但该专项已不属于首期验收门槛；没有将风险接受记作缺陷修复或测试通过。正常时间下的到期竞争、跨进程恢复和 #10–#16 其余验收仍须完成。本次仅修改决定与文档，未新增业务代码或测试执行证据。
