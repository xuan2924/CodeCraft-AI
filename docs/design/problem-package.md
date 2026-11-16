# 题目包与受控执行契约 v1

状态：仓库题目包、本机发布验证、Java 发布持久化与 Linux/amd64 受控执行已交付。以下资源值属于首题，不是所有题目的统一默认值；当前发布绑定本机 Docker daemon，跨宿主发布与镜像复验仍是扩大部署范围时的门槛，不是尚未实现本机发布。

## 最小包与身份

当前包在 `problems/cart-total/1.1.0/`，购物车金额计算，Java 21/Maven，45 分钟，含一轮隔离发布的可选挑战；旧题包已从当前树移除。`manifest.json` 是 v1 格式；维护者入口 `scripts/problem-contract.mjs` 验证其约束。此版本只支持这个 Java 源码题型，不提供任意命令、可执行脚本或用户构建插件。

| 字段/路径 | 契约 |
|---|---|
| `schemaVersion` | 固定 1；不认识的版本拒绝 |
| `id` / `version` | kebab-case 标识 / 三段数字版本；同版本发布内容不可覆盖 |
| `title`、`durationSeconds` | 非空标题、正整数秒；首题 2700 |
| `requirements` | 固定 `README.md`，包含输入输出、异常、示例及评价规则 |
| `initial` | 固定 `initial/`，创建工作区只复制这里 |
| `reference` | 固定 `reference/`，发布用参考源码；不发给用户、不打入执行镜像 |
| `acceptance` | 固定 `acceptance/`，公开但受保护的权威源码 |
| `editableRoots` | 固定 `src/main/java`、`src/test/java`；按路径分段匹配，不按字符串前缀 |
| `symlinks` | 固定 `forbidden` |
| `targets` | 固定 `["base"]`，映射服务端模板，不把字段拼成命令 |
| `expectedInitial` / `expectedReference` | `tests/failures/errors/skipped` 非负整数；tests > 0，失败+错误+跳过不大于总数；参考解全通过且不跳过 |
| `runtime` | 平台及正整数 CPU、MiB、PID、秒、日志字节、全局并发配置；见下表 |
| `initial/pom.xml` | 固定 Java、JUnit 和 Maven 插件版本；只读；权威执行使用镜像内副本 |
| `Dockerfile` | 维护者审核的固定构建配方；基础镜像摘要锁定 |

包摘要算法：按名称排序递归读取普通文件，对每个文件依次 SHA-256 更新 UTF-8 的 `JSON.stringify([相对POSIX路径, 字节数])` 和原始内容；包括 manifest、题面、参考解、测试、POM、Dockerfile。目录名不单独计入。禁止链接、硬链接、特殊文件、非规范路径及超过 1 MiB 的单文件。此限制是首个文本题目包的发布门槛，不等于最终工作区总配额。

固定身份为 `(id, version, packageSha256, executionImageDigest, platform)`。本机验证 receipt 使用 Docker image ID（同 daemon 的不可变内容标识）；跨宿主发布必须补充推送后的仓库 `@sha256` 摘要并在目标宿主拉取复验，不能把 tag 或本机 image ID 冒充 registry digest。Java 发布记录保存该身份及验证证据；同 id/version 不同摘要拒绝发布。该业务持久化已由 `ProblemTransactionService` / `ProblemRepository` 实现；启动时从通过验证的发布产物导入，不覆盖冲突身份。

## 编辑与执行隔离

- 整个题目包和工作区禁止符号链接（含内部链接、Windows junction）、硬链接及特殊文件。所有文件操作、快照复制和发布导入逐级校验真实路径与归属，拒绝绝对路径、`..`、反斜线变体和跨演练访问。运行时防 TOCTOU 的文件实现由任务 04 验证；维护者包检查不是其替代品。
- `pom.xml`、权威测试和受控配置不在可编辑区域。只向用户展示权威测试只读内容；参考解完全不分发。
- `base` 编译稳定副本的 `src/main/java`，权威测试源码来自镜像 `/opt/acceptance`。用户 `src/test/java` 是练习区域，不参与权威判定，不能通过同名测试、删断言或改 POM 跳过验收。
- 平台固定 Maven 参数：`-o -B -ntp -f /opt/contract/pom.xml -Dmaven.repo.local=/opt/maven-repository -Dcandidate.sources=/work/src/main/java -Dacceptance.sources=/opt/acceptance -Dbuild.output=/work/target test`。不加载用户 `.mvn/`、settings、profile、插件或 CLI 参数；实际工作目录 `/work` 仅复制 `src/`，不复制工作区 POM。
- 测试输出和报告在独立 `/work/target`，不回写正式工作区。Go 必须从确定修订复制输入、记录题目身份和工作区版本；快照原子性属于任务 04。
- 权威文件只读保护不等于恶意代码防作弊：被测代码和 JUnit 共享 JVM，可退出进程或伪造测试产物。首期仅可信受邀用户，退出码和完整报告仍须交叉校验，不能声称抵抗任意恶意代码的成绩证明。扩大用户范围前另行设计执行/判题信任边界。

## 离线镜像与发布门槛

1. 检查元数据及所有包文件；审核题面与测试一致。POM 锁定 JUnit 5.12.2、compiler 3.14.1、resources 3.3.1、Surefire 3.5.3，Maven 3.9.15 / Temurin 21 镜像按摘要固定。
2. 只在维护者构建阶段允许下载 Maven 插件和依赖。运行实际 `test` 目标以预装 Surefire provider，不假设 `dependency:go-offline` 足够。构建时允许初始代码断言失败仅用于预热；**构建成功不是发布成功**。
3. 在同一不可变 image ID 下创建全新禁网容器，初始工程必须编译成功且报告符合预期；参考解测试数非零、无失败/错误/跳过。发布验证命令任一步失败均不产生成功 receipt。缺依赖、报告缺失或权限失败阻断发布，不临时联网。
4. 同批验证隔离、可写目录限额、保护性超时及清理。receipt 保存包摘要、镜像身份、平台、配置、实际环境、测试摘要及测量；题目首次发布和任何包/镜像变更均重跑。
5. 目标部署宿主复验通过后，才可由 Java 将固定版本加入可选列表。当前脚本不写业务数据库，没有实现 registry 发布或生产发布 API。

仓库命令：`node --test scripts/problem-contract.test.mjs`（快速包验证）；`node --test scripts/*.test.mjs`（包含真实 Docker 清理故障回归）；`node scripts/validate-problem.mjs`（生成成功回执）。这些发布命令顺序执行，不并行写同一个证据目录。仅需 Node 24 和 Docker；验证容器输入挂载的是维护者包的临时副本，不是用户活动工作区。默认 daemon seccomp 必须启用（探针检查 mode 2），不支持 unconfined 环境。

## 首题资源建议与边界

依据 [实际验证记录](../validation/problem-execution-03.md)，本机按下面的配置顺序运行。不是容量压测或多题共用标准；发布到新宿主必须复测，配置改变重新验证。

| 参数 | 首题试用建议 | 依据/含义 |
|---|---|---|
| CPU | 1 核 quota | 单核受限情况下参考解约 6–7 秒，cgroup 已出现节流 |
| 内存 / swap | 768 MiB / 禁止额外 swap | 实测约 200 MiB 内存峰值，留编译、JVM、tmpfs 和异常输入余量；Maven 堆 256 MiB，不代表整个容器内存 |
| PID | 128 | 实测 38 左右，包含 JVM 线程；限制写入 cgroup |
| 可写目录 | `/work` 128 MiB、`/tmp` 64 MiB、`/dev/shm` 16 MiB | tmpfs、nosuid/nodev；前两者 noexec，根只读；工作/临时目录写满拒绝已实测。tmpfs 消耗内存，不是免费的磁盘 |
| 单次保护超时 | 30 秒 | 约为当前正常运行的 4 倍以上；不是算法性能标准 |
| 日志 | stdout/stderr 合计保留 1 MiB | 当前输出远低于此值；正式 Go 服务采用任务 05/16 的尾部保留，达限后继续消费、淘汰最旧正文，持久化 `truncated=true` 与已丢弃字节数，不让管道阻塞测试 |
| 全局并发 | 1，不排队 | 这是保守准入建议，非已验证吞吐上限；同演练最多一个任务。16 CPU / 约 6.8 GiB Docker VM 不代表可以开 16 个测试 |

验证脚本用 Docker `local` 日志驱动 `max-size=1048576,max-file=1,compress=false` 限制 daemon 日志；其轮转是近似文件上限，不能代替业务精确字节预算和显式截断事件。维护者验证用 `docker start --attach` 在启动前建立输出收集，不从轮转日志推断总输出；stdout/stderr 超过合计收集上限即失败，并确认容器已终止。保存的输出载荷最多 1 MiB（额外通道标记不计入载荷），超量时观测字节数只是已收集数量，不虚构完整丢弃字节数。故意超量探针必须被拒绝，不将残缺日志当成功。成功回执只在全部容器及临时目录清理完成后写入。镜像、只读输入副本、正式工作区与持久化日志仍有实际磁盘占用，不受 tmpfs 大小约束。任务 16 已确认 [单场容量与保留政策](workspace-execution.md#保留与删除)，本机第一阶段不新增固定全局磁盘预算/水位策略；单场计量、收尾预留及磁盘不足安全行为仍须验收，不能将 tmpfs 限制当作持久化配额。

## 结果与保护性超时

| 分类 | 判定与归因 |
|---|---|
| 通过 | 编译及受控目标成功，完整报告包含所需测试且全部通过，无跳过；关联固定题目及工作区版本 |
| 测试失败 | 用户源码编译错误、已运行测试断言/异常失败；保存编译或测试原因，不把所有 Maven 非零码都归到此类 |
| 执行超时 | Go 的平台墙钟保护期限到期并确认执行终止；标记 `platform_deadline`，不直接推断算法性能不合格 |
| 已停止 | 授权停止请求实际终止执行；不是浏览器断开或 RPC 取消即成功。与完成竞争由任务 05 排序 |
| 环境错误 | 缺离线依赖、镜像/挂载/daemon/权限故障、缺损报告、无法确认完成、非正常进程退出或资源耗尽；保存具体原因并与代码失败分开 |

OOM、PID/磁盘上限导致的异常如没有确定测试失败报告，归环境错误并标 `resource_limit`（能识别时细分），不自动扣代码分。退出 137 本身不能证明 OOM；同时记录 Docker `OOMKilled`、平台 kill 原因及时间。正常断言失败退出 1 也不能单凭退出码判定。停止/超时后必须确认容器不运行，再保存最终结果、删除容器与临时资源；清理失败单独记录并重试，不篡改测试结果。服务重启发现/对账及最终事件协议属于任务 05/14，未由本脚本实现。
