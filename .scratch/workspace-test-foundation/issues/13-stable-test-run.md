# 13：运行稳定版本测试

**Type:** implementation
**Status:** ready-for-agent
**Blocked by:** None
**Spec:** [第一阶段规格](../spec.md)

## What to build

用户在浏览器启动服务端允许的测试目标；Go 从指定版本准备稳定副本，在一次性受限 Docker 容器中执行，页面查询并显示结构化结果。

## Acceptance criteria

- [x] 有未保存内容时先选择保存后运行或返回，不默默测试旧代码。
- [x] 创建和查询任务分离；唯一测试任务及重复启动幂等生效，不隐式排队。
- [x] 复制与写入协调，执行期间继续保存不改变测试输入；结果关联启动版本并标记旧版本。
- [x] 使用真实禁网、非 root、受限权限与资源配置，受保护验收入口不可被用户文件绕过。
- [x] 日志采集有界、最终结果持久化，区分失败、超时和环境错误；实时展示留给任务 14。
- [x] 超时能杀死相关执行；正常/失败/超时后清理容器和副本，清理失败可观察，不先交付不受限执行器。
- [x] REST/gRPC 验证越权与并发，真实 Docker 验证禁网、权限、限额及稳定副本，浏览器启动到结果链路通过。

## Comments

**最终验收：#13 已完成。** 下面保留历史切片记录；以末尾“正式链路交付”和 [最终验证记录](../../../docs/validation/stable-test-run-13.md) 为当前结论。Status 是分诊标签，不代替上面的完成清单。

本任务可以通过状态查询展示结果，不伪造流式日志。主动停止、断流和重启恢复在任务 14 完成前不得宣称已交付。

### 实施前风险切片（基线 `b2ccd72`，未实现）

#10–#12 已逐票验收完成。现有正式 `.proto` 尚无执行任务消息，Go Publication 只含题目身份/初始文件，Compose 的 Go 尚无执行 Docker 权限；不把维护者发布脚本当作正式用户执行器。

测试沿用规格已确认的真实 REST/PostgreSQL/Go gRPC、Docker 和浏览器接缝。先做受限 Go 执行失败/超时与稳定副本纵向切片；未通过前不暴露浏览器启动按钮，不先交付不受限执行器。

| 验收条件 / 不变量 | 权威状态与提交点 | 失败或竞态 | 可观察结果 / 测试入口 |
|---|---|---|---|
| 稳定版本与授权 | Go 与 Apply/CloseGate 同协调区校验并复制，Java 事务绑定身份/页面/期限/版本 | 复制与保存竞争、旧基线/旧epoch、过期派发 | gRPC 旧请求拒绝，执行副本始终单一revision；原工作区后续保存不影响测试输入 |
| 唯一任务与不盲重启 | Java 持久化task/execution ID、请求指纹/槽位；Go创建容器前持久化登记 | 并发不同键、相同键异载荷、Start响应丢失 | REST409或原task引用；查询不启动新容器，未知任务继续占槽；不隐式排队 |
| 先隔离再执行 | 固定已验证镜像/目标与runtime，受控Docker配置 | 用户测试伪造、越界挂载、网络/权限/资源探测 | 实际Docker非root/禁网/只读根/dropALL/no-new-privileges/seccomp/CPU/内存/PID/tmpfs限制；只挂本次输入，用户测试不替换镜像验收 |
| 结果与日志独立 | 完整报告+退出信息，Go持久化终态；stdout/stderr有界尾部 | 编译失败、断言失败、缺报告、OOM/环境错、日志洪水 | 不以BUILD SUCCESS/退出码单独认定通过，日志截断不抹终态，持续排空不堵塞 |
| 超时先确认静默再回收 | Go持久化执行登记和精确容器标识 | 保护超时、kill/remove未知或失败 | 真实进程终止及inspect确认；未静默不释放槽位；结果与cleanup状态分别可查 |
| 清理与恢复责任边界 | 原task/execution、受控容器labels、结果引用 | 创建后Go死亡、遗留容器/副本、存储不可用 | #13不能把未知视为无任务；#14完成主动停止、inventory/重启重接与流式恢复前保持明确未交付，不能无登记按空表开放Start |
| 浏览器不悄悄测试旧代码 | 当前buffer提交回执与task启动revision | 草稿未保存、保存失败、请求未知、运行后继续保存 | 保存后运行/返回；保存失败不启动；结果显示启动revision及过时标识，只轮询不伪造流 |

### 工具链核对（不是通过证据）

- 本机 Docker Engine `29.4.0`，API范围 `1.40–1.54`。官方 Moby `v26.1.5/api/swagger.yaml` 定义API1.45的 `Mount.VolumeOptions.Subpath`，可将本次稳定副本子目录作为只读输入挂载，而不向用户容器暴露整个工作区卷；使用前须在受限执行测试中确认实际挂载。
- 用户确认沿用原有已确认技术路线：Go 使用 Docker SDK，不采用标准库自建 Engine HTTP 客户端；以 `docs/design/architecture.md` 和阶段规格为准。引入 SDK 用于复用 Docker 协议适配，实施时核实接口兼容并锁定版本。socket只给受信任Go协调进程，绝不挂用户执行容器；授予socket等同宿主管理权限，须在部署说明显式记录，不声称read-only socket挂载能限制API操作。
- 官方资料：Docker卷子目录说明 <https://docs.docker.com/engine/storage/volumes/#mount-a-volume-subdirectory>；daemon socket风险 <https://docs.docker.com/engine/security/protect-access/>；API原始规范 <https://raw.githubusercontent.com/moby/moby/v26.1.5/api/swagger.yaml>。HTML API页提取失败，已核对官方原始规范，未因此改本机外部工具配置。
- 受限tmpfs中的报告不能假设容器退出后仍存在。正式报告收集须在受控退出前完成，并与有限日志分开保留；现有发布脚本只提取testsuite头，不能直接冒充正式完整报告判定。此点进入首个真实执行切片验证。

### 当前工作区实施进度：稳定输入前置切片（未完成本票）

- 新增内部 `PrepareTestCopy` / `QueryTestCopy` RPC；与保存、CloseGate 共用协调区，校验 revision、gateEpoch 和授权截止，独立复制文件并原子发布完整回执。历史重放只返回原输入，不授予执行权限，也不登记/启动容器。
- 副本查询校验内容摘要及链接安全；已发布目录缺失回执/内容视为不可确认，不按不存在重新创建。执行 ID 不可换修订重用。
- 准备取消发现副本时拒绝，避免清理旧逻辑遗留执行证据却报告完成。副本的引用登记、容量、回收和执行任务接入仍未实现；此接口尚无 Java/浏览器调用方，不作为可交付执行器。
- TDD：最初两个 gRPC 用例因 Unimplemented 失败，实施后通过；审查发现取消误报后，回归用例先复现失败，再修复通过。另覆盖服务重建、保存/复制竞争、旧修订/关闭栅栏/过期拒绝、损坏/符号链接/硬链接拒绝。
- 实际验证：`node scripts/generate-proto.mjs`、新用例单独执行、`vue-tsc --noEmit` 通过；`scripts/verify.mjs` 使用仓库既有 LLVM-MinGW 设置当前进程 CC 后全部通过（含 Linux Docker 内全部工作区 gRPC 测试、Java 本地验证、Windows Go race、前端 8 文件 57 测试、lint/build/audit）。第一次未设置 CC 的全套检查因旧 Windows race 工具链加载错误 `0xc0000139` 中止，不能算通过。
- 未验证：Java 真实依赖集成测试在本地 verify 中明确跳过，未运行新浏览器链路；副本发布中途进程死亡/磁盘不足、复制期间截止、执行容器隔离/终止/结果/清理均未验收。工作区测试在 Docker 内运行不等于正式执行器隔离通过。#13 验收仍全部未勾选，#14–#16 未开始。
- 独立双轴审查：规范无硬性违例，提出原子发布逻辑重复的维护建议；规格审查发现取消清理误报，已以失败回归测试驱动修复。原子发布公共提取暂不扩展本切片。
- 按用户要求，仅保留未暂存工作区改动，不 stage、不提交。

### 后续进度：真实受限 Docker 适配器（仍未完成本票）

- 新增 `backend/sandbox/internal/execution/`，锁定官方 Docker SDK 28.5.2；持久化登记先于创建，受限子目录挂载，完整报告与有界尾部日志分离，确认静默后保存终态并清理。未接入 Go 服务运行入口、Java/REST/浏览器；不先开放运行按钮。
- 新增 `scripts/test-execution.mjs` 并纳入 verify。实际验证参考通过、初始失败、缺报告环境错误、隔离/资源限制、日志洪水、超时、工作进程取消、并发拒绝、进程死亡后的占槽、清理失败可观察，以及真实 gRPC 稳定副本送入 Docker 后并发保存不改变输入。
- 真实验证发现 archive/cp 无法读取 tmpfs；核对官方说明后改为固定、限量的 SDK exec 报告采集通道，不放宽隔离。独立审查发现未知 Start 可能误报静默，回归先复现迟到 Start 仍返回 204，再以精确容器删除栅栏修复为 404；损坏持久化 XML 的假恢复通过也已先红后绿修复。
- 整套 verify 已执行通过（Java 本地 18 测试、35 个真实依赖 IT 明确跳过；前端 57 测试等）。审查修复后重跑全部真实执行测试、Linux Go vet 和 go mod verify 均通过；最终执行测试日志在忽略的 `.runtime/execution-13-final.log`。没有将 Windows race 称为 Linux 执行 race，也没有宣称新的 Java/浏览器链路通过。
- 编译失败分类、正式任务协议与业务槽位、稳定副本引用/回收、主动恢复及 #14–#16 仍缺失。具体风险映射、审查与未验证项见 [#13 切片验证记录](../../../docs/validation/stable-test-run-13.md)。

### 后续进度：编译证据切片（本票仍未完成）

- 新增未发布题目包 `cart-total/1.0.1`，保留已有 1.0.0 发布身份。固定镜像中使用 Maven 官方 EventSpy 接口采集有限编译证据，不新增下载依赖、不放宽执行隔离。
- 真实 Docker TDD 复现语法错误原先归环境错误；语法/验收 API 缺符号现为 FAILED，证据与截断日志分离，损坏记录不能恢复成绩或重启。
- 独立规格审查发现单凭 CompilationFailureException 会误判权限/I/O 错误；真实不可写编译输出目录回归先红，收紧为固定诊断位置、源码错误白名单及完整续行后转绿。未知诊断仍保守报环境错误：此切片不是所有编译失败的完整分类，也未兼容旧 1.0.0 镜像。
- 实际执行新版本维护者 Docker 验证通过，未将其导入业务发布记录；新包 README 的旧版验证命令问题已修复。最终整套 `scripts/verify.mjs` 使用既有 LLVM-MinGW CC 通过（含真实 Docker 执行与 Linux 工作区测试、前端 57 测试、类型检查/lint/build/audit、Java 本地测试及 Windows race）；Linux `go vet ./...`、`go mod verify` 通过。Java 真实依赖 IT 在 verify 内明确跳过，未跑新 REST/浏览器链路。日志 `.runtime/verify-13-compilation.log`。
- 正式任务协议、持久化业务槽位/派发、授权排序、副本引用/回收、浏览器保存后运行/轮询/旧版本与真实 REST/浏览器链路仍未实现。原验收项继续保持未勾选，不宣称 #13 完成。
- 双轴审查：规范轴未发现硬性代码违例，修复新包验证文档指向旧版的问题；规格轴的输出 I/O 误判已以失败回归修复并复审通过。旧镜像兼容、未识别编译诊断和全票缺失明确保留为未完成项。按当前用户指令提交可验证切片，早先“不暂存、不提交”的交付约定不再适用于本轮。

### 正式链路交付：#13 验收通过

- 正式 StartTest/QueryTask、Go 服务所有者生命周期、Java V9 持久化任务/一次性派发/幂等/单槽、REST 查询与启动已接通；浏览器支持保存后运行、轮询、原请求键重试和旧修订标识。未知任务不盲重启，不隐式排队。
- 执行与副本清理分别持久化；副本释放墓碑阻止迟到重建。独立审查发现“已静默操作错误不清理副本”和“发布副本后过期丢失清理责任”两个 P1；负向控制复现后修复，并补真实 Docker 回归及复审。
- 编译分类改用固定 JDK DiagnosticListener 和 javac 原参数解析，不再依赖部分英文文案。语法/类型/重复类/验收 API 错误、不可写输出和编译器栈耗尽均实测；资源故障不判代码失败。薄 JDK 内部 API 适配层升级需复验，无新增下载依赖。
- REST/页面补齐不可变题目/镜像身份和有界证据摘要。1.0.1 已在隔离链路验证并发布；本机原有 1.0.0 不覆盖/不迁移。旧版本无执行策略时显式禁用并在任务保留前拒绝；正式启用新版本仍须维护者发布和配置。
- 最终 `scripts/verify.mjs` 通过：真实 Docker/Linux 工作区测试、Java 本地测试、Windows race、前端 9 文件/62 测试、类型检查/lint/build/audit。单独完整 `scripts/test-tracer.mjs --workspace` 通过：22 项 WorkspaceIT、1 项 WorkspaceCrashRecoveryIT、1 项 StatusIT、两个工作区浏览器用例及依赖故障/重启探针。日志 `.runtime/task13-verify-final.log`、`.runtime/task13-full-chain-final.log`。
- 最终双轴审查均 OK with notes；未发现新阻断项，要求的最终 tracer 复验已通过。既有 AGENTS.md 用户改动不纳入提交。
- #14 主动停止/SSE/执行重启重接/清理重试、#15 结束/最终验收、#16 容量/保留/删除与阶段总验收不在本票交付中。未宣称 Linux race、实际 daemon 宕机、磁盘耗尽完整矩阵或生产宿主复验通过。

### #16 联动发现的日志持久化缺陷

最小真实 PostgreSQL 回归重现：合法原始日志含 NUL 时，Java UTF-8 文本投影的 `\u0000` 被 jsonb 拒绝，阻断权威终态持久化。现将 NUL 显示为 `␀`，非法 UTF-8 仍按替换字符显示，Go 原始字节/证据不变；公开 `TestTaskCoordinator.observe` 加实际 MyBatis/jsonb 用例已红绿通过。此修复纳入 #16，未为本问题重跑完整 Docker/浏览器链路，也不改变既有完成边界。
