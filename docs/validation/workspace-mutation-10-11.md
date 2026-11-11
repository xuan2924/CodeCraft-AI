# #10/#11：Go 原子写入与栅栏失败切片

> 历史验证记录：保留当时的执行事实、失败及限制，不新增当前验收义务。当前完成边界以根 [AGENTS.md](../../AGENTS.md) 和对应任务最新结论为准；旧“后续门槛”不自动成为待办，已有证据无需逐票重跑。

历史切片记录（`dccf112`）。后续 Java/浏览器实现及最新未验证项见 [控制权、保存与草稿切片](workspace-write-browser-10-12.md)；下文“尚未接通”等状态保留为当时的验证边界。

## 交付边界

本记录仅覆盖 `.scratch/workspace-test-foundation/` 的 #10/#11 **Go 存储边界**。不是这两张任务完成，更不是 #10–#16 完成。

- 正式 `WorkspaceStorage` 增加 `OpenGate`、`ApplyBatch`、`QueryMutation`，Java/Go 均从同一 proto 生成。
- CREATE/UPDATE/DELETE 组成整批变更；重命名以 CREATE + DELETE 表达，成功推进一次修订。无变化保存仍发布持久化回执，不推进修订。
- 回执、文件哈希及完整新树同一个 commit 发布；指纹绑定操作、基线与排序后的变更内容。已提交同键重放返回历史结果，同键异载荷拒绝。
- Apply 与 Close/Open 共用正式服务串行协调区，沿用进程入口对私有根的独占。历史 Open 重放不改变当前栅栏；旧代次与过期授权拒绝。准备阶段取消不能删除已激活工作区或写入证据。
- 保持现有不可变全量复制方式；当前内容校验 200 文件、单文件 1 MiB、合计 10 MiB，初始化输入也校验。RPC 解码上限 12 MiB，容纳合法批次的文本及路径开销。回执 manifest 达现有 1 MiB 上限时拒绝新增，不静默淘汰回执。
- 浏览器仍没有保存入口；Java 尚未调用新写入 RPC。只有可信内部调用可访问 Go，不能将内部 gate 当作浏览器授权。

## 已执行验证

环境：Windows 宿主、Go 1.26.5；Docker Desktop Linux/amd64；测试容器非 root、禁网、只读根、drop ALL、no-new-privileges，临时文件系统 128 MiB。使用真实 TCP gRPC 和临时文件系统，不用业务成功替身。

| 执行项 | 结果 |
|---|---|
| `node scripts/generate-proto.mjs` | Java/Go 生成成功 |
| 首次运行 `TestMutationReceiptSurvivesLostResponseAndRestart` | 预期红灯：OpenGate 未实现，不是编译或环境错误 |
| `node scripts/test-workspace.mjs` | 最终完整套件 28 个顶层测试通过；子进程专用 helper 在父进程 skip，在故障测试中实际执行 |
| `cd backend/sandbox && go test ./...` | Windows 可编译模块通过；不包含 Linux 工作区测试，不能替代上面的 Docker 结果 |
| `cd backend/sandbox && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go vet ./...` | 通过 |
| Maven Wrapper `test` | Checkstyle 0 违规，Java 18 个测试通过，含新生成协议编译；不是 Java-Go 业务集成测试 |
| `git diff --check` | 通过 |

Java 现有 StatusHttpTest 明确使用不可用依赖地址，输出预期依赖失败及调度器连接错误；测试统计为 0 failures / 0 errors。未因此宣称数据库联调成功。

### 失败与竞态证据

- **真实进程响应丢失：** 子进程运行正式 Service；RPC interceptor 在 Apply 成功后、发送响应前退出。客户端收到 UNAVAILABLE，重新启动服务后查询到同一回执及完整新树。生产代码不含故障注入钩子。
- **暂存写入失败并退出：** 子进程用 Linux RLIMIT_FSIZE 强制暂存文件写入 EFBIG，随后退出；重启查询无成功回执，读取原树及原修订。该证据不是实际磁盘 ENOSPC、断电或持久卷损坏测试。
- **栅栏排序：** Close 后迟到 Apply 拒绝；旧 Open 重放不重开；并发 Close/Apply 只能观察到完整提交或被拒绝两种结果。重新创建服务后栅栏有效。
- **查询无回执的边界：** 未关闭栅栏时，NOT_FOUND 后仍可到达并提交原请求；因此 Java 必须先关闭栅栏，不能据 NOT_FOUND 判定取消。
- **幂等与基线：** 同键并发只提交一次；不同键相同基线仅一个成功；无变化回执在后续版本推进后重放仍保持原结果；不同文件不绕过工作区级冲突。
- **原子批次：** 删除源、新建目标后另一项校验失败，正式树保持不变；去掉失败项后重命名仅推进一次修订。
- **安全与容量：** 保护文件、路径穿越、前缀伪装、NUL、重复路径、同名/缺失文件拒绝；源树符号链接、目录链接、硬链接、FIFO 不被复制。达到 200 文件或 10 MiB 的合法树可读，超一项/一字节整批拒绝。
- **损坏：** 最新 manifest 损坏不能回退旧版本，也不返回 NOT_FOUND；缺失回执历史和非法回执字段失败关闭。

## 仍未实现或验证

1. #10：Java 持久化操作/transition、单页面凭证、身份撤销协调、未决写入 worker、REST 权限与回执核对、浏览器编辑保存。
2. #11：Java 文件操作与浏览器文件树/草稿交互、跨用户 REST 测试。
3. #12–#16：草稿、测试执行/日志/停止、生命周期冻结与最终验收、删除与保留未在本切片实现。
4. 内容限制尚为存储模块集中常量；全链路配置、单场 512 MiB 跨存储计量、收尾预留、历史引用/GC、删除墓碑、真正 ENOSPC 等属于后续容量切片。当前全量复制与累计回执不具备长期生产容量保证。
5. 主机时钟回退监测、发布临界时刻受控时钟、断电及目标部署持久卷验证未执行。截止授权使用已有发布前 deadline 检查，不将入口过期测试等同全部临界验证。
6. 未运行 race detector、真实 Java REST—Go 写入集成、浏览器端到端或题目执行容器；Linux 测试跑在真实 Docker 内不等于已实现 Docker 测试执行器。

下一可验证切片：先接通 Java 持久化控制权转换及身份撤销栅栏，再加入 Apply 派发/查询对账；以“Go 已提交、Java 未落回执时重启”和“接管先关闭、旧调用迟到”验证成功后，才开放浏览器保存。

## 后续逐票验收补充

上述列表是 Go 初始切片的历史边界，Java/浏览器及崩溃恢复后续证据以 [#10–#12 验证记录](workspace-write-browser-10-12.md) 为准。#10 逐票收口新增 `TestMutationExpiresAfterStagingBeforePublication`，真实 gRPC 已准入、完整暂存树 fsync 后自然到期；原发布前检查拒绝，重建服务无成功回执且原内容/版本不变。临时删除发布前检查的负对照因错误发布而失败，恢复后通过，不再把入口过期测试替代发布边界证据。此测试未调整系统时间；时钟异常专项保护按 ADR 0003 排除，断电/目标持久卷验证未因本测试而宣称完成。
