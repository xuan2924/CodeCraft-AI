# P2-22：第二阶段整体验收

**Type:** implementation
**Status:** needs-info
**Blocked by:** P2-06；P2-07；P2-08；P2-09；P2-10；P2-11；P2-12；P2-13；P2-14；P2-15；P2-16；P2-17；P2-18；P2-19；P2-20；P2-21
**Spec:** [第二阶段规格](../spec.md)

## What to build

汇总第二阶段真实模型闭环、确定性回归、安全与故障、容量删除及供应商矩阵，形成可复验的交付证据，不将任一单条演示当作整个阶段完成。

## 开工门槛

第一阶段达到 Dev Done（功能、L1 与代码审查完成，不含远程 CI 或重型验证）；P2-06～21 全部完成各自验收，上游决定结论与实际实现一致。缺口留在所属票，不通过改标签或忽略失败解除门槛。

## Acceptance criteria

- [ ] 第二阶段规格的全部验收项有证据映射，用户已确认行为没有被实现便利性暗改；未实现第三阶段能力在入口明确说明。
- [ ] 真实浏览器、Java、PostgreSQL、Go、Docker 和 DeepSeek 完成配置/准备/读取/提案/批准/应用/测试/回复链路，非思考和思考模式均有真实验证。
- [ ] 三种协议、四家预设及自定义连接按声明矩阵验证；缺失凭证、未验证组合或 SDK 缺口显式报告，不假装全面通过。
- [ ] 默认审批、auto 权限边界、拒绝后继续、串行多工具、停止所属测试、槽位忙、steer、重启/接管/撤销/到期组合回归通过。
- [ ] 自动/手动压缩、原始证据保留、真实用量/估算费用、Key 更新及未知价格反馈通过。
- [ ] Skill 50 个/文本限额、快照与下一任务生效、按需加载、私有隔离及路径/提示注入测试通过。
- [ ] 512 MiB 单场预算、调用前预留、磁盘不足、30 天保留、删除重试及个人设置独立通过，未重新引入全局预算或备份建设。
- [ ] 付费测试显式触发、秘密脱敏、确定性测试与真实模型测试分离；报告实际命令、通过/失败/跳过和未验证环境。
- [ ] 缺陷归属原功能修复并补回归，形成阶段验证报告和运行入口说明；真实闭环未跑通不得宣布阶段交付。

## Comments

本票不是首次集成，也不是只检查前置票勾选。必须复核跨功能行为和真实证据，未验证项不计为通过；本阶段不提供面试官、评测和平台 Agent 后台。

### 阶段收口记录（2026-11-15）

**结论：DeepSeek 与本轮无阻塞功能已完成实测，第二阶段整体仍不能宣布完成。** #20 的 OpenAI/GLM/Kimi 凭证与供应商组合实测缺失，且部分 OpenAI Responses 模型存在原生 SDK phase 缺口。四家预设不是协议笛卡尔积；可以在原技术路线下选择经官方支持、随后实测的组合，但不能把缺少凭证或 SDK 字段损失写成已通过。此票前置门槛仍保留，不用勾选/分诊状态掩盖缺口。

#### 规格—证据映射

| 规格范围 | 当前结果与证据 |
|---|---|
| 个人连接、Key、版本切换、准备检查 | #06/#07/#16 的 HTTP/真实 PG 证据；本轮 OFF/ON 浏览器均通过真实两类检查后才开始计时；未加入生产环境 Key 回退 |
| 默认审批、auto、原子修改、真实测试 | 本轮两种模式完整浏览器链路：读取→提案/Diff→断线恢复原提案→接管并批准→确认修订→Go/Docker base 测试→模型回复；再执行 auto、模式审计、跨浏览器草稿隔离与 GET-only 重连 |
| 串行工具、拒绝/停止、steer、所属测试与槽位、恢复/竞争 | #10/#12–14 既有证据及完整 `CopilotPersistenceIT`；#19 真实拒绝/auto、#20 三协议停止/steer 与原始 ID/opaque 字段控制夹具；未把服务对象重建当作真实进程强杀 |
| 压缩、原始证据、实际用量与费用 | #15/#16/#19；本轮实际 ON 摘要→工具→回复通过，保留原消息；输入占用/累计/费用分列，缺失与自定义价格不补零；#18 修复不可压缩 Skill 超窗时无效收费摘要 |
| Skill 管理、限额、快照、按需加载、隔离 | #17/#18：严格解析/并发/配额/owner HTTP、组件与真实管理浏览器；DeepSeek 按需读并使用随机标记；读取再次授权与固定快照、最后引用回收的真实 PG 回归通过 |
| 统一容量、删除和个人设置独立 | #21 与第一阶段 #16；本轮完整 Copilot/Skill/workspace retention PG 合计 106 通过、7 个收费用例跳过；可控时钟覆盖 30 天/24 小时/7 天，未决证据先收敛，不新增全局预算或备份 |
| 三协议、四家预设、自定义 | [矩阵](../../../docs/research/model-provider-matrix.md)：DeepSeek Chat OFF/ON、Responses OFF、Anthropic OFF 有真实证据；其余供应商缺凭证，现代 OpenAI Responses phase 组合明确拒绝。**该整体目标未完成** |
| 阶段性入口与秘密保护 | 工作区与账号均明确无面试官、挑战、AI 报告；真实 Key 仅在可信 Java 环境，付费浏览器关闭 trace/video/screenshot，累计请求硬闸与产物扫描；非收费入口不借真实 Key 通过 |

#### 本轮真实浏览器结果

- `node scripts/test-tracer.mjs --workspace --smoke`：**2 通过**。旧 409 入口已修复：经真实个人配置/检查/选择 API 满足前置条件，未直接伪造数据库检查成功。仅隔离测试进程加载 `WorkspaceBrowserModelFixture`，只接受固定 synthetic target 的内置检查，单测验证不创建供应商客户端；普通镜像不包含该类。首次新版运行因只读 test-support 挂载检查仍只允许 paid 分支而失败，补齐精确分支条件后通过，未放松其他容器边界。
- `node scripts/test-tracer.mjs --workspace --copilot-browser --copilot-thinking=OFF`：**1 通过，38.8 秒**；实际 10 请求（3 检查+7 Copilot），硬上限 12。7 轮落库 OFF，0 轮 reasoning。目录 `.runtime/cc-tracer-17448-13b041b4`。
- `node scripts/test-tracer.mjs --workspace --copilot-browser --copilot-thinking=ON`：**1 通过，42.7 秒**；`deepseek-flash`、ON/low、输出上限 4096，实际 10 请求，硬上限 12。7 轮落库 ON，4 轮保留真实 reasoning。目录 `.runtime/cc-tracer-13536-66f16791`。
- 以上测试都在真实浏览器/Java/PostgreSQL/Go/Docker 上运行。题目包含预设错误，因此结构化测试 **FAILED 是被正确回填的业务结果，不是验证失败**；没有修复题目错误或伪造 PASSED。每次 auto 确认修订 2，无额外测试派发；本次管理的执行容器和临时卷由 tracer 清理。

#### L1 与失败修复

- 最终 `node scripts/verify.mjs --quick`：**通过，38.54 秒**（30 秒软目标提示，60 秒硬时限内）；Java **317**、前端 **200 / 39 文件** 全通过，Node 契约、Go 行为/vet、Vue 类型/Lint 全通过。Lint 有原有 **36 warning、0 error**。
- 首次 quick 的 3 项账号测试失败归属 #17：新增 Skill 路由未进入旧测试路由夹具；补齐并加入口断言，账号 12 项通过，同时修正过期的“Copilot 尚未接入”文案。没有忽略失败或用重跑替代修复。
- `npm --prefix frontend run build` 在最后文案修改后再次通过。新测试夹具单测、修改 E2E 文件 Lint、`node --check scripts/test-tracer.mjs` 通过。
- #21 首次完整集成命令超过 180 秒，未记通过；按合理整套时限重跑得到 106 pass / 7 skip / 0 fail，未改业务逻辑。超时遗留的本次独占测试数据库已按日志精确名称删除，没有清理其他环境。
- 主要日志：`%TEMP%/stage22-workspace-browser-repeat.log`、`stage22-live-browser-off.log`、`stage22-live-browser-on.log`、`stage22-quick.log`、`stage22-account-regression.log`、`stage22-quick-repeat.log`、`stage22-frontend-final-build.log`；各功能定向证据在原票。此前已通过且代码/依赖未受影响的证据复用，不重复全部付费或重型故障回归。

#### 剩余事项与运行边界

- #17/#18/#19 已完成独立双轴审查；#20 的 `d3be51c` 审查通过但交付缺口仍在。最后对固定范围 `d3be51c...1f1d05f` 的独立 Standards / Spec 审查均未发现问题，覆盖 #21 与本节新增入口、#17 账号回归修复。Standards：OK；Spec：OK with notes，明确不证明阶段整体完成。审查运行 `725a7db6-9246-4c94-8b6a-907d6634fd45`，子报告 `1b443d6a-b7f2-4f04-b94d-3892cec3cf69`、`a42020fa-7e1c-40f9-881a-08ea935234b3`；审查没有修改代码、重跑测试或新增收费调用。
- 需要提供 OpenAI、GLM、Kimi 的明确目标端点/模型与测试凭证，才能完成其支持声明；本轮仅检查常见变量存在性，均无凭证，未展示值。真正 Anthropic 服务亦未实测，不借 DeepSeek Anthropic 格式成功替代。
- 若必须支持当前被拒绝的 OpenAI phase-aware Responses 组合，需要原生 SDK 补齐并验证，或另行确认另一正式 SDK 路线；不私自写 wire 适配或切换。保留这项组合缺口，不推定必须支持全部笛卡尔积。
- 普通验证：`node scripts/verify.mjs --quick`、`node scripts/test-tracer.mjs --workspace --smoke`、`node scripts/test-tracer.mjs --workspace --smoke --skills-browser`。真实调用仅在明确 paid 选择及环境 Key 下运行，上述请求次数不是固定金额上限。没有新增远程 CI、重型 QA、生产 SLA 或备份欠账。
