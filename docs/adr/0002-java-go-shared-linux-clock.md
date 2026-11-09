---
status: accepted
---

# Java 与 Go 开发及验收链路统一到同一 Linux 时钟域

用户确认：Java 后端与 Go 服务在同一 Linux VM/宿主时钟域运行，作为日常开发及真实链路验收的目标布局；Windows 可以继续运行编辑器、Vite 和浏览器。此决策替代原先“Windows 本机 Java + Docker Desktop Linux Go”的日常开发路线，不改变 Java/Go 职责、首期同机部署或题目容器隔离约束。

原因是固定绝对截止时间跨 Windows/VM 时钟传递时，已实际出现 Java 派发时过期 624 ms、Go 接收时仍余 119 ms，导致过期准备被发布。将两端移入同一 Linux VM 后，原用例 10 次通过，其中 8 次过期请求到达 Go 并被拒绝。详细证据与限制见 [时钟诊断与复验](../validation/workspace-write-browser-10-12.md)。

## 取舍与后续实施

- 接受 Java 启动、调试、网络配置与集成测试入口迁移的成本，换取截止判断所需的一致时钟前提；Windows 本地编译、静态检查和不涉及跨端截止的单元测试不因此禁用。
- 不再将 Windows JVM + Linux VM 混合模式作为截止时间敏感的业务开发或验收基线。不能通过增加 sleep、延长授权或反复重跑关闭该问题；若未来重新支持混合/跨时钟部署，须另行设计并验证授权时间协议。
- 同一 Linux 时钟域不代表系统时间永不跳变。原有“时钟回退或无法确认时拒绝新写入、不延长已有授权”的要求继续有效，检测机制与验证仍待实现。
- 本次确认的是运行边界，不指定正式 Java 镜像、容器网络或调试方案。启动/验收脚本迁移及完整链路回归尚未完成；诊断用的共享网络、Docker socket 和临时脚本不是正式部署方案，Java 应用与用户题目容器不得照搬诊断 socket 权限。

有效方案入口：[架构](../design/architecture.md)、[工程工具链](../design/engineering-toolchain.md)、[任务与恢复](../design/task-event-recovery.md)。
