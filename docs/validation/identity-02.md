# 任务 02：身份契约核实与验证边界

## 用户确认

来源：任务 #2 实施对话。先提出 12 位密码、固定 7 天期限和终端初始化命令，用户分别改为 **8 位**、支持续期、首次启动自动初始化；这些被否决的选项不是当前规格。

最终确认：`.env` 首次初始化管理员、已有管理员跳过不覆盖、首次必须改密；临时凭证可信渠道人工交付；新建/重置强制改密；自行改密验证旧密码并撤销全部旧会话；Argon2id；7 天闲置/30 天绝对期限；本地 HTTP + Vite 代理、生产同源 HTTPS；CSRF Token 与来源校验；重置与撤销原子提交、不中止任务。

契约唯一维护于 [接口与事件](../design/api-events.md#账号与登录契约任务-02)。技术细节是任务 02 的设计产物，不冒称用户逐项确认了路由名、哈希参数或存储布局。

## 实际完成的核实

- 检查实际目录、依赖及脚本：只有后端一次性 PoC，`backend/server/`、`backend/sandbox/`、正式前端清单和启动脚本尚不存在；不借用模型 PoC 测试声称身份行为通过。
- 核对 Spring Security 6.5 官方密码存储文档及 `6.5.x` 源码：`Argon2PasswordEncoder(int saltLength, int hashLength, int parallelism, int memory, int iterations)`；`encode(CharSequence)`、`matches(CharSequence, String)`；实现使用 `ARGON2_id` 和 `withMemoryAsKB`，需要 Bouncy Castle。
- 核对默认工厂 `defaultsForSpringSecurity_v5_8()` 实际是 16 MiB、2 次、并行度 1。OWASP 的一组最低建议为 19 MiB、2 次、并行度 1，因此契约显式指定 19456 KiB，不盲用框架默认值。
- 核对 CSRF 扩展接口 `CsrfTokenRepository`、默认 `HttpSessionCsrfTokenRepository` 及登录/退出后 Token 刷新要求。默认进程内 HttpSession 不自动满足本项目数据库会话与撤销契约，不宣称仅加 starter 即实现。
- 官方接口说明已解决本次 API 疑问，未额外创建兼容性探针或安装依赖。

### 一手来源

- [Spring Security 6.5 密码存储](https://docs.spring.io/spring-security/reference/6.5/features/authentication/password-storage.html)
- [Spring Security 6.5.x Argon2PasswordEncoder 源码](https://github.com/spring-projects/spring-security/blob/6.5.x/crypto/src/main/java/org/springframework/security/crypto/argon2/Argon2PasswordEncoder.java)
- [Spring Security 6.5 CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)
- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)

分支文档不是正式依赖锁文件；任务 06/07 需核对 Boot 实际解析的精确版本和 Bouncy Castle 安全版本。未引入 Spring Session 或选择新的会话框架。

## 尚未执行与明确阻塞

本次只修改契约文档，没有正式身份程序，**未执行类型检查、单文件业务测试、全套业务测试、真实数据库并发或浏览器端到端测试**。TDD 应在下表 REST/数据库/浏览器边界执行，不能用文档检查或一个内存模型代替。

| 责任任务 | 阻塞条件与必须验证的行为 |
|---|---|
| 06/07 | 正式 Maven/Vue 清单与启动脚本尚不存在；锁定 Spring Security/Bouncy Castle，构建、哈希 encode/matches/不同盐、错误密码、日志脱敏及实际负载耗时 |
| 07 | 用真实 PostgreSQL 验证空库初始化、重启不覆盖、并发初始化、无配置失败；创建唯一冲突、普通用户禁止管理、受限会话不能进入业务 |
| 07 | 统一账号规范化/长度及密码 Unicode 计数/最大输入；配置并测试登录限流、匿名 CSRF 上下文期限与容量，防止密码哈希和预登录存储资源耗尽 |
| 07 | 使用可控时钟验证 7 天闲置、30 天绝对边界、活动续期、不因查询/轮询/SSE 续期、撤销与续期竞争不复活 |
| 07/08 | 数据库真实事务验证密码更新失败整体回滚、重置使全部旧会话失效、改密验证旧密码、旧密码验证与会话签发之间并发重置、业务写提交与撤销排序 |
| 05/08 | 固定幂等回执窗口/字段，验证同键并发、响应丢失、重放不生成第二个密码且不回显秘密、同键不同载荷冲突；不为重放跳过鉴权 |
| 07 | 浏览器验证本地代理与生产 HTTPS Cookie 属性、登录前 CSRF、Token 轮换、来源不符拒绝、无效身份 401 而非 HTML 跳转 |
| 05/14 | 确定 SSE 撤销复核周期与通知失败兜底、验证到期/撤销后停止私有事件交付，同时后台任务继续 |

以上是明确实现阻塞与验证义务，不是已通过验收。账号停用与管理员遗失凭证的运维恢复尚未决定；不得把这些扩展纳入本次已确认范围。
