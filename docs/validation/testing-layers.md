# 测试分层与黄金路径整改

## 范围与决定

按本次用户清单整改 #01–#13 相关实现及当前测试入口；历史一次 ECONNRESET 按用户要求跳过，不重新调查。已有未提交的 host epoch/inventory/恢复改动保留，不作为本次提交的交付内容。

- 新迁移 V13 移除物理外键，历史迁移校验和不改；关联存在性/归属继续由 Service 的事务校验负责。保留唯一性、主键、非空、CHECK 和索引。
- PR 并行运行 quick 与 smoke；heavy 只在主分支 push、nightly 或手动触发执行。
- `verify.mjs --quick` 为默认，30 秒热缓存预算并终止超时的自有进程树；无 Docker、race 或完整 Spring HTTP IT。首次依赖下载和编译属于明确的准备步骤。
- `--all` / `--heavy` 执行快速层、真实 HTTP IT、Docker、race、浏览器存储集成及完整 tracer。重型 Node 清理故障测试独立命名，不进入 8 项快速契约入口。
- 三条浏览器黄金路径：管理员登录/改密/邀请/退出；输入/刷新/草稿恢复；保存后运行/结果/刷新恢复。工作区用例各自通过临时 fixture API Seed 身份，并通过正式 API 创建工作区，不依赖另一条测试。
- fixture API 只在 tracer 进程中启用，使用随机密钥、loopback 端口及受检查的临时库；无生产测试路由，不读取开发 `.env`，不修改生产登录限流。测试数据由临时数据库/卷整体销毁。
- IndexedDB 的事务、冲突草稿、游标、epoch、晚写入和清理移入独立 Chromium 集成层，无 Java/Go/PostgreSQL。
- 失败保留 trace/截图/录屏；CI 白名单产物保留 3 天，仅包含一次性测试凭证，不上传环境文件或数据库。

## 风险与快速验证

| 不变量 | 提交点/风险 | 验证 |
|---|---|---|
| 关联由应用校验，数据库不再建立硬外键 | 最新迁移/身份夹具清理 | LogicalReferencesTest；真实新库 IdentityIT 元数据检查与全套身份边界 |
| 失效授权不能提交准备结果，也不能卡死清理事务 | 身份服务事务代理；捕获异常后 rollback-only | PreparationAuthorizationTest、RecoveryAuthorizationTest；撤销恢复 IT |
| 保存不改变运行中的稳定输入 | 副本发布与正式保存；跨文件混版 | Go 双文件竞争；候选代码 OLD 标记后阻塞，保存 NEW 后显式释放，最终只能输出 OLD |
| 时间策略不靠真实等待 | 业务授权到期与发布前复核 | Go 注入业务 Clock，发布前分别检查业务授权与真实传输 deadline；Java MutableClock；前端批处理/重试 fake timers |
| 系统进程期限不被重新授予 | 原执行 StartedAt 与期限 | 精确按配置期限轮询，不再固定等 17 秒；真实超时/清理集成样本 |
| 退出不误删新草稿 | IndexedDB 单事务游标、epoch | 真实浏览器本地集成；无后端，无 E2E 数据库 hack |
| 清理失败不掩盖原始错误 | Node finally | 聚合原始错误及清理错误；Docker 用例使用独立临时证据目录 |

## 实施中发现并修复的真实缺陷

将真实 5.5 秒授权等待分解为可控时钟的到期策略测试和真实撤销恢复测试后，后者首次失败：状态一直 PREPARING，日志为 UnexpectedRollbackException。

原因是工作区在跨 Spring 事务代理调用 `authorizeMutation` 后捕获 IdentityFailure，但内层已标记共享事务 rollback-only。新增 `tryAuthorizeMutation`，在身份服务事务边界内将正常失效/受限身份转换为明确的无授权结果；数据库故障及撤销进行中错误仍传播。两项定向 WorkspaceIT 随后通过，未采用重跑掩盖失败。

## 实际验证

以下为本机当前工作树执行结果，不代表远程 CI 已通过：

| 入口 | 结果 |
|---|---|
| `node scripts/verify.mjs --quick` | 通过，26.59 秒；Node 8、Java 22、前端 66 项通过；含 Vue 类型/Lint、Linux Go 编译/vet、Go 快速行为 |
| `npm --prefix frontend run test:integration` | 7/7，4.7 秒；真实 IndexedDB，无后端 |
| `test-tracer --smoke --identity --workspace` | 3/3，3 workers，浏览器阶段 19.0 秒 |
| `test-tracer --smoke --workspace --browser-test="save and run"` | 单独 1/1，15.7 秒，无前置浏览器用例 |
| `test-tracer --identity --boundary-only` | 14/14，无跳过；包含当前迁移无物理外键检查 |
| 定向 WorkspaceIT | 稳定任务/归属及撤销后准备清理 2/2，39.24 秒 |
| 定向 Go 工作区 | 发布前到期 0.05 秒；双文件版本竞争 0.01 秒 |
| 定向 Go Docker | 准备后到期/取消清理 0.17 秒；原期限恢复 1.23 秒；OLD/NEW 显式同步 8.48 秒 |
| `validation-cleanup.docker.test.mjs` | 1/1，22.03 秒；不再修改共享 `problems/.validation` 回执 |

最后一轮增加夹具 ID 校验时，发现创建接口只返回受理信息，工作区实例 ID 应从 READY 快照取得；已修正并复跑三条并行 smoke 通过。失败轮实际生成了 trace、截图、录屏及报告，诊断配置不是仅静态声明。

浏览器时间不包含镜像构建、受信任题目发布校验和服务启动。Lint 为 0 errors，现有 Vue 格式 warnings 未冒充清零。本次未重复执行整套重型矩阵，也未宣称远程 CI、生产可靠性或后续未提交恢复功能已验收。
