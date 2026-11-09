# #10–#12：控制权、保存与浏览器草稿切片

本记录接续 `workspace-mutation-10-11.md`，基线 `dccf112`。仅记录本次可验证改动；**不是 #10–#16 整体完成或生产上线许可**。

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
