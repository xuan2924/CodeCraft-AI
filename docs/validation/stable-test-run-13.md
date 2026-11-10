# #13 稳定版本测试：交付与验证记录

状态：**#13 验收通过；#14–#16 未交付。** 有效规格为 `.scratch/workspace-test-foundation/issues/13-stable-test-run.md` 与 `docs/design/`。早期切片及红灯历史保留在任务 Comments 和提交 `f21dc32`、`38fe48e`；本文记录最终实现，不新增产品政策。

## 已实现的正式链路

- 浏览器：只能运行 `base`；有脏缓冲时选择“保存后运行”或“返回编辑”。逐个保存并确认修订，保存失败、未知保存结果或期间出现新编辑均不启动。运行后可继续编辑/保存；结果显示启动修订及旧版本提示。POST 未知保留原请求键和原修订，仅显式重试；挂载/刷新只查询，不自动启动。
- Java：`POST /api/exercises/{id}/tests`、单任务 GET、最近 20 项 GET。校验登录、归属、操作凭证、ACTIVE/截止、版本和已发布目标；V9 持久化任务/执行身份、幂等指纹、一次性派发和全局单槽。不同键并发拒绝，无隐式队列；相同键异载荷冲突。
- Go：正式 `StartTest` / `QueryTask`。与写入/CloseGate 同协调区完成授权、稳定副本和准入；固定发布配置选择镜像、suite/tests/限额，不接收用户命令。执行登记先于创建容器，task ID 不可换 execution ID 重用，绑定从持久记录重建。worker 独立于 RPC 取消/页面接管，停机取消并等待所有者退出。
- 结果：Go 保留完整权威证据、结构化终态及有限日志；Java 校验身份后持久化快照。REST 返回题目版本、包摘要、镜像身份/平台，以及证据种类、字节数、SHA-256；execution ID + 种类定位持久结果内的原始证据，不提供任意文件读取入口。列表不携带日志正文。
- 清理：确认进程静默与持久终态后，标记副本已释放再删除文件树，禁止迟到重放重建；容器与副本清理分别可查。操作报错不等于仍在执行：已确认删除容器的未知 Start 仍完成副本清理。复制成功后授权过期或 worker 取消时，登记 INTERRUPTED 清理责任方，不创建容器。
- 未知状态：派发响应丢失只查询；查询不存在还需推进工作区栅栏并再次查询，才可确认旧请求不能注册。不把一次“查无记录”当成安全重启许可。损坏登记、未知容器、未确认静默保持关闭准入。

## 隔离与部署边界

固定本机 image ID、离线 Maven 模板、只读权威测试/POM；仅挂本次副本的只读 volume subpath，不暴露整个工作区卷。真实容器使用非 root、禁网、只读根、drop ALL、no-new-privileges、builtin seccomp，并限制 CPU、内存/swap、PID、tmpfs/shm、core 和日志。stdout/stderr 合计有界、单片至多 16 KiB、最多 4096 片；超量继续排空，丢弃字节数与流不完整状态单独记录。

Docker SDK 为 `github.com/docker/docker v28.5.2+incompatible`，沿用已锁依赖。测试环境 Docker Desktop Linux/amd64、Engine 29.4.0；SDK 协商 API 1.51。拒绝低于 1.45 或无 builtin seccomp 的 daemon。固定 SDK exec 收集器解决 Docker archive/cp 不能读取 tmpfs 的限制；没有开放任意命令接口。完整 Surefire XML 上限 2 MiB，交叉检查 suite、testcase、计数、跳过和退出码；不依据控制台成功标记判分。

**Compose 的受信任 Go 协调进程持有 Docker socket，等同宿主管理权限。** `DOCKER_SOCKET_GID` 需匹配宿主 socket 组；只读根、cap_drop 或 socket 的只读挂载均不能把此权限变为低权限。用户测试容器绝不持有 socket。宿主仍须按部署环境隔离受信任控制面；本机验收不代替生产宿主复验。

## 编译证据与版本兼容

- 保留已发布 `cart-total/1.0.0` 身份，不替换镜像或迁移已有演练。新增 `1.0.1` 的受保护执行配置，并在隔离 tracer 内实际验证、发布和执行；未修改本机原有业务发布记录。
- 旧版本无执行配置时 `executionAvailable=false`，浏览器明确禁用，REST 在保留任务/容量之前返回 `TEST_TARGET_UNAVAILABLE`，不默默用新镜像执行旧工作区。
- 1.0.1 用固定 JDK 21 的 `DiagnosticListener` 采集诊断代码/来源/位置，不再匹配英文异常正文。Maven compiler 3.14.1 通过固定 fork executable 调用原 javac 参数解析；禁用注解处理器。没有新增下载依赖。
- 只有来源正确的源码 ERROR、受控 javac ERROR 退出及完整证据可判代码失败；I/O、读写权限、位置/处理器配置错误、SYSERR/异常、资源耗尽和证据写入失败不出具源码失败证据。证据上限 16 KiB，与日志独立保存；查询重新校验。
- 为复用 javac 的完整命令行解析而不是自研参数解析，薄适配层使用 `com.sun.tools.javac.main/util/file` 三个 JDK 内部导出。**升级固定 JDK 必须复验此适配层**；不宣称内部 API 跨版本稳定。清理使用 try-with-resources 保留原异常。此机制不提供对恶意候选代码的防作弊保证。
- 本机正式启用需维护者先验证/发布新版本，再配置未跟踪 `.env` 的 `PROBLEM_PUBLICATION_FILE` 指向新 artifact 并重建服务镜像；旧演练仍绑定旧身份。示例：

```sh
node scripts/validate-problem.mjs problems/cart-total/1.0.1 .runtime/validation-cart-1.0.1
node scripts/publish-problem.mjs problems/cart-total/1.0.1 .runtime/validation-cart-1.0.1 .runtime/problem-publication-1.0.1.json
```

官方接口核对：[Maven compiler 3.14.1](https://maven.apache.org/plugins-archives/maven-compiler-plugin-3.14.1/compile-mojo.html)、[JDK 21 javac Main](https://github.com/openjdk/jdk21u/blob/master/src/jdk.compiler/share/classes/com/sun/tools/javac/main/Main.java)、[诊断代码](https://github.com/openjdk/jdk21u/blob/master/src/jdk.compiler/share/classes/com/sun/tools/javac/resources/compiler.properties)、[Docker volume subpath](https://docs.docker.com/engine/storage/volumes/#mount-a-volume-subdirectory)、[socket 风险](https://docs.docker.com/engine/security/protect-access/)、[cp 限制](https://docs.docker.com/reference/cli/docker/container/cp/#corner-cases)。

## 风险与实际验证接缝

| 不变量 / 提交点 | 故障或竞态 | 实际公开行为验证 |
|---|---|---|
| Java 事务固定身份/指纹/槽位 | 同键并发、异键并发、异载荷、越权/旧凭证、过期/目标不可用 | 真实 REST + PostgreSQL + Go；复用同 task，409/404 拒绝，不产生额外任务 |
| 同协调区复制与准入 | 运行时保存、页面接管、调用方取消、任务 ID 换执行 ID | 真实 gRPC + Docker，原 revision 结果不变，接管不杀任务，身份重用冲突 |
| 注册先于外部副作用 | 进程死亡、Start 响应丢失且延迟送达 | 真实子进程死亡保留占槽；故障注入后精确删除容器，迟到 Start 返回 404 |
| 副本有清理责任方 | 操作报错但已静默；复制发布后恰好过期/取消 | 两个审查发现均以负向控制复现，修复后实际副本删除、终态/清理持久化、不创建过期容器 |
| 权威证据独立于日志 | 语法、缺符号、类型不匹配、重复类；不可写输出、编译器栈耗尽 | 源码失败 FAILED；I/O 与 StackOverflowError 为 ENVIRONMENT_ERROR；损坏证据拒绝恢复成绩 |
| 隔离先于运行 | Java 探针访问网络、权限、资源及替换入口 | 实际 UID/caps/NNP/seccomp/网络/cgroup/tmpfs；参考解通过，初始代码失败，练习文件不绕过权威入口 |
| 超时先终止再回收 | 超时、所有者取消、容器删除失败、日志洪水 | kill/inspect/remove；TIMED_OUT/INTERRUPTED；cleanup 失败可见且不改变既有成绩；日志截断保留完整结论 |
| 浏览器不测试脏缓冲/旧结果冒充当前 | 保存失败/期间继续输入、未知 POST、运行时继续保存、刷新 | 组件失败路径；真实浏览器初始失败→确认保存参考解→运行时保存→通过并标旧版本；刷新只查询 |

故障注入只拦截指定真实 Engine 请求，其他操作仍走实际 daemon；不将它描述成 daemon 自身宕机。无配置目标的 REST 负向用例临时移除隔离测试库中的执行策略并在 finally 恢复，不改发布文件/镜像，也不是旧镜像执行兼容性证明。

## 最终执行结果

1. `CC='D:/hot/prototype/CodeCraft-AI/.tools/llvm-mingw-20261104-ucrt-x86_64/bin/clang.exe' node scripts/verify.mjs`：通过。包含完整真实 Docker 执行、Linux 工作区 gRPC、Java 本地测试、Windows Go race，以及前端 **9 文件 / 62 测试**、类型检查/lint/build/audit。日志 `.runtime/task13-verify-final.log`。
2. `node scripts/test-tracer.mjs --workspace`：通过。**22 项 WorkspaceIT、1 项 WorkspaceCrashRecoveryIT、1 项 StatusIT** 全部真实依赖执行；两个工作区浏览器用例、状态/依赖故障/重启探针通过。日志 `.runtime/task13-full-chain-final.log`，隔离服务日志 `.runtime/cc-tracer-5196-81dc9b70/`。
3. 最终题目包 `node scripts/validate-problem.mjs problems/cart-total/1.0.1 .runtime/validation-cart-1.0.1` 通过，覆盖当前 README/包身份，日志 `.runtime/task13-package-final.log`；只验证，不修改正式发布记录。
4. 单独验证：类型诊断先复现旧白名单漏判；清理负向控制日志 `.runtime/task13-cleanup-red.log`，对应修复及正式 gRPC 用例通过 `.runtime/task13-cleanup-green.log`。新增测试曾因 Wait API/SQL 表名写错失败，修正后重跑；不把编译失败/夹具失败冒充行为红灯。全链路曾因额外登录触发现有限流，测试改为仅在 worker 内存复用已有 Cookie，不放宽服务端限制。
5. 最终双轴独立审查：规范、规格均 **OK with notes**，未发现新阻断项；要求保留最后 tracer 结果，该复验现已通过。此前两个清理 P1、构造器装配、异常保留及响应证据遗漏均已修复并复审。审查只读，没有把审查员未执行的测试说成独立复跑。

## 明确未验证 / 未交付

- verify 中跳过的其他真实依赖 IT 不等于执行过；本票真实依赖证据来自单独 tracer。Windows race 不覆盖 Linux-only 执行包；未宣称 Linux race、生产宿主、实际 daemon 宕机或磁盘耗尽完整矩阵通过。
- #14：主动停止、SSE/实时日志、断流续接、完整执行重启重接/宿主代次、未知创建主动对账及清理重试。当前未知仅保守占槽；不盲重启，不把它冒充恢复完成。
- #15：冻结/结束与最终验收；#16：持久化容量/保留/删除和阶段整体验收。当前普通任务清理不等于历史回执 GC 或完整灾难恢复。
