# P2-11：DeepSeek 首条真实端到端验收

**Type:** implementation
**Status:** needs-info
**Blocked by:** P2-09 修改提案、Diff 与审批；P2-10 Copilot 受控测试工具
**Spec:** [第二阶段规格](../spec.md)

## What to build

建立可重复、显式触发的浏览器—真实 DeepSeek 非思考模式—Java—PostgreSQL—Go—Docker 验收，证明读取、审批修改、测试与模型回复是一条真实业务链路。

## 开工门槛

第一阶段达到 Dev Done（功能、L1 与代码审查完成，不含远程 CI 或重型验证）；P2-09/10 的正式接口及自身验收完成；依赖中的真实配置、费用提示与验证限额已确定。不能用综合票替代前置票首次联调。

## Acceptance criteria

- [x] 用受邀用户个人配置进入阶段性入口，准备真实工作区和模型检查，显示题目与计时，不展示不存在的面试官或报告。
- [x] 从页面提交需求，真实模型读取代码、产生提案，用户查看 Diff 并批准；正式版本准确推进，记录调用/提案/回执关联。
- [x] 在该已保存版本运行真实 Docker 测试，模型收到结构化结果并继续或回复；日志/版本与页面显示一致，不以聊天文字判定测试通过。
- [x] 至少覆盖拒绝后继续、基线冲突、断线恢复观察和停止所属测试等已实现路径，确定性故障与真实调用分别报告。
- [x] 联调凭证可读取环境变量 DEEPSEEK，输出与报告只含脱敏配置/模型身份；不保存秘密、不默认启动收费回归。
- [x] 提供实际运行入口、前置条件和可重复测试方式；缺少真实模型或 Docker 时标未验证，不宣称闭环通过。
- [x] 缺陷回到所属功能修复并补回归；验证报告区分单元/边界、真实供应商、Docker、浏览器结果。

## Comments

这是第一个完整里程碑，不代表第二阶段已完成。P2-12～21 的控制、压缩、Skill、兼容性和保留仍需交付。

### 实施取舍

- 复用隔离 tracer 与现有受邀 USER 身份夹具；供应商 Key 仅从环境传入可信 Java 测试容器，测试专用受鉴权入口委托正式连接 Service 建立个人连接，浏览器及其驱动均不接收 Key，页面执行模型选择/真实检查、开始计时、发送需求、Diff 审批、结果观察与断线恢复。
- 新增明确收费的浏览器模式，不接入默认 smoke/quick。为硬限制验证费用，只在该临时 Java 容器挂载测试专用 Spring BeanPostProcessor 与连接夹具，通过既有 ProxyFactory 为真实 ModelCallDriver 设最多 12 次调用；不修改正式任务轮次策略，不引入模型替身或 TLS 代理。正常 Java 镜像不包含这些测试类；夹具 Controller 还以 disposable 属性限制发现，避免污染普通 Spring 集成测试。
- 收费浏览器场景关闭 trace/video/自动截图，避免 Cookie 或表单/网络负载进入故障制品；仅显式保存不含凭证的业务画面，结构化日志限模型身份、状态、版本与用量。拒绝、基线冲突等沿用前置票确定性证据，真实浏览器/供应商/容器证据分开记录。

### 交付与实际验证

- 新入口 `node scripts/test-tracer.mjs --workspace --copilot-browser`，前置条件与边界已写入 README。支持 `DEEPSEEK` / `DeepSeek`；无 Key 或混用其他套件时在编译/创建资源前拒绝。每次拥有随机独立 DB/卷/会话，不默认启动收费验证。
- 最终真实链路整体通过：`.runtime/cc-tracer-37432-6d8fb1e3/`。浏览器 **30.2 秒**、Playwright 合计 31.8 秒，不把镜像/构建/服务准备耗时算入交互时间。使用普通 USER 身份夹具及其私人连接，未替换任何模型、Go 或 Docker 结果；邀请/登录本身沿用第一阶段验收，不宣称本例重新覆盖了邀请注册。
- 页面选择精确个人模型版本，显式确认收费并真实检查，开始计时并展示题目与截止时间；从页面发需求，模型 `view_file` 读取修订 0，产生单文件提案。核对 Monaco Diff 与完整文本后，断开并关闭页面，再进入只观察原任务/提案，无新提案；显式接管操作权并批准。正式写入修订 1，保存内容与批准文本逐字一致。
- 同任务正式 `run_tests` 使用修订 1 真实 Docker 稳定副本，终态 FAILED、Surefire XML 权威证据、quiescent 与容器/副本清理均成功；关联测试卡片打开原记录，页面修订/失败状态/日志与 REST 一致。模型收到同一原测试 ID 的工具结果并回复 FAILED。新增注释而不修复初始题目错误是有意的验证设计，不将此结果描述为题目解答通过。
- 验证了已确认的 `view_file`、`propose_changes`、`run_tests` 工具消息、原父任务/提案/测试关联及 0→1 修订；底座仍以原 FILE_BATCH operation/回执收敛。无遗留执行容器，数据库/卷 finally 清理，明文供应商 Key 扫描通过；浏览器驱动确认环境不含该 Key，测试专用连接入口对未认证请求返回 401/403。
- 最终 JVM 确认预算代理安装，记录 **7 次真实 dispatch**：检查 3 次、Copilot 4 次。四个 Copilot turn 输入/输出/总 token：`1358/52/1410`、`1735/179/1914`、`1941/75/2016`、`5238/265/5503`，均非 unknown；不估算费用。这不是全部尝试的累计计费次数。
- 确定性故障证据复用 P2-09/10 的 PostgreSQL 验证：`rejectionContinuesTheTaskAndDoesNotRegisterTheSameRejectedPatchAgain`、`approvalRechecksOwnershipControlAndBaselineAndCannotAuthorizeNewContentWithAnOldKey`、`stopAcceptanceIsNotProcessExitAndResultVersionMustStillMatchAtCommit` 等。停止所属真实测试复用 P2-10 Linux Java/Go/Docker/DeepSeek 已通过证据；不把这些宣称为本例再次进行的浏览器拒绝/冲突/停止交互，也不扩展成故障矩阵。
- 默认 quick 通过：Java **259**、Vue **156**，以及 Node 契约、类型/lint、Go quick/vet/Linux 编译与 vet；33.31 秒（30 秒性能目标警告、60 秒硬限内）。真实入口的 Java 打包和前端生产构建通过。预算代理新增 Spring 接线、未启用排除、12 次后拒绝及未安装代理时禁止凭证夹具启动的自动化测试；Node 覆盖无凭证/歧义参数前置拒绝。

### 发现与修复、审查结论

- 首次启动失败暴露已有 jar 早于最新 classes 的问题；专用浏览器入口改为先 `package`，而非只 test-compile。第二次在收费前遇到页面有两段相同说明导致严格定位失败，改为定位阶段说明段落。两次均未派发模型请求。
- 第三次真实浏览器链路通过，但附加统计误查 `codecraft.copilot_turn`，实际迁移在 public；该整条命令判为失败。同时复查发现通过 initializer 属性安装的预算没有实际生效，不能声称该次已受 12 次硬限保护。改用带 disposable 条件的真实 Spring BeanPostProcessor，增加先红后绿接线测试，并让凭证入口在启动时检查目标驱动包含同一限额 advice，未装即失败。修复后才取得上面的最终整体通过证据；该次中间尝试已实际收费，未隐瞒为免费探针。
- 自审确认正式产物不打包测试类，不改生产轮次策略；Key 仅进入可信 Java 环境/正式加密存储，测试 API 不返回 Key；不扩大 Go 权限，不自动重试未知副作用。测试支持文件的准备也纳入 finally 清理范围；仅测试清理放置和文档调整不重复收费。
- 本票无已知功能缺口，P2-11 scoped Dev Done；P2-12～22 仍按后续票继续。
