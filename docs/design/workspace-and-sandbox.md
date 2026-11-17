# 不可变工作区、Docker 沙箱与题包隔离

## 核心痛点

如果多个写入源（用户手动保存、AI 修改提案、开发 Bash 命令、代码历史回滚）和受控测试直接操作同一个磁盘目录，并发时必然出现文件撕裂、测试跑到一半代码被改、甚至用户通过软链接越权读宿主文件或提前偷看隐藏挑战测试。

## 关键设计决策

### 1. 不可变目录树与原子提交（`commit-*`）

Go 不在原目录就地修改文件，每次变更（创建/修改/删除/重命名）都在同一文件系统下完成：

```text
获取工作区串行锁 → 校验 gateEpoch 与预期修订(revision)
  → 在 stage-* 目录完整准备新文件树与 manifest
  → fsync 后原子 rename 为下一个 commit-* 目录（唯一可见提交点）
```

- **唯一版本号 `revision`**：仅当文件内容或结构真实改变时递增；相同内容保存不递增。任何写操作都会严格校验基线版本，不存在静默自动合并。
- **稳定测试副本**：运行受控测试时，Go 在协调锁下从确定的 `revision` 复制一份独立副本供容器使用，原工作区立刻释放给用户继续编辑，互不阻塞。

### 2. 路径、链接防御与开发/验收隔离

- **全面禁用链接**：题包与工作区禁止任何符号链接、硬链接和 Windows junction，每次读写在 Linux 下逐级校验真实路径与演练归属，禁止目录穿越。
- **可编辑范围锁定**：固定只允许修改题目声明的 Java 目录（`src/main/java`、`src/test/java`），并强制保护 `pom.xml` 和各级只读 `AGENTS.md` 指导文件。
- **双模式 Docker 执行**：
  - **权威受控测试（`run_tests` / 手动验收）**：完全禁网，只读挂载镜像内权威测试 `/opt/acceptance` 与离线 Maven 仓库，用户 `src/test/java` 不参与权威判定；带 CPU/内存/PID/tmpfs 与 30s 保护性超时。
  - **开发 Bash（`bash`）**：运行在独立可写容器副本中，**允许联网下载依赖**，执行后按可管理范围同步改动并推进 `revision`；每场演练同时只允许 1 个运行中测试或命令。

### 3. 双阶段题包隔离（Base vs Challenge）

- 为防止开发 `bash` 在基础阶段偷读镜像中的 `/opt/acceptance` 挑战测试，题包内 `challenge/` 拥有**独立的离线验证镜像**，两张镜像的 `initial/` 工程必须逐字节一致。
- 发布时通过 `scripts/publish-problem.mjs` 验证初始工程预期失败、参考解全通过以及禁网隔离，生成绑定本机镜像 ID 的 `.runtime/problem-publication.json`。

### 4. 跨端 512 MiB 统一容量预算（ADR-0006）

- Java 业务记录 + Go 磁盘文件/日志共享单场 **512 MiB** 预算：其中 **256 MiB** 固定预留给结束冻结与最多 3 次最终验收尝试（每次 56 MiB），普通开发最多使用其余 256 MiB。
- Java 在事务内先预留额度，待副作用完成后再以 Go 返回的带 `meterEpoch` 与单调序号快照结算，乱序快照不会错误拉低已占账本。
- 已结束演练默认保留 30 天，每用户最多保留 20 场历史；删除时先在 Java 撤销访问，再由后台按指数退避重试清理物理目录。

## 关键源码入口

- Java 题包与存储：`ProblemPublicationReader.java`、`WorkspaceWriteService.java`、`WorkspaceAiOperationService.java`、`WorkspaceHistoryService.java`、`StorageCapacityService.java`
- Go 磁盘与容器：`mutation_linux.go`、`testcopy_linux.go`、`freeze_linux.go`、`history_linux.go`、`capacity_linux.go`、`runner_linux.go`、`stop_linux.go`
- 发布脚本：`scripts/publish-problem.mjs`、`scripts/validate-problem.mjs`
