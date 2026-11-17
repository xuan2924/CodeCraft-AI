# 阶段 1：工程底座与跨端沙箱闭环

## 解决的核心问题

从零搭建“受邀账号 → 选题准备 → 编辑器保存 → 隔离运行 Java/Maven 测试 → 结束冻结与删除清理”的可靠工程底座，重点解决**跨服务文件一致性、并发防覆盖与执行沙箱安全**。

## 关键架构选择

1. **Java + Go 职责切分**：Java 管业务身份、授权、状态机和 PostgreSQL 持久化；Go 通过 gRPC + Docker SDK 管磁盘工作区与容器执行，**不共享业务库**。
2. **不可变目录 + 原子重命名（`commit-*`）**：Go 不在原工作区就地修改文件，而是在同文件系统 `stage-*` 目录准备好完整新树、写好 manifest 后执行原子 `rename`，以此作为唯一可见提交点；测试永远从确定修订复制出的**独立副本**运行，运行期间用户继续改代码互不干扰。
3. **单用户多标签页互斥（Page Control）**：通过内存持有 `X-Operation-Credential` 和显式“接管”机制防多窗口互相覆盖；同时在普通空间耗尽时保留不依赖页面凭证的安全停止/结束通道（[ADR-0007](../adr/0007-owner-recovery-stop-and-end.md)）。
4. **跨端统一 512 MiB 预算**：Java 作为统一预留账本，Go 返回带 `meterEpoch` 的有序占用快照，并预留 256 MiB 给最终收尾验收（[ADR-0006](../adr/0006-exercise-storage-reservations.md)）。

## 真实踩坑与演进

- **跨 OS 时钟漂移事故**：早期让 Windows 本机跑 Java JVM、Docker Desktop Linux VM 跑 Go 沙箱。实测出现 Java 派发时请求已过期 `624 ms`，Go 接收时却仍剩余 `119 ms`，导致过期准备被放行。最终拍板 Java 与 Go **必须在同一 Linux Docker daemon 时钟域运行**（[ADR-0002](../adr/0002-java-go-shared-linux-clock.md)），并明确接受单机系统校时风险（[ADR-0003](../adr/0003-accept-shared-system-clock-risk.md)）。
- **多个观察者覆盖终态**：停止请求、测试自然完成与 Go 宿主重启回调可能并发到达。引入持久化单调代次（`gateEpoch`、`hostEpoch`）和 Go 端串行协调区后，先落盘的停止/超时意图锁定终态，绝不被迟到结果反转。
- **业务预期失败污染 Spring 事务**：在 `Identity` 代理调用内抛出异常后再在外层 `catch`，导致共享事务已被 Spring 标为 `rollback-only`。修复方式是让认证/校验服务在代理返回前就形成确定性失败结果对象，避免跨代理异常污染外部事务。

## 关键代码入口

- 工作区读写与测试：`WorkspaceWriteService.java`、`TestTaskService.java`、`StorageCapacityService.java`
- Go 原子存储与执行：`mutation_linux.go`、`testcopy_linux.go`、`runner_linux.go`、`capacity_linux.go`
- 前端工作台与草稿：`WorkspacePage.vue`、`drafts.ts`
