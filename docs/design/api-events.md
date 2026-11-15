# 接口、身份与事件设计

状态：身份产品规则、页面操作权及恢复行为已确认。账号登录与密码重置已实现，运行证据见[任务 07](../validation/account-login-07.md)及[任务 08](../validation/password-revocation-08.md)；工作区及跨 Go 撤销已有 [#09](../validation/problem-workspace-09.md)、[#10–#12](../validation/workspace-write-browser-10-12.md) 证据，SSE 的阶段进展见 [#14](../validation/stream-stop-recover-14.md)；#14 已按其最新任务记录的 L1 边界收口，不能将历史验证记录或下文契约当作新增真实运行证据。

## 身份与授权

- 首期可信邀请制；管理员后台创建账号、重置密码，不开放自助注册。
- 使用 PostgreSQL 保存服务端登录会话，浏览器通过 HttpOnly Cookie 携带随机凭证，不采用 JWT。密码仅存安全哈希，写操作做 CSRF 防护。
- 每次请求校验会话有效性与资源归属；密码重置时更新密码并撤销该用户旧登录会话。Cookie 尚在浏览器也不再被认可。
- 已建立 SSE 不能只在连接时鉴权：撤销后关闭相关连接，连接存续期间定期复核。每批私有事件发送前及空闲时最多每 5 秒复核，撤销后本进程通知关闭；具体排序见 [任务 05 协议](task-event-recovery.md#撤销与发送边界)。
- 登录撤销不自动停止已启动任务；停用账号的任务处理另行决定。
- 浏览器仅访问 Java；Go 仅接受受信任内部调用。演练、文件、任务、审批、反馈及事件流均做权限校验，游标或事件 ID 不构成授权。
- 管理员内容访问边界见 [架构](architecture.md#配置权隐私与费用)，模型凭证见 [模型接入](model-access.md)。

## 账号与登录契约（任务 02）

### 已确认规则与初始化

用户在任务 02 对话确认以下规则：首次启动从未跟踪的 `.env` 初始化管理员，不使用额外初始化命令；可信渠道人工交付临时密码；新建/重置后强制首次改密；允许验证旧密码后自行改密；密码最低 **8 位**，采用 Argon2id，不要求字符种类组合；登录可续期，闲置 7 天失效、单次登录绝对上限 30 天；本地 HTTP + Vite 代理、生产同源 HTTPS；CSRF Token + 来源校验；重置/改密撤销全部旧会话，但不停止任务。

- 规划配置名 `BOOTSTRAP_ADMIN_USERNAME`、`BOOTSTRAP_ADMIN_PASSWORD`，启动脚本只传白名单变量，不打印值。正式脚本由任务 06 提供，Spring Boot 不会因文件名为 `.env` 而自动加载。
- 数据库初始化事务串行化检查与创建：存在管理员则跳过，绝不能因环境变量变化覆盖密码；不存在管理员且配置缺失/不合法则启动失败，不开放匿名创建管理员入口。并发启动只能创建一个初始管理员。
- 管理员初始化也标记必须改密；初始化成功后可删除 `.env` 中初始密码。管理员遗失凭证的运维恢复流程尚未定义，不能通过重启隐式重置。
- 新建/重置的临时密码由安全随机数生成器生成，只在成功响应中向执行管理员展示一次，由其通过可信渠道交付。账号查询不回显；响应丢失时不能恢复明文，只能显式再次重置。
- 初始、临时及新密码均执行最低长度规则；密码不 trim、不截断。实现需统一前后端 Unicode 长度计数及输入上限，作为任务 07 的边界测试，不暗中增加字符组合要求。
- 密码、哈希、Cookie、CSRF Token、初始化配置和临时凭证不进入生产日志、链路追踪、审计参数或跟踪文件；审计仅记录执行者/目标标识、动作、结果、时间。凭证响应 `Cache-Control: no-store`，密码不进入 URL。
- 测试诊断例外：tracer 生成并随临时数据库销毁的测试凭证允许进入失败 trace/截图/录屏；不得读取开发 `.env` 或录制真实账号。预置 CI 配置限定只上传诊断白名单、保留 3 天（当前无远程运行证据），不上传环境文件或数据库。此例外不改变应用运行时日志规范。

### 任务 07 输入与安全参数（用户确认）

- 账号名 3–32 个 ASCII 字符，仅字母、数字、`_`、`-`；统一小写，不自动去除空白。
- 密码按 Unicode 码点计数，8–128 个；不 trim、不截断、不做 Unicode 归一化、不要求字符组合；拒绝不完整代理项。浏览器与 Java 使用相同规则。
- 登录和改密分别按账号及来源 IP 限制每分钟 5 次；两维均须通过，失败尝试也计数。来源取受信任入口的连接地址，不信任任意转发头。
- 匿名 CSRF 上下文有效期 15 分钟，全局最多 1,000 个；满额拒绝新建，不挤掉有效上下文。使用数据库约束和串行准入，使重启/多实例不绕过限制。
- 上述参数由用户在 #07–#15 实施对话确认，仅涉及身份契约；#16 的证据保留和单场限额已在后续讨论中独立确认，见 [工作区与执行](workspace-execution.md#保留与删除)，不能从身份参数推导容量政策。

### 哈希、会话与续期

- 使用 Spring Security `Argon2PasswordEncoder` 的 `encode` / `matches`，不自行实现密码学。技术参数基线为盐 16 字节、哈希 32 字节、并行度 1、内存 19456 KiB、迭代 2；参数随哈希存储，部署时验证耗时和并发容量。不能直接把框架 16 MiB 默认值称为 OWASP 的 19 MiB 基线。
- 后续正式工程引入 Spring Security 及其 Argon2 所需 Bouncy Castle，理由是复用密码编码与 CSRF 安全组件；当前不新增依赖清单，版本随 Boot 3 依赖管理及正式锁定核实，见[验证记录](../validation/identity-02.md)。
- 登录凭证是至少 256 位安全随机值，Cookie 携带原值，数据库只存不可逆摘要。它与用户、签发时间、最后有效活动时间、绝对到期时间、撤销状态及用户认证版本绑定；不使用 JWT，不把内存缓存当授权真相。
- 有效条件：未撤销、认证版本一致，且服务端 `now < lastActivity + 7 天`、`now < issuedAt + 30 天`。到期边界拒绝，不能靠续期复活；续期不能修改签发时间或跨越绝对上限。
- 仅由用户显式操作触发且鉴权成功的请求推进活动时间。SSE 建连/重连/心跳、后台轮询、CSRF 获取和会话状态查询不续期；不能信任客户端任意声明“用户活跃”。任务 07 按路由明确可续期操作清单。
- 数据库续期使用带有效性条件的原子更新，时间只前进；与撤销竞争时不能覆盖撤销状态或旧认证版本。Cookie 剩余寿命不超过数据库剩余闲置期限与绝对期限中较短者，服务端检查始终权威。
- 每次成功登录生成新凭证并轮换 CSRF 上下文，防会话固定；重新登录不自动撤销其他设备会话。退出仅撤销当前会话，改密和重置撤销该用户全部会话。
- 必须改密的账号只获得受限会话：仅可读取自身最小身份/改密要求、获取 CSRF、改密和退出，不能访问工作区或管理员功能。改密成功撤销包括当前受限会话在内的所有旧会话，清 Cookie，重新登录。

### 浏览器、Cookie 与 CSRF

| 环境 | 浏览器入口 | 登录 Cookie |
|---|---|---|
| 本地开发 | `http://localhost:<Vite端口>`，相对路径 `/api` 由 Vite 代理到 Java | `HttpOnly; SameSite=Lax; Path=/`，不设 `Domain`，不启用 `Secure` |
| 正式部署 | 单一 HTTPS 入口同时提供静态文件及 `/api` 反向代理 | 同上且必须 `Secure`；非 HTTPS 配置不能静默降级 |

Cookie 名规划为 `codecraft_session`；清理时使用同名同路径。开发环境固定使用同一主机名，不混用 localhost 与 127.0.0.1。浏览器不直接访问 Java 的另一端口；不开放带凭证的跨源 CORS。后端信任明确配置的公共源，不从任意转发头推导可信来源。

- GET 不做业务写入。所有 POST/PUT/PATCH/DELETE（含登录、退出）验证 CSRF Token 和来源；`Origin` 必须精确匹配公共源，缺失时用 `Referer` 的源，均缺失或不匹配则拒绝。不能只依赖 SameSite。
- 已认证 REST 请求支持可选 `X-Codecraft-Owner` 条件头：提供时必须等于该请求 Cookie 所属的当前用户 ID（空值也拒绝），不匹配返回 401 `UNAUTHENTICATED`，在进入业务前拒绝；它不是授权凭证，也不替代新鲜鉴权、资源归属或 CSRF。模型设置页所有模型读写必须携带已确认账号 ID，防止其他标签页切换 Cookie 后将旧页 Key 保存到新账号或把新账号数据写入旧账号查询缓存。旧客户端不携带时保持既有接口兼容；会话发现和 CSRF 获取不绑定旧账号，以便重新确认身份。
- `GET /api/auth/csrf` 返回 CSRF Token（不缓存），前端仅内存保存并在 `X-CSRF-TOKEN` 请求头提交。登录前使用短期匿名 CSRF 上下文，登录成功后换为登录会话绑定上下文；具体 `CsrfTokenRepository` 适配由任务 07 测试，不直接依赖进程内 HttpSession。
- 登录、退出后重新获取 Token；旧上下文不能继续写入。匿名上下文寿命和限额由任务 07 随登录限流实现，不得无限建表。安全过滤器异常使用明确 HTTP 状态，不返回 HTML 登录重定向。

### 操作结果、权限与重复请求

以下固定路由、HTTP 语义及符号错误含义，JSON 外层包装见 [任务 05](task-event-recovery.md#rest-与幂等边界)。重置/改密/退出涉及跨端栅栏时可先返回 202 与操作标识；表中 200/204 表示已确认完成，而非仅受理。所有请求先执行安全校验；403 CSRF 拒绝优先于业务结果。已通过 CSRF 的请求中，无效身份返回 401，身份有效但无权限返回 403；受限会话访问业务返回 403 `PASSWORD_CHANGE_REQUIRED`。业务校验失败不产生部分副作用。

| 操作 | 成功 | 失败与重复行为 |
|---|---|---|
| `POST /api/admin/users` | 完成改密的管理员创建普通用户，201，返回用户标识与一次性临时密码 | 非管理员 403；输入无效 400；账号名唯一冲突 409。要求幂等键，同键重试不再次建号、不再次交付密码 |
| `POST /api/auth/login` | 200，Set-Cookie，最小身份及 `passwordChangeRequired` | 未知账号/错误密码统一 401 `INVALID_CREDENTIALS`，避免账号枚举；受限流请求 429。失败不建登录会话。登录不缓存成功凭证，每次显式成功登录可生成新会话，客户端不得自动重试 |
| `GET /api/auth/session` | 200，最小身份、角色、改密要求与到期信息 | 无效会话 401，不续期，不返回用户私有内容或凭证 |
| `POST /api/auth/logout` | 204，撤销当前会话并清 Cookie | 无会话也可在取得有效匿名 CSRF 上下文后返回 204；旧 Token 重放可能 403，应重新获取 Token。重复退出没有额外业务副作用 |
| `POST /api/admin/users/{id}/password-reset` | 完成改密的管理员执行，200，一次性临时密码；目标必须改密 | 非管理员 403；目标不存在 404；要求幂等键，同键不再重置、不再交付密码。管理员重置自己也撤销当前登录；不得借重置获得用户私有资源权限 |
| `POST /api/auth/password` | 当前用户验证旧密码后更新，204，撤销全部会话并清 Cookie | 旧密码错误 400 `INVALID_CURRENT_PASSWORD`；新密码不合规则 400。要求幂等键；旧会话在成功后重放返回 401，不能为重放豁免鉴权；用户用新密码重新登录核实结果 |

- 创建/重置/改密的幂等键按执行者、操作和目标隔离，与变更同事务提交。同键不同请求返回 409；同键已完成且仍有权限时，创建/重置返回 200 的已完成回执（目标标识、原操作标识、`credentialDelivered=false`），不含临时密码；同键不能再次改变密码。无权限时不泄露回执。幂等记录不保存明文凭证，请求指纹不得使用可离线枚举的裸密码快哈希；实现采用服务端密钥 HMAC，密钥不入库/日志。
- 同键并发只产生一次效果；不同键的密码变更按用户串行化，后提交的密码生效，期间签发的旧会话也撤销。任务 05 定义完整回执至少保留 7 天、未决不清除及到期墓碑防重放，客户端不能在未知结果时自动换键重发。
- 账号名规范化/长度、登录与改密限流参数在任务 07 实现前明确并测试；不扩展为开放注册、账号停用或角色编辑。管理员权限不绕过用户私有内容的归属校验。

### 重置一致性与任务 05 授权边界

- 用户密码哈希、必须改密标志、用户认证版本递增和全部旧会话撤销在同一个 PostgreSQL 事务提交；任一失败整体回滚。改密遵循同一事务边界。
- 登录与重置按用户认证版本排序：可先在事务外进行昂贵哈希验证，签发会话前必须在事务内重新校验所验证版本未改变；否则重新验证或拒绝，不能拿重置前密码签发重置后有效会话。
- 每次 REST 请求查询权威会话状态和当前用户认证版本。授权后的业务写入仍须与撤销确定先后：在提交业务变更的事务中复核并锁定授权状态，与重置按同一用户锁顺序串行化。撤销先提交则拒绝；业务先提交则保留已确认结果，不承诺回滚既有副作用。
- 上述短事务规则用于数据库内副作用；Go 文件提交必须先通过 [任务 05 撤销栅栏](task-event-recovery.md#密码重置改密与退出)排空/隔离旧代次，再提交身份撤销，不能将 JVM 本地锁当作跨服务保证。
- 对任务 05 暴露的授权检查语义为：会话标识、当前用户/角色、是否受限、认证版本、闲置/绝对到期；返回有效或失效，不把 Cookie 原值传给 Go 或任务工作线程。数据库无法确认时失败关闭，不能沿用旧缓存继续发私有内容。
- SSE 建连必须检查身份与资源归属；存续期检查同一权威状态，但不续期。撤销事务提交后通知连接关闭，通知失败不能撤销已提交的密码变更，须由定期复核兜底。任务 05 必须确定复核间隔、通知机制及在途事件的失效边界，任务 14 集成验证；本票不宣称撤销瞬间已经发出的字节可追回。
- 登录撤销不停止后台任务，也不改变页面控制权契约。重新登录后仍需检查资源归属和操作凭证。账号停用及运维恢复另行决策，不能等同于密码重置。

## 页面操作权

同一演练仅一个页面拥有编辑、任务启动、审批和 steer 等操作权；其他页面只读。登录 Cookie 识别用户，额外随机操作凭证识别控制权，不宣称能鉴别物理标签页。

- 服务端签发操作凭证，页面内存保存，不放入会跨标签页共享的 Cookie/localStorage。
- 显式接管原子地转移操作权并使旧凭证失效。旧页面即使未收到只读通知，后续写请求也被拒绝。
- 接管不停止后台任务，不搬运旧页面草稿；刷新重新申请，不静默抢占另一页面。
- 本人恢复停止/结束是显式安全终止权限，不是页面写权限：仍检查当前登录/密码状态、CSRF 和演练归属，但不需要页面凭证、不签发新凭证、不授权保存/新测试或取消系统最终验收。取消意图按测试去重、收尾按演练去重，因此普通容量耗尽或刷新后仍可安全终止，不允许无限免费接管挤占救援空间。见 [ADR 0007](../adr/0007-owner-recovery-stop-and-end.md)。
- 身份、归属与业务状态校验独立于操作凭证，用户不能借凭证绕过授权。
- 操作权切换与在途写入必须有确定顺序，不能仅在请求入口校验一次。首期不设自动续租/抢占的页面租约；刷新无凭证时只读并显式接管，跨重启栅栏协议见 [任务 05](task-event-recovery.md#页面操作权与可恢复栅栏)。

## 任务与传输

- REST 创建/查询/操作任务，SSE 消费事件；创建与消费分离，重连不能重新发起模型或测试。
- 第二阶段区分 Copilot 任务与测试任务：同演练最多一个 Copilot 任务，其调用的测试仍使用底座单演练执行名额。持久化父子归属，停止 Copilot 联动停止其运行中测试，不停止用户独立手动测试；占用返回忙，不排队或抢占。具体接口/检查点由第二阶段决定票确定，行为见 [Loop](copilot-loop.md#copilot-发起的测试)。
- 启动、变更、批准、停止、接管等操作有幂等保护；作用域、回执、墓碑及一次性凭证重放见 [任务 05](task-event-recovery.md#rest-与幂等边界)。
- Go 测试 gRPC 使用服务端流，区分 stdout/stderr、进度和最终结果；历史 unary 示例不是正式契约。
- 透传 deadline/cancellation，但取消 RPC 不等于进程退出，Go 必须落实停止和清理，见 [工作区与执行](workspace-execution.md)。
- 流使用有界缓冲、输出上限及慢消费者策略；截断明确提示，不能静默丢失最终状态。
- 路由退出可关闭订阅，关页面不停止任务；用户通过“停止任务”请求停止。前端以服务端结构化状态结束加载，不从聊天或日志猜测成功。

## Copilot 任务与审批接口

- `POST /api/exercises/{id}/copilot/tasks`：`{text, feeAcknowledged}`，最多 4 KiB UTF-8 请求文字；要求当前操作凭证、CSRF 与幂等键，返回 202。同演练仅一个活动任务，不排队；原键重放只读取原受理记录。
- `GET /api/exercises/{id}/copilot/tasks/latest`：最近任务、消息高水位、历史文本转换提示、未确认回复预览；无任务返回 `null`。
- `GET /api/copilot-tasks/{taskId}`、`POST /api/copilot-tasks/{taskId}/stop`：读取或单调停止。停止要求当前身份、CSRF 与幂等键，但不要求工作区写入凭证或新空间；不停止独立手动测试。
- `GET /api/exercises/{id}/copilot/messages?after=...`：默认 `-1` 返回最近页，`0` 从最早记录开始，其余为向前读取游标。每页最多 100 条、数据库协议负载最多 512 KiB；前端不得把一页视为完整历史。
- `GET /api/exercises/{id}/copilot/tasks/{taskId}/events`：独立只读 SSE，`copilot_snapshot` 推送持久化状态与有界预览，不创建或重启任务。游标只用于快照去重；刷新/重连从 REST 消息记录恢复，不保证重放每个文本增量。所有终态以结构化任务状态为准。
- 未确认的文本在已预留空间内按块落盘，预览最多 4096 个 Unicode 字符并明确截断。它不进入下一轮模型消息，也不能执行工具；完整响应确认后才保存正式消息。失败保留已落盘预览，成功后由完整消息替代。
- REST 使用当前身份与资源归属校验，支持 `X-Codecraft-Owner` 防止旧页面跨账号提交；所有响应禁止缓存。SSE 复用现有有界非阻塞传输与持续身份核验。

### 修改提案

- `GET /api/copilot-tasks/{taskId}/proposals?before=0`：按提案序号倒序分页，最多 20 条元数据；`nextCursor` 继续向前查看。工作区修订与提案序号按十进制字符串传输，`fileCount` 为小范围整数。
- `GET /api/copilot-proposals/{id}`：不可变的整批修改前/后完整文本；最多 32 个文件，原文合计最多 10 MiB。前端仅为选中的提案加载，一次只挂载一个文件 Diff 编辑器，不轮询完整文本。
- `GET /api/copilot-proposals/{id}/status`：轻量状态、批准/拒绝及写入回执元数据，供只读轮询。审批已受理不等于文件已应用；`APPLYING` 仍须等待权威写入回执。
- `POST /api/copilot-proposals/{id}/decision`：`{decision: "APPROVE"|"REJECT", reason?}`；拒绝理由最多 2 KiB UTF-8，批准不接收理由或替换内容。要求 CSRF、原幂等键及当前操作凭证，重新检查当前用户、归属和任务/演练/基线。批准与既有 FILE_BATCH 意图同事务落盘，RPC 在事务外由原写入协调器执行。
- 同一提案只能决定一次；原键原内容重试读取原决定，不能覆盖它。页面读到该不可变提案已决定/取消可解除审批传输的不确定提示，但不能把 `APPLYING` 当已写入。新内容或新基线必须生成新提案。
- `WAITING_APPROVAL`/`WAITING_WRITE` 属于活动任务，但不占 worker；保存原批次位置和已完成工具。新会话的显式有效审批可为原任务后续调用更新授权，单纯登录、查询或重复读取原审批回执不能重新授权。
- 应用后前端失效工作区查询，沿用编辑器原有修订冲突处理；不自动保存、覆盖或清除本地草稿。停止在未知写入收敛前保持 `STOPPING`，不将未知结果伪报未应用。

### 演练审批模式

- `GET /api/exercises/{exercise}/copilot/approval-mode`：`{exerciseId, mode: "ASK"|"AUTO", version, history}`。缺省 ASK/版本 `"0"`；最近 20 条审计含 exerciseId、id、previousMode、mode、version、actorId、createdAt，按数字版本倒序。只读，不改变授权。
- 同路径 `POST`：`{mode, expectedVersion}`，版本为十进制字符串；要求有效 USER、ACTIVE、当前操作凭证、CSRF、原幂等键。期望版本冲突返回 `COPILOT_APPROVAL_MODE_CONFLICT`；同键不同内容拒绝。成功返回该次不可变审计，不代表后来仍是同一模式；前端重新读取当前快照，不用旧回执覆盖新选择。未知结果仅原键/原内容重试。
- 提案元数据增加 `modeVersion`（字符串）；AUTO 必须有正版本。登记线性化取最新模式，AUTO 自动决定与原 FILE_BATCH/WAITING_WRITE 原子绑定；旧 ASK 不自动批准，AUTO 禁止手动 decision。所有模式请求与既有私有 REST 一样归属校验、禁止缓存。

### Copilot steer

- `POST /api/copilot-tasks/{taskId}/steers`：`{text}`，成功 `202` 返回原指令回执；需要 USER、归属、ACTIVE、当前操作凭证、CSRF、幂等键及原父任务仍有效的授权。4 KiB UTF-8/最多 32 条待处理，不自动保存草稿，不另起任务。同键同内容返回原记录，异内容冲突；未知结果仅原键/原内容核对，查询或换账号不能自动重发。
- `GET /api/copilot-tasks/{taskId}/steers?before=0`：私有、no-store 的 `{items, nextCursor}`，数字接收序号倒序，每页 20 条，`0` 表示最近页。记录包含 `id, sequence, taskId, exerciseId, actorId, text, state, turnId, reason, createdAt, completedAt`；序号/游标为十进制字符串，GET 不派发模型或工具。
- `RECEIVED` 尚未纳入请求，`APPLIED` 已纳入持久请求上下文且带原 `turnId`，`NOT_APPLIED` 表示结束前未派发并带原因。后两者有完成时间；已纳入请求不等于供应商收到或成功回复。停止/到期不补发，已绑定写入/测试先按原 ID 收敛。
- `COPILOT_STEER_NOT_ACTIVE`（409）表示新指令不被当前任务受理，`COPILOT_STEER_LIMIT`（429）表示待处理队列已满。先接受的 steer 使旧待审批提案 `INVALIDATED/STEER_SUPERSEDED`，已批准原写入不由 steer 撤销。

### Copilot 上下文与压缩

- `GET /api/exercises/{exercise}/copilot/context`：私有、no-store，返回 `exerciseId, version, summaryId, throughSequence, summary, requests`；摘要为非权威文本，`requests` 为最近 20 条、按数字接收序号倒序。读取不准备上下文、不调用模型、不恢复任务。
- `POST /api/exercises/{exercise}/copilot/compactions`：`{feeAcknowledged:true}`，需要 USER、归属、ACTIVE、CSRF、当前操作凭证及幂等键，返回 `202`。同键只返回原请求；结果未知时仅原键/原内容核对。既有回执可在控制权变化后读取，不借重试重新派发。已有活动请求返回 `COPILOT_COMPACTION_BUSY`（409）。
- `POST /api/exercises/{exercise}/copilot/compactions/{id}/stop`：归属与 USER/CSRF 校验，单调取消，无需页面操作凭证。等待请求立即取消；已派发调用须先收敛，不能据此推断未收费或退款，不取消原提案/写入/测试。
- 请求记录为 `id, sequence, exerciseId, trigger, state, reason, attempts, stopRequested, sourceVersion, throughSequence, createdAt, completedAt`；序号/版本为十进制字符串，未派发来源字段可为 null。`trigger` 为 MANUAL/AUTO，状态为 WAITING/RUNNING/APPLIED/FAILED/INTERRUPTED/CANCELLED/NOT_NEEDED。`attempts` 只表示持久派发登记数（0–2），不保证供应商收到；NOT_NEEDED 表示当前无需继续压缩，不抹去此前已发生的调用。
- 前端区分受理未知、等待安全点、取消待收敛及 `MODEL_RESULT_UNKNOWN`，不自动重发收费请求。账号/演练切换取消本地观察与迟到回调；换控制凭证不丢失原压缩请求的未知回执。运行或受理未知期间不能另起新目标。

### 用量与估算费用读取

- `GET /api/exercises/{exercise}/copilot/usage`：本人演练读取，返回 `exerciseId, context, totals, costs`。`context.state` 为 UNKNOWN / REPORTED / WAITING_UPDATE，含最近 CHAT 的 `turnId, modelId, contextWindow, windowSource, inputTokens, dispatchedAt`；无证据字段为 null，窗口来源为 USER_CONFIGURATION，压缩后等待更新时 inputTokens 为 null。
- `GET /api/model-checks/{id}/usage`：本人检查读取，返回 `checkId, totals, costs`，只统计本组实际派发，复用检查不再次计数。两接口沿用登录、owner 条件头及 no-store 边界，不触发模型请求。
- `totals` 按 purpose（CHAT / SUMMARY 或 CHECK）返回 `calls, completeCalls, inputTokens, ordinaryInputTokens, cacheReadTokens, cacheWriteTokens, outputTokens, totalTokens, pricedCalls`。数值均十进制字符串，用量字段允许 null；部分字段的已知累计不是完整统计。`costs` 按 purpose/currency 返回 `amount, pricedCalls`，金额为十进制字符串；仅已知费用小计，不将未知或缺失数据当零。

### Copilot 关联测试

- `GET /api/copilot-tasks/{taskId}/tests`：返回最近 20 条绑定元数据（父任务、工具、原测试 ID、保存修订、`base`、`WAITING`/`RESOLVED`、确定终态与时间）。只读、归属校验、no-store，不触发执行或回填；完整记录沿用 `/api/exercises/{exerciseId}/tasks/{testTaskId}`。
- `WAITING_TEST` 是活动父任务，但不占 worker。等待时允许用户继续编辑和保存；页面标出原测试修订，不能把其结果归给新修订或草稿。
- 停止父任务仅对持久绑定的普通子测试登记取消。`STOPPING` 保留到原执行终态且 quiescent；系统取消 RPC 接受不等于进程退出。结束或授权失效后不会因迟到结果自动恢复模型请求。

## 保存与恢复

业务动作被服务端确认时持续保存，不依赖浏览器关闭事件作为保存点。

| 数据 | 恢复契约 |
|---|---|
| 工作区、阶段、审批及已完成结果 | 恢复已确认状态 |
| 对话 | 保存用户消息和模型回复；中断内容若保留须标记不完整 |
| 测试日志 | 保存限额内 stdout/stderr，截断可见 |
| 执行与操作记录 | 保存结构化结果、修改、审批和模式切换等证据 |
| 平台运维日志 | 独立管理，不直接作为用户日志展示 |

不承诺逐 Token/逐传输分片回放。重新进入可加载已保存内容和当前状态，不重演打字机过程；事件去重与完整历史重放是不同能力。

浏览器断线后任务继续，审批点暂停，`auto` 可继续写入。服务重启不是浏览器断线：

- 待审批可恢复并重新校验。
- 中断模型请求不自动重新收费调用，由用户重试。
- 写入结果未知先对账，不盲目重放副作用。
- 测试先查询 Go 状态；可确认则接续跟踪或读取结果，否则明确标记中断。

## 任务 Skill 快照读取

- Copilot 启动请求增加可选 `skillIds` UUID 数组，表示显式加载哪些启用 Skill 的主文档；既有无该字段请求仍有效。任务始终固定全部启用版本，重放校验原文及显式选择集合。
- `GET /api/copilot-tasks/{task}/skills` 返回 `skillId,versionId,name,description,explicitlySelected,explicitLoaded` 列表；后两个字段只描述显式加载，模型按需加载记录由原始 `load_skill` 工具结果呈现。
- `GET /api/copilot-tasks/{task}/skills/{skill}/package` 返回固定版本原文包，仅用于本人核对；不修改模型上下文、不新增加载记录、不触发模型请求。两接口均校验当前用户、任务/演练归属和访问生命周期，采用 no-store 与 owner 条件头；原件删除后仍可读取合法保留的任务快照。

## 个人 Skill 文本管理

- `/api/skills`：GET 返回本人原件元数据列表（含停用），POST 新建；`/api/skills/{id}` PUT 原子替换完整包，DELETE 删除原件引用。
- `/api/skills/{id}/package`：GET 导出当前原文 JSON 包；`/api/skills/{id}/enabled` PUT 切换启停，查询参数 `enabled`。
- 写操作必需 `Idempotency-Key`；更新/启停/删除必需查询参数 `expectedVersion`（十进制字符串）。同用户请求身份不可复用于另一操作/内容；重放仅返回原回执，不重新占用额度。版本冲突不覆盖原件。
- 包请求严格采用 `formatVersion:1` / `files:[{path,content}]`，重复 JSON 字段、未知字段、无效 YAML、路径或 UTF-8 均拒绝；仅新建/替换包路由的请求体上限为 2 MiB。导入传输保留原始 JSON，不能先解析覆盖重复字段再上传。
- 默认新建/导入停用。名称/描述从 `SKILL.md` 唯一提取，返回不可变 `contentVersion` UUID 和修改版本 `version`；其他字段为 `id,name,description,enabled,deleted,contentBytes`。编辑后的导出包含当前表单内容，页面注明未保存状态。
- 所有接口需要普通 USER 新鲜授权，私有读取也使用归属锁；管理员不能读取用户原件。`X-Codecraft-Owner`、CSRF、no-store 和账号切换规则沿用通用入口。失败码 `SKILL_INVALID_PACKAGE`、`SKILL_LIMIT_EXCEEDED`、`SKILL_NOT_FOUND`、`SKILL_VERSION_CONFLICT`、`SKILL_NAME_CONFLICT`、`SKILL_IDEMPOTENCY_CONFLICT`、`SKILL_STORAGE_UNAVAILABLE`。

## 待决与实现验证

- 任务 07/08 已落实安全库、输入/限流边界及数据库会话/重置事务；跨服务撤销已有工作区接入，SSE 见 #14 实施记录。账号停用和管理员凭证遗失恢复流程仍待决，详见[身份验证记录](../validation/identity-02.md)。
- 任务 05 已定义 [REST 包装、路由及幂等边界](task-event-recovery.md#rest-与幂等边界)、业务状态、gRPC 语义消息及日志信封；正式落地随 #09–#14 推进，具体结构以协议源文件、迁移及客户端实现为准，整票完成以任务记录为准。
- 测试结果包含任务、版本、状态、耗时等证据；未启动/无法确认退出时 `exitCode=null`，中断与环境异常独立于代码失败。
- [任务 05 恢复协议](task-event-recovery.md)定义查询/订阅衔接、有界游标、停止竞争、连接撤销及持久化栅栏；最小模型不等于真实 SSE、数据库、Go 文件提交及 Docker 故障证据，但不因此将真实故障矩阵设为默认开发门槛。
- 数据保留期限见执行设计；不能把保存内容恢复的决定扩大为无限事件重放承诺。

## 验收要求

以下行为以确定性 L1 覆盖核心分支；真实跨服务、浏览器和故障验证按明确安排或具体问题最小执行，不逐票展开全套矩阵，也不自动生成 QA 欠账。

覆盖跨用户访问、密码重置与在途 SSE、CSRF、页面接管与旧凭证写入、刷新竞争、重连不重复启动、幂等、终态竞争及慢消费者。故障注入验证任务恢复、未知回执对账、日志截断与状态查询；验证 Java/Go 生成契约和前端消费一致性。
