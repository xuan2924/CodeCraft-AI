# 任务 05 最小状态模型

验证 [任务/操作权/恢复协议](../../../docs/design/task-event-recovery.md) 中几个关键动作的先后顺序。用户已确认测试边界：操作权与写入、任务停止/恢复、快照/订阅/撤销。另覆盖已确认的准备失败与有限最终验收收尾规则。

这不是正式服务、存储实现、Protobuf 生成代码或浏览器替身。每个方法代表**假设已原子持久化**的一步；Checkpoint/Restore 只复制内存值，不进行磁盘 I/O。通过不能证明真实系统能做到这些原子步骤。

## 运行

使用仓库已验证的 Go 工具链，无第三方依赖：

```bash
cd backend/prototypes/task-protocol
gofmt -l .
go build ./...
go vet ./...
go test -v ./...
```

可单点运行 `go test -run TestTakeoverOrdersOldWritesAcrossRecovery ./...`。

## 边界与文件

- `workspace.go`：文件发布与 Close/Open/Freeze 在同一串行协调区的抽象；不实现文件内容、路径、无变化保存和 I/O。
- `transition.go`：Java 意图与 Go 回执分离，丢失 Close/Open 响应后恢复；省略真实授权、未决操作扫描和数据库并发。
- `admission.go`：区分手动测试与冻结后的系统最终验收准入，后者仅使用可信 Java 的冻结回执/尝试/预算授权，不重新开放写入；省略真实鉴权和尝试登记。
- `tasks.go`：同一演练的执行槽位、重复启动、取消墓碑、停止/结果排序及未知执行隔离；不运行容器，不验证杀进程。
- `events.go`、`connection.go`：有界序列、快照/订阅、重复/缺口检测、发送前及空闲授权复核。模型按条数限额；字节预算、UTF-8 切块及网络发送属于正式实现测试。
- `lifecycle.go`：准备失败重试/放弃、冻结回执之后的最终验收次数/墙钟预算。不是完整 FSM；`BeginFinalTesting` 注入已经确认的冻结回执，省略 READY/ACTIVE/CLOSING。

测试通过公开动作与快照断言结果。手动列举关键竞争顺序，不是穷举模型检查器；时钟与重启是显式模拟。完整结果与待验证项见 [验证记录](../../../docs/validation/task-event-recovery-05.md)。
