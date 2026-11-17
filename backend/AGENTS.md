# 后端通用编码规范

## 架构与数据边界

- **单向依赖**：业务按模块聚集在 `ai.codecraft.modules`；跨模块调用走公开 Service，不直接读写别家内部表或 Repository。`common` 与 `infrastructure` 不反向依赖 `modules`。
- **逻辑外键与事务**：不新增数据库物理外键与级联删除；关联校验、归属检查与删除顺序由 Service 在事务内保证，数据库保留主键、唯一、非空与检查约束。已发布 Flyway 迁移不可篡改。
- **契约唯一来源**：Protobuf 是跨端生成代码的唯一来源，删除字段保留编号不复用；应用配置集中绑定到 `@ConfigurationProperties`，不在业务代码散落读取环境变量。
- **数据安全与幂等**：SQL 参数化，动态排序走白名单，集合批量读写防 N+1；外部调用透传超时与取消，流/队列有界；副作用结果未知时先对账，不盲目重放。

## Java 规范

- **分层**：Controller 管路由、入参校验与响应映射；Service 管业务编排、授权与短事务；MyBatis Mapper 直接作 Repository，不包一层纯透传类。默认写具体类，仅在需要多实现或隔离契约时抽接口。
- **数据载体**：请求/响应 `XxxRequest` / `XxxResponse`，内部传值 `XxxDTO`，持久化 `XxxEntity`，不可变对象优先 `record`；携带密钥的类型必须在 `toString()` 脱敏。
- **事务边界（核心红线）**：`@Transactional` 只标在 Service `public` 方法上（注意 Spring 代理与同类自调用失效问题）。**事务内禁止调用大模型、外部 HTTP/gRPC 或做大文件耗时解析**；需独立短事务时由专门的 `*Store` 承担。
- **并发与异常**：业务异常用 `BusinessException` + 模块错误码；线程池显式限制队列与拒绝策略；资源用 try-with-resources，日志用 SLF4J 参数化且不打印凭证/Cookie/原始敏感 SQL。涉及撤销、墓碑与恢复协议见 [任务恢复与实时事件](../docs/design/task-recovery-and-realtime.md)。

## Go 规范

- **简洁与接口**：按使用方需要定义小接口，不预建多余接口；导出 API 写明用途注释。
- **Context 与错误**：阻塞/IO 调用首参传 `ctx context.Context` 沿链透传；error 用 `fmt.Errorf("...: %w", err)` 包装并用 `errors.Is/As` 判定，不在普通流程吞掉 `Close`/刷新错误。
- **并发与资源**：Goroutine 明确退出路径与所有者，channel 由写入关闭方负责关闭；共享可变状态加锁且带锁值不复制；循环内资源及时释放（`defer` 放单次操作作用域）。
