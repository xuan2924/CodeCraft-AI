# 01：确定工程初始化与验证工具链

**Type:** decision
**Status:** ready-for-agent
**Blocked by:** None
**Spec:** [第一阶段规格](../spec.md)

## What to build

核对现有环境与官方依赖接口，提出兼容已确认技术路线的正式工程初始化方案，供后续最小真实链路实施。不把 PoC 依赖或目标目录当正式工程。

## Acceptance criteria

- [x] 清点 Java、Maven、Go、Node、Docker 与数据库所需环境，区分已可用和需人工准备项。
- [x] 确认兼容依赖版本、唯一前端包管理器及锁定方式，新增依赖按项目规则说明。
- [x] 明确 Maven 包装器、Protobuf 双端生成、迁移及服务启动方式。
- [x] 明确 Java/Go 测试与静态检查、前端类型检查/组件测试/端到端测试及构建命令方案。
- [x] 必要兼容性问题用最小验证证明；记录实际执行及未验证项，不以规划命令冒充已运行。
- [x] 需用户选择的事项取得确认，更新架构专题；不初始化无业务用途的通用框架。

## Comments

本任务交付初始化决定和验证证据；正式链路由任务 06 建立。

### 2026-11-06：初始化决定与阶段性验证

用户确认目录方案、npm、Maven Wrapper、Vitest/Vue Test Utils、Playwright，以及暂缓 MapStruct/虚拟线程；确认本机运行 Java/Go/Vite、Docker 运行 PostgreSQL/题目测试容器，不要求安装本机 PG。

- 方案：[工程工具链](../../../docs/design/engineering-toolchain.md)；已同步架构专题。
- 证据：[实际环境、命令与结果](../../../docs/validation/toolchain-01.md)。Java 生成/编译及 Flyway/JDBC 探针通过，Go 生成/编译、Docker SDK Ping 和 vet 通过，Vue 类型检查、组件测试、构建及 Chromium 烟测通过。探针在系统临时目录，未创建正式业务工程。
- **未完成**：前端依赖安全告警尚未关闭，不能冻结正式前端版本；Windows Go race 退出 `0xc0000139`，Linux race 未运行。唯一包管理器及锁定方式已确认，但依赖验收项仍未勾选；第一阶段工程初始化门槛不解除。
- 继续工作：核对 DOMPurify 与测试依赖链修复版本/覆盖策略并复验，再冻结前端基线；在 Linux 环境补充 race 证据。Monaco worker 等真实集成由后续功能验证，不能将烟测等同于任务 06 的完整链路。
- 标签仍表达分诊状态，不代表完成；本任务非 wayfinder 决策任务，不增加 claimed/resolved 状态。

### 2026-11-06：第二轮问题关闭，决定票验收完成

用户同意继续解决上述两项问题，详见验证记录的“第二轮”及工程工具链专题：

- 前端采用 Monaco 0.57.0，并精确覆盖 DOMPurify 3.4.17、glob 14.0.4、minimatch 11.0.5。干净 npm ci 后 audit 为 0、npm ls 无错误；类型检查、组件格式化、glob/EditorConfig 实际调用、构建及生产预览中的 Monaco worker/净化浏览器烟测通过。
- Go race 的失败在无依赖空测试中复现；仅将进程 CC 指向校验后的 LLVM-MinGW 20261104 UCRT 工具链即通过，原 Go 探针 race 也通过，故意竞争负对照仍可检出。没有关闭 race、修改全局 PATH 或替换系统编译器。
- 结尾重跑全部临时正常探针：Java 1、Go 2（普通与 race）、Vue 1、浏览器 2；另有最小 race 启动与故意竞争负对照。保留第一轮失败历史，不覆盖旧结论。
- 本决定票的六项验收已满足；工程初始化的选型问题不再阻塞任务 06。任务 06 仍受其其他前置任务约束，并须建立正式清单/锁文件/脚本；Linux race、真实服务链路和隔离验收仍未完成，不能据此宣布第一阶段交付。
