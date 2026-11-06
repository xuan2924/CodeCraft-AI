# 任务 03：题目与执行边界验证

## 范围与环境

2026-11-06，Windows 开发机 → Docker Desktop 4.70.0 Linux/amd64 daemon 29.4.0，内核 `6.18.33.2-microsoft-standard-WSL2`，overlay2，16 CPU、7,268,364,288 字节内存；Node 24.9.0。这是**真实 Linux 容器**验证，不是正式部署宿主或 Go gRPC 集成验收。

用户已明确批准：全面禁止内部/外部符号链接；仅 Java 源码和练习测试可编辑，POM 和权威测试保护；购物车首题 45 分钟、无挑战；仅构建期下载依赖，运行期禁网，资源值根据实测提出。

## 入口与固定输入

```sh
node --check scripts/problem-contract.mjs
node --check scripts/problem-contract.test.mjs
node --check scripts/validate-problem.mjs
node --check scripts/validation-cleanup.test.mjs
node --test scripts/problem-contract.test.mjs
node --test scripts/*.test.mjs
node scripts/validate-problem.mjs
```

- 基础镜像：`maven:3.9.15-eclipse-temurin-21@sha256:d6e32a254897415a445654a3c43c30fbc731a4d946cf5c66d1cb9184141c20c1`。
- 实测执行镜像本机 ID：`sha256:fb8047322d885aa16d7e18deb45f4a5b8dde32598bad1c46e2406938a6cf6d0d`，不是 registry digest。
- 首题包摘要：`f79adeba44fd2c14c84e569764cf651d5d12f0435de835e8d9c113916e848cd3`。
- 自动输出：`problems/.validation/receipt.json` 和各阶段 `.log`，不入库。每次运行生成新证据；本文件保留一次已执行结果，耗时不承诺精确重复。
- `.mjs` 仅做 Node 语法检查，没有将其称作 TypeScript 类型检查。Java 21 源码与测试由容器 Maven 编译；正式前后端尚无可运行类型检查套件。

## 红绿与边界结果

1. 先写权威 JUnit 测试及初始 stub，在禁网/只读/非 root 配置下执行：8 项，6 失败，0 错误/跳过，退出 1。失败涵盖金额累计、负输入、null、两种溢出；空列表和零金额通过。
2. 添加参考解，保持同一权威测试和离线镜像：8 项全部通过，退出 0。不是仅检查 Maven 命令能启动。
3. 包验证测试先因入口缺失失败，再实现校验并通过；最终 7 项通过：合法包、越界可编辑路径、内部 junction/符号链接、硬链接、缺权威测试、无界超时、包内容摘要变化。
4. Docker 探针通过：UID 10001、零 effective capabilities、no-new-privileges=1、seccomp mode 2；网络只有 lo 且外连失败；根挂载只读，POM/权威测试/离线仓库写入失败；`/work`、`/tmp` 可写且超过 tmpfs 配额被拒绝。cgroup CPU/内存/swap/PID 配置与 manifest 一致。参考解不在镜像内。
5. 2 秒专用超时探针运行 `sleep 60 & wait`，由外部 watchdog kill，约 2.47 秒确认退出 137，OOMKilled=false；容器随后删除，无本次 label 残留。普通测试超时配置为 30 秒，不能混淆两者。

### 一次完整运行（07:59 UTC）

| 用例 | 墙钟 | cgroup 内存峰值 | PID 峰值 | `/work` 结束占用 | 收集日志 | 结果 |
|---|---:|---:|---:|---:|---:|---|
| 隔离/写满探针 | 0.730 s | 139,210,752 B | 6 | 0 KiB（已删填充文件） | 223 B | 通过 |
| 初始工程 | 6.551 s | 197,255,168 B | 38 | 52 KiB | 9,314 B | 预期 6 项失败 |
| 参考解 | 6.612 s | 191,565,824 B | 38 | 44 KiB | 2,962 B | 8 项通过 |
| 超时探针 | 2.472 s | 未采集 | 未采集 | 未采集 | 8 B（通道标记） | 平台终止，无 OOM |

内存为 cgroup `memory.peak`（包含 tmpfs 等），不是只看 JVM 堆；PID 为 `pids.peak`，包含线程。CPU 读取 `cpu.stat`，初始/参考分别有 60/61 个节流周期。磁盘是执行结束时 `du`，**不是峰值**；峰值上界由 tmpfs 大小约束。上表不证明 128 PID/768 MiB 下所有异常代码可正常运行。

据此建议首题 1 CPU、768 MiB、128 PID、`/work` 128 MiB、`/tmp` 64 MiB、`/dev/shm` 16 MiB、30 秒保护超时、合计日志 1 MiB、全局并发 1。内存和空间留余量；并发 1 是保守上线建议，不是吞吐测量。详细理由及结果归因见 [题目包契约](../design/problem-package.md)。

## 实测发现

- Docker `local` logging 使用 `max-file=1` 时不能保留默认压缩，否则容器启动失败；脚本显式 `compress=false`，失败那次也已清理容器。
- `/tmp noexec` 导致 Jansi 2.4.3 提示原生库无法映射；Maven 退化输出后仍完成编译及测试。保持 noexec，保存 stderr，不把此警告误归代码失败。
- 预热使用实际测试生命周期，运行时 `-o` + network none + 只读仓库均通过；不以联网构建成功冒充离线验证。

## 独立审查与回归修复

两路只读审查以 `7e9bd98..bda32d0` 为范围；首次因审查工具无法取得已提交 diff 而受阻，提供完整 diff 文件后重试完成。

### Standards

发现 1 项 P2：成功回执早于临时目录清理，清理失败时可能残留成功证据。已将回执写入及成功消息移至整个 try/finally 成功返回之后；新增 `validation-cleanup.test.mjs`，在真实 Docker 验证完成后显式注入文件系统清理失败，断言命令失败且 receipt 不存在，测试自身恢复文件系统函数并移除故障目录。该故障回归已实际通过。

### Spec

发现 1 项 P2：仅在结束后读取 daemon 轮转日志，不能证明原始输出没超过预算。已改为 `docker start --attach` 启动前建立有界收集，超限即失败，并独立核对/终止容器；增加输出 2 MiB 后再输出成功标记的真实 Docker 探针，确认超限仍被拒绝，最多保存 1 MiB 输出载荷。

修复后单独执行清理故障回归通过；初始/参考、隔离、超量拒绝、超时和容器清理均再次通过。最终完整套件为 7 项包校验 + 1 项真实 Docker 清理故障回归；成功发布验证另行顺序执行。旧测量表保留原时间与原收集格式，修复后 `logBytes` 不再计入 8 字节通道标记。

## 未验证项及后续阻塞

- 尚无正式 Java/Go/Vue 服务，未执行 Go gRPC、Java REST/SSE、浏览器 E2E 或原 PoC 的模型联网测试；本任务不声称第一阶段闭环完成。
- 未推送 registry，未在最终部署宿主重新拉取验证，也未验证其他架构、cgroup v1 或无默认 seccomp 的环境。**部署前必须复验；当前版本不得据此自动加入生产可选列表。**
- CPU quota、内存/PID 上限已检查，未故意制造 JVM OOM 或 fork bomb；不能声称所有资源异常分类已由真实服务验证。
- tmpfs 写满已验；正式工作区、镜像、日志持久化的宿主磁盘配额/保留策略仍阻塞任务 04/16 的容量验收。
- 服务级全局准入、日志精确截断事件、停止与完成竞争、重启对账及残留清扫仍阻塞任务 05/14；脚本 finally 清理不等于崩溃恢复。
- 不以本次包扫描替代用户工作区防路径竞态、跨演练隔离与稳定快照测试；这些属于任务 04/13。

## 官方接口依据

实现前核对并用于本次命令：

- [Docker run](https://docs.docker.com/reference/cli/docker/container/run/)：read-only、network、cap-drop、security-opt、PID/CPU/内存及 tmpfs 参数。
- [Docker resource constraints](https://docs.docker.com/engine/containers/run/#runtime-constraints-on-resources)：memory-swap 是内存+swap 总量，本次与内存设相同值。
- [Docker tmpfs](https://docs.docker.com/engine/storage/tmpfs/)：tmpfs 挂载选项、大小及内存性质。
- [Maven CLI](https://maven.apache.org/ref/3.9.9/maven-embedder/cli.html)：`-o` 离线、`-f` 指定受控 POM、`-B` 非交互。
- [Compiler compile goal](https://maven.apache.org/plugins/maven-compiler-plugin/compile-mojo.html)：Java release 编译配置。
