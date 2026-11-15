# P2-18：Skill 快照与按需加载

**Type:** implementation
**Status:** needs-info
**Blocked by:** P2-04 上下文与 AI 数据容量契约；P2-08 只读 Copilot 任务；P2-17 个人 Skill 管理
**Spec:** [第二阶段规格](../spec.md)

## What to build

新 Copilot 任务固定启用 Skill 列表与版本快照，提供用户显式选择及模型按需加载，展示真实加载记录，不把所有正文塞入每次请求。

## 开工门槛

第一阶段达到 Dev Done（功能、L1 与代码审查完成，不含远程 CI 或重型验证）；前置快照、受控加载、存储计量和版本契约完成。读 [Skill](../../../docs/design/skills.md)、[Loop](../../../docs/design/copilot-loop.md)。

## Acceptance criteria

- [ ] 任务启动与设置更新竞争有确定快照，只从本任务已启用版本提供名称/描述；显式选择和模型加载均不得绕过该范围。
- [ ] 正文/参考文件按需只读加载，路径只能在受控 Skill 包内，加载内容和实际记录可见，不能修改个人原件。
- [ ] steer、运行中/待审批、重启恢复保持原快照；编辑/启停/删除只影响下个任务，旧快照可继续读取。
- [ ] Skill 是非可信指导，不覆盖权限、审批、到期和资源限制；恶意指令不扩大工具能力，用户私有正文不默认共享给平台 Agent。
- [ ] 原件独立 5 MiB 配额，任务快照计入所属演练 512 MiB；复用相同版本仍正确计量与引用，删除一个引用不破坏其他任务。
- [ ] 已加载内容参与上下文与用量，按契约保留记录和压缩引用，不把名称曝光当正文已加载。
- [ ] 自动测试覆盖配置竞争、显式/自动加载、越界、未启用项、删除/停用及重启；真实模型能按需加载并使用文本指导。

## Comments

P2-21 收口演练删除与版本回收；本票必须先保证快照完整性、存储预留和引用不悬空。

### 实施决定（2026-11-15）

沿用用户持续推进授权：复用 #17 不可变版本及用户级锁，任务启动先计算新增归属快照存储预留，再原子保存任务与启用版本引用。已有 `ExerciseDataContributor` 接入共享预算/删除，不由 Copilot 直接修改 Skill 模块内部表。名称/描述清单在模型上下文准备阶段从本任务快照生成，不从个人当前配置读取，也不标为正文已加载。测试边界沿用 #05/#18 已约定的任务启动、真实持久化、受控加载和 UI，不为内部类分拆验收轮次。

显式选择采用启动请求可选 `skillIds` 集合，不引入斜杠命令；用户可选启用列表中的任意项。目录/显式主文档作为非可信指导放在历史之前，避免每轮工具结果后插入人为的新用户轮次。显式加载范围固定 SKILL.md 全文 offset 0，首次派发时间连同任务/不可变版本引用为记录；按需加载沿既有工具结果保存精确路径、UTF-8 切片与版本。查看留存原文为纯读取，不冒充模型加载。

### 当前实现与定向证据

- V37 选择/首次显式加载标记、`SkillSnapshots` 模块边界、任务启动容量预留及原子 pin、只读 `load_skill`、上下文投影/压缩可重读引用已接入。目录并不加载全包正文，显式内容也不能扩大权限。原件修改/删除与任务启动共用用户锁，幂等重放核对文字及原快照显式选择，不重新抓取配置。
- `SkillExerciseData` 通过既有 contributor 参加 512MiB 计量和删除；真实 PG 验证同演练同版本只计一次、跨演练各自计量、删除一场保留另一引用、最后引用消失后回收。原件删除不影响旧快照；快照预算不足时任务/引用一起回滚，原件保留。
- 新任务 UI 可显式选择；当前任务显示固定 UUID、显式加载状态及私有原文，自动加载的完整内容/路径/版本/切片由对话工具结果展示。只读接口遵守账号/任务/演练归属及删除生命周期，不启动付费工作。
- 定向 Java 单测/HTTP 与真实 PostgreSQL 通过：`SkillPersistenceIT`、`CopilotPersistenceIT#taskLoadsOnlyItsPinnedSkillAfterThePersonalOriginalIsDeleted+explicitSkillSelectionUsesTheTaskVersionAndRecordsLoadingOnlyOnDispatch+taskAdmissionAndPersonalEditsSerializeToOneWholeSkillVersion+skillSnapshotCapacityFailureRollsBackTheWholeTaskWithoutDeletingPersonalOriginals`。覆盖并发版本、禁用拒绝、重复任务不抓新配置、steer 不刷新、重建快照服务后数据仍在、显式/按需加载、UTF-8/路径/worker 边界、额度原子性和共享引用回收。
- 前端 Copilot/Skill 受影响测试、类型与修改文件 Lint 通过；额外覆盖显式 IDs 在 CSRF 等待期间不被表单修改、晚到私有原文在账号/任务切换后隐藏并取消。
- 真实 DeepSeek `deepseek-flash` / OpenAI Chat / OFF 显式执行 `realDeepSeekLoadsPinnedSkillGuidanceOnDemandAndUsesItsPrivateMarker`，硬上限 3 次，实际 2 次请求通过：先从仅名称/描述的目录选择 `load_skill`，再使用正文独有随机标记回答。数值 usage 为输入/输出/总量 `1837/82/1919`、`2058/9/2067`；不打印 Key 或请求正文，不因验证补做自动重试。此证据仅覆盖本组合，不冒充 #19/20。
- 日志 `%TEMP%/skill-load-red.log` / `skill-load-green.log`、`skill-live-deepseek.log`、`skill-snapshot-final-directed.log`、`skill-snapshot-final-ui.log`、`skill-snapshot-final-typecheck.log`、`skill-snapshot-final-lint.log`。完整阶段回归、双轴审查及 #21/#22 跨功能证据继续收口，不把上述局部证据当作第二阶段已完成。

### 双轴审查与阻塞修复

对 `e267977...367d073` 运行独立 Standards / Spec 审查。Standards 发现 P1：工具准入之后，`execution.current` 仅验证 worker 租约，未重新验证会话/演练期限；Spec 发现 P1：压缩收益/可容纳判断没有计入不可压缩的 Skill 指导，可能发起无法恢复继续能力的收费摘要。均先补红测复现，再修复：`load_skill` 复用 `CopilotEvidenceStore` 的所有者锁+新鲜 USER 授权+worker/任务/演练状态检查，读取后再在事务外解码；上下文实际请求与 `retainedCanFit` 共用相同 Skill 指导构建，超窗时 `safe=false,compaction=null`。

同时保留 JSON 异常原因链并只向外返回安全码。UTF-8 边界的少量重复为启发式建议，当前保留各工具独立限额与响应协议，不为几行边界检查新增公共载体。Spec 确认显式加载的固定主文档全范围、任务/版本引用与派发时间满足记录契约，无须再复制正文账本。定向 `CopilotContextPlannerTest,CopilotSkillToolsTest,CopilotEvidenceToolsTest` 及真实 PG 撤销/到期、按需旧版本、显式/steer 路径通过（`%TEMP%/skill-read-authority-red.log`、`skill-compaction-red.log`、`skill-review18-green.log`）。修复提交 `87fdc18` 后，后续独立 Standards / Spec 审查分别确认 P1/P2 授权/原因链及 P1 压缩估算问题已解决。
