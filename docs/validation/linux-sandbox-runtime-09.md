# 任务 09 前置：Linux Go 运行环境

## 范围

用户确认将 Go 工作区服务从 Windows 本机运行调整为本机 Linux 容器，Java/Vite 保持本机运行。此改动只交付运行环境，不代表任务 09 的题目发布、工作区接口或选题页面已完成。

- 宿主已锁定的 Go 工具链交叉编译静态 Linux/amd64 二进制；临时 Docker 构建上下文只包含二进制、Dockerfile 和空存储目录，构建后清理，不上传仓库或 `.env`。
- Compose 使用本地内容派生标签，禁止缺失时远程拉取。Go 以 UID/GID 10001 运行，只读根、drop ALL、no-new-privileges，端口只发布到宿主 loopback；私有具名卷是唯一挂载，不传数据库或管理员凭证。
- Linux 启动时检查并独占卷内锁文件，拒绝符号链接、硬链接和特殊文件；第二个存储所有者在监听前失败。锁由内核在进程结束时释放。
- 原生 Go 入口只保留状态查询兼容性；非 Linux `--container` 模式失败关闭。尚未提供 Docker SDK 或题目执行能力，未挂载 Docker socket。

## 实际验证

Windows 宿主 + Docker Desktop Linux daemon：

| 检查 | 结果 |
|---|---|
| Linux 交叉 `go vet ./...` | 通过，包含 Linux 专属代码 |
| `node scripts/verify.mjs`（已验证 LLVM-MinGW CC） | 8 项脚本测试、17 项 Java 测试、Go vet/test/race/build、15 项前端测试、lint/类型/构建/audit 通过；14 项外部集成按条件跳过 |
| `node scripts/test-tracer.mjs` | 5 次浏览器状态检查、真实状态集成、Java 启动失败关闭通过；Go 容器删除重建后卷内标记仍在，重复存储所有者被拒绝 |
| `node scripts/test-tracer.mjs --identity` | 13 项真实身份边界、1 项状态集成、浏览器闭环、Java 重启保留会话通过 |

容器检查同时断言 UID、只读根、capabilities、no-new-privileges、唯一私有卷、loopback 发布和凭证环境变量排除。测试仅清理随机 Compose 项目的资源。

## 双路审查

基点 `2ba4bc8`，审查当前暂存的新文件及改动：

- **Standards**：未发现规范违反或需要处理的代码异味。
- **Spec**：未发现运行环境前置改动的问题；明确指出任务 09 的业务验收仍未完成。

审查员未运行测试；上表由主代理实际执行。运行环境采用配置与真实链路回归，没有把这一步描述为工作区业务 TDD。

## 未验证

未验证工作区修订/文件原子提交、崩溃恢复、实际题目执行、Linux race、远程 CI、生产部署及容量/保留。卷内标记保留只证明容器重建没有丢失该卷，不证明工作区事务、断电恢复或备份。
