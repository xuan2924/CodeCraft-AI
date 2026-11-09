# 任务 07：账号与登录验收

## 范围

交付首次管理员初始化、受邀账号创建、登录、强制/自行改密、退出、数据库会话及 CSRF。输入与限流参数以[身份契约](../design/api-events.md#任务-07-输入与安全参数用户确认)为准。管理员重置密码、工作区、SSE 撤销及跨 Go 栅栏属于后续任务。

## 实际执行（2026-11-09）

在 Windows、JDK 21、真实 Docker PostgreSQL、Go 和 Chromium 上依次执行：

| 命令 | 结果 |
|---|---|
| `node scripts/verify.mjs`（CC 指向仓库 `.tools/` 中已验证的 LLVM-MinGW clang） | 8 项脚本测试、10 项 Java 单元/HTTP 测试、Go vet/测试/race/构建、13 项前端测试、lint、vue-tsc、构建及 npm audit 通过；11 项外部集成测试按条件跳过 |
| `node scripts/test-tracer.mjs --identity` | 10 项真实 PostgreSQL/REST 边界测试、1 项真实状态集成、1 条 Chromium 账号闭环通过；已有数据库会话跨 Java 重启有效，修改 bootstrap 配置未覆盖账号 |
| `node scripts/test-tracer.mjs` | 原有正常、Go 停止、数据库停止、恢复、Java 停止五次浏览器检查通过；数据库不可达时启动失败关闭 |

边界测试覆盖：并发初始化、已有管理员不覆盖、空库缺少合法凭证拒绝、受限账号、角色拒绝、同键并发建号/重放不交付密码/异载荷冲突、真实事务回滚、改密撤销全部会话、旧密码验证结果不能跨认证版本签发会话、7 天闲置和 30 天绝对期限、查询不续期、显式动作续期、匿名 CSRF 容量/到期、来源与 CSRF 轮换、账号/IP 限流。

浏览器使用正式 API 和生产构建，完成管理员改密、建号、临时密码交付、用户 Unicode 密码修改、角色拒绝及退出；检查键盘提交与窄屏。凭证不进入查询/Mutation 缓存或浏览器持久存储；账号测试关闭 trace、截图和视频。测试使用独立 Compose 项目、数据库和端口，结束清理进程、容器、卷及临时凭证文件。

## 实现核实与修正

- 实际依赖：Spring Security 6.5.11（Boot BOM）、Bouncy Castle 1.86；核对[固定版本 Argon2 源码](https://github.com/spring-projects/spring-security/blob/6.5.11/crypto/src/main/java/org/springframework/security/crypto/argon2/Argon2PasswordEncoder.java)及[CSRF 配置源码](https://github.com/spring-projects/spring-security/blob/6.5.11/config/src/main/java/org/springframework/security/config/annotation/web/configurers/CsrfConfigurer.java)。哈希参数、不同盐与错误密码已有实际测试。
- PostgreSQL 时间参数显式转为 `timestamptz`，避免参数减 interval 的类型推断错误。
- CSRF 上下文仅在真正登录时轮换，关闭框架在逐请求认证时隐式执行的 CSRF 登录策略；CSRF 删除与会话撤销职责分离。
- 状态接口保留原响应契约；修正安全过滤器与状态控制器重复写入 `Cache-Control` 的回归。
- 匿名上下文和限流窗口落库并串行准入；限流表最多 2,000 个短期桶、Argon2 最多两个并发任务，满额拒绝。业务数据/回执总体配额与保留仍归 #16。

## 结构重构回归（2026-11-09）

- 业务代码与测试迁入 `ai.codecraft.modules`；公共响应/异常独立，配置使用构造器绑定的 `@ConfigurationProperties`。新增分层、错误包装和配置绑定检查。
- `maven clean test`：17 项测试通过。`node scripts/verify.mjs`：17 项 Java 测试、13 项前端测试以及脚本、Go、静态检查、构建和审计通过；外部集成测试按条件跳过。
- `node scripts/test-tracer.mjs --identity` 复跑：10 项身份集成、1 项状态集成、浏览器闭环及重启会话验证通过。`node scripts/test-tracer.mjs`：五种浏览器状态及启动失败关闭通过。
- 首次身份回归在重启前的 CSRF GET 遇到一次 `ECONNRESET`；未修改代码的复跑通过，代理日志未提供原因。该偶发测试网络故障尚未定位，不将复跑成功称为修复。

## 未验证

生产 HTTPS 端到端、目标部署负载、Linux/远程 CI、跨服务撤销栅栏、SSE 旧连接、工作区归属与测试隔离尚未验收。Secure Cookie 有边界测试，不等于生产 HTTPS 已验收。当前连接 IP 取直连地址；本机 Vite 代理请求共享 loopback 限额，不信任任意转发头。第一阶段完整交付尚未完成。
