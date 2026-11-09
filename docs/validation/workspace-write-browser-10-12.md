# #10–#12：控制权、保存与浏览器草稿切片

本记录接续 `workspace-mutation-10-11.md`，基线 `dccf112`。仅记录本次可验证改动；**不是 #10–#16 整体完成或生产上线许可**。

## 实现范围

- V8 持久化控制行、转换意图和文件批次；事务不跨 RPC。Java 校验身份/归属，凭证只存摘要，页面仅持有内存秘密。
- CloseGate → 查询旧批次 → OpenGate 排序；身份撤销捕获固定 fence ticket。最多自动派发一次，未知结果查原回执；NOT_FOUND 后先关闭旧代次再判未提交。
- REST 文件批次、控制权与操作查询；开始要求操作凭证。READY 放弃先关闭 gate；Go 允许关闭后、没有写入证据的 READY 取消，不删除开放 gate 或写入证据。
- Monaco 文本编辑、显式保存、原子创建/重命名/删除、双栏冲突与基线查看。保存快照不覆盖后续输入；删除/重命名未决时锁住相关编辑及草稿恢复。
- IndexedDB 草稿按账号/演练/路径/页面标识隔离，防抖写入，明确备份失败。显式退出前选择返回工作区保存、丢弃全部账号草稿或取消；过期不清理。退出事务同时清除草稿并推进账号 epoch，旧页面在重新登录后也不能重新写回已清除的草稿。
- 查询失败保留已有编辑器与草稿；操作权查询失败明确提示并暂禁写入。路由保存后再次检查新增输入，未解决则不离开。

## 实际验证

环境：Windows 宿主、Docker Desktop Linux/amd64、Java 21、Go 1.26.5。没有将 Docker 内运行存储测试计作“题目测试执行器”。

| 执行项 | 结果与范围 |
|---|---|
| 前端 `typecheck` / `lint` / `test:unit` / `build` | 通过；6 文件、35 测试。Monaco 异步块约 2.7 MB，仍有构建体积警告 |
| Maven `test` | 18 测试通过，Checkstyle 通过 |
| `node scripts/test-workspace.mjs` | 29 个 Go 顶层测试通过；含真实 gRPC、响应丢失进程故障与新增 READY gate 取消约束 |
| Windows `go test ./...`、Linux 目标 `go vet ./...` | 通过；Windows 不含 Linux 存储测试 |
| `node scripts/test-tracer.mjs --workspace` | 最终串行重跑通过：14 项真实 REST/PostgreSQL/Go 测试、浏览器保存/双页面/冲突/草稿/文件操作/退出确认，以及服务重启后内容与修订保持 |
| `node scripts/test-tracer.mjs --identity` | 最终串行重跑通过：13 项身份集成测试、真实浏览器身份流程与服务重启 |

本地日志（未跟踪）：`.runtime/write-final-e2e-retry.log`、`write-final-identity-retry.log`、`write-review-go.log`、`write-review-java.log`。

### 回归与评审

- 删除未决时编辑器仍可输入：红灯 `readOnly=false`；加入相关路径锁后通过。
- 手工确认保存后旧轮询仍触发：红灯查询两次；撤销旧定时器并校验请求身份后通过。
- 后台读取失败卸载编辑器：修正测试异步组件替身后，确认红灯为查询失败后的 textarea 消失；保留缓存编辑器后通过。
- 保存后离开丢失在途输入、丢弃恢复副本误删另一页面草稿：分别先复现，增加脏状态复查及仅本页丢弃清理后通过。
- 退出前未确认、控制权查询失败无反馈：分别先复现，再加入确认流程及错误/重试入口；组件测试通过，退出确认及账号 epoch 栅栏另有真实浏览器验证。
- 已执行独立 Standards / Spec 双轴评审。修复活跃编辑器错误反馈、受控时钟、上述草稿丢失及退出流程问题；控制动作字符串的 enum 建议为非阻断可维护性建议，未扩展重构。

### 必须保留的失败记录

最后收口时并行启动 identity/workspace 两个 tracer，曾出现：

1. IdentityIT 清理夹具未包含 V8 新外键表，13 项报 SQL 错误。已补齐两个显式 TRUNCATE 清单；重新编译并串行执行身份全链路通过。
2. `expiredPreparationAuthorityCannotCreateFilesAfterSandboxRecovery` 一次预期 PREPARATION_FAILED、实际 READY。未修改该测试或产品逻辑；随后串行 boundary-only 和完整 workspace 均通过。**根因尚未定位，不能宣称偶发失败已修复**。该测试涉及 Java/Go 实时时钟及容器暂停，宿主负载/时钟假设尚待验证；亦未排除实现竞争。后续不得仅靠增加 sleep 或放宽断言消除信号。

这两套脚本还共享 Maven target/failsafe 与前端报告目录，后续收口按串行执行；本次没有证明并行是第 2 项的根因。

## 尚未完成的门槛

- 精确注入“Go 写入已提交、Java 记录回执前被杀”的 Java 进程恢复用例仍缺失。已有 Go 响应丢失与整服务重启验证不能替代这个切片。
- 上述准备授权过期偶发结果须定位；可控时钟边界、时钟回退与目标部署卷/断电不在本次通过范围。
- #13–#16 未实现：受限题目测试容器、流式日志/停止恢复、冻结与最终验收、删除/保留/跨存储配额。
- 512 MiB 计量、引用回收、收尾预留、真实 ENOSPC 仍未交付；现有全树复制/累计回执不具备长期生产容量保证。
- 前端复杂草稿逻辑仍需后续文件生命周期/删除联动组合验收；单场/账号删除不能由普通 404 推导。未运行 race detector。
