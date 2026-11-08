# 06：建立最小真实服务链路

**Type:** implementation
**Status:** ready-for-agent
**Blocked by:** 01 工程初始化与验证工具链
**Spec:** [第一阶段规格](../spec.md)

## What to build

建立浏览器经 Java 查询数据库和 Go 服务状态的最小可运行纵向链路，并提供实际初始化、生成、测试与构建命令。不创建无用途的业务框架。

## Acceptance criteria

- [x] 正式依赖锁定、前端仅一种锁文件，配置示例不含凭证。
- [x] 数据库迁移可从空库运行；Protobuf 可生成 Java/Go 契约。
- [x] 浏览器通过 Java 看到真实依赖可用/不可用状态，不暴露敏感连接信息或内部堆栈。
- [x] Java、Go、前端实际测试/检查/构建可执行；服务异常能得到明确失败反馈。
- [x] 本地启动步骤可重复，Docker 或外部环境不可用明确记录，不伪报通过。
- [x] 页面仅用于内部底座，不伪造用户身份、模型或完整演练可用状态。

## Comments

所有实现任务的 needs-info 在依赖决策获批、契约完整后复核为 ready-for-agent；完成以验收证据为准。

### 2026-11-08：范围确认与实现验收

- 前置任务 01 已验收；用户确认本票仅提供本机免登录内部状态页，不扩展账号、AI 或完整演练。按此复核为 ready-for-agent；标签不是完成证明。
- 已建立正式 Java/Go/Vue 工程、唯一 Protobuf、数据库迁移、依赖锁定、生成/启动/验证入口和 Linux CI 配置。内部接口仅返回固定可用性字段，服务均 loopback，不复用任务 05 的业务接口。
- [运行步骤](../../../README.md)；[实际验证记录](../../../docs/validation/service-tracer-06.md)。正式本地检查通过；真实 Chromium→Java→PostgreSQL/Go 正常、停服、恢复和 Java 启动失败关闭路径通过。测试资源与开发数据库隔离并清理。
- Windows Go race、npm 干净安装/audit、类型/静态检查、单元/边界测试和生产构建已通过；Linux race、远程 CI、产品业务闭环与题目隔离未验收，不伪报通过。
- 两路独立审查：Standards 发现 2 项 P2（缺少依赖诊断、重复主色来源），已修正并重跑正式全套与真实链路；Spec 0 项。PowerShell/Git Bash 生成入口、生成漂移检查及四个开发启动入口也已实际验证。
