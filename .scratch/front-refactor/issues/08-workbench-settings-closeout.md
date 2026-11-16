# 08: 完整工作台与个人设置迁移、阶段收口

Status: ready-for-agent
Blocked by: 01、02、03、04、05、06、07
Spec: [统一规格](../spec.md)

## 目标

将全部已有 Copilot 能力与本阶段面试流程整合到统一工作台；完成个人模型/Skill 设置迁移，最终移除旧 UI，不以功能缩水换取截图效果。

## 验收要求

- [x] 工作台三栏与专注态共用业务组件，文件树/题目、编辑器/Diff、测试/命令/日志、Copilot/面试官有明确边界及可调整面板。
- [x] 保留显式保存、草稿恢复/冲突/退出处理、操作权接管、ASK/AUTO、审批失效、任务停止、steer、上下文和用量语义。
- [x] New/Fork、引用、项目指令、follow-up、BTW、子任务、联网资料、Bash 与修改历史均有真实可达入口，旧业务测试不因改 UI 被简单删除。
- [x] 个人模型连接/参数管理与演练内显式选择完整保留，不静默回退；Skill 设置及任务快照语义不变，不把个人水平标签当作 Skill。
- [x] 暂停、挑战、结束/回顾、报告与反馈的关键路径整合；高风险状态不可被折叠区遮蔽，停止任务与暂停演练明确区分。
- [x] 流式批处理、分页、有界日志、编辑器实例/观察者/订阅清理及切换作用域迟到响应保护不退化。
- [x] 所有用户和管理页面统一主题；清除 Naive UI、旧全局元素样式及无用重复依赖，核对 npm 锁文件与领域文档一致。
- [x] 受影响类型/Lint/单测及 build 通过；按共享边界集中选择完整 L1，不重复跑无变化检查，不默认增加真实模型或重型故障矩阵。
- [x] 最小浏览器检查参考图布局、长内容、缺失/零值、窄屏、键盘、禁用解释和主流程；记录实际结果与功能缺口。

## Comments

历史规划阶段未授权编码。00 产品规则已确认，信息阻塞解除，仍须等待列出的功能依赖完成；不代表已有 Copilot 功能未交付。最终验收集中在本票汇总，其他票仍各自承担功能与测试责任。

### 本次实施状态

用户已授权全目录实施，覆盖历史“暂不编码”限制。00 已确认；本次 01 的公共设计系统、Pinia 和应用壳已交付，验证及双轴审查见 01 Comments。02–04 已陆续交付（分别见对应票），05–07 尚未实现；本票完整工作台/设置迁移及整阶段收口也未完成，不能将基础展示夹具视为业务端到端完成。

Naive UI 仍保留旧账号、模型配置、Skill、工作区和 Copilot 业务控件；准确入口用 `rg -l "from 'naive-ui'" frontend/src` 核对。共享基础在 `src/components/ui/`；后续页面复用这些组件及集中路由，不删除旧业务测试。

04 边界验证发现旧 `WorkspaceIT.startsOneStableTestWithDurableResultsAndOwnership` 在开始计时时返回 `MODEL_SELECTION_REQUIRED`：该底座夹具未构造 P2 的个人模型选择/检查，不是平台绑定失败。04 使用创建/绑定用例验收受影响边界；05/08 接完整流程时更新这类测试前置条件，不能放宽产品模型准入或声称旧全套 WorkspaceIT 已通过。

### L1 / 全目录交付收口

- 工作台改为可调整的题目/文件、编辑/验证/历史、面试官/Copilot 三栏，窄屏纵排；专注模式只隐藏题目 pane，不卸载编辑器、不停表、不隐藏 AI 停止/审批。原保存、草稿/冲突/退出、操作权、ASK/AUTO、steer、New/Fork、引用、项目指令、follow-up、BTW、子任务、联网资料、Bash、测试、修改历史、用量和压缩组件保留；没有用静态聊天或虚构成绩替换业务。
- 个人模型/检查/用量、演练显式选择、Skill 与账号管理统一使用源码控件；移除 Naive UI 及其依赖（共减少 21 个包）、`legacyTheme.ts` 及旧包装全局样式，npm 锁文件同步。Monaco/Diff 与 xterm 使用统一 Token 的公开主题 API。少量组件保留原局部 `NButton` 标签别名，但 import 已直接指向 `components/ui/Button.vue`，不存在旧组件或兼容运行时依赖。
- 05–07 入口已完整整合：真实暂停/恢复与挑战、结束后的回顾/报告导航、私有 PDF、问答/报告授权反馈及管理员处理。统一导航按角色展示；保留范围、缺失/零值、错误/未知请求均如实表达。
- 自审修复：报告重试字段协议、取证/导出的版本与删除复核、迟到用量只记账不发布、前端四维边界校验、报告变更证据自动只读 Diff 的 watcher 调度、挑战测试按钮误标基础目标；视觉检查发现的面板高度溢出与 Monaco 白色默认主题均已修正并加浏览器断言。未发现剩余票内功能缺口。
- 集中验证：`CODECRAFT_QUICK_TIMEOUT_SECONDS=180 node scripts/verify.mjs --quick` **75.64s 通过**，覆盖 Java **441** 单测、前端当时 **326** 用例、类型/Lint、Go quick/vet/Linux vet 与 Node 契约（`closeout-quick.log`）。后续新增挑战标签和报告 Diff 定向用例并修复调度问题后，当前前端 **78 文件 / 328 用例全通过**，类型/Lint/build 通过（`closeout-frontend.log`、`closeout-history-editor.log`、`closeout-lint.log`、`closeout-build.log`）。不是默认 60 秒内通过。
- 报告/反馈 Java **31** 定向单测 + PostgreSQL **2** 集成用例通过，覆盖迁移、幂等、重生成上限、unknown 不重发、迟到回执、管理员处理、到期、计量与擦除（`report-feedback-it.log`，Failsafe 2 执行、0 跳过）。PDF 中文提取/渲染/分页、隐私投影与下载竞争单测通过，中文预览已检查。
- 已修正历史 `WorkspaceIT` 的个人模型准入前置：显式创建模型配置并使用注明的持久化探测夹具，不放宽产品规则。定向 `node scripts/test-tracer.mjs --workspace --boundary-only --workspace-test=startsOneStableTestWithDurableResultsAndOwnership` **1 项实际通过**（`closeout-workspace-boundary.log`）；REST、PostgreSQL、Linux Go 与受控测试是真实链路，模型检查是持久化夹具，不冒充真实供应商调用。仅运行对应最小用例，没有宣称全套 WorkspaceIT/故障矩阵通过。
- Chromium **22** 浏览器用例通过（`closeout-browser.log`）：宽/窄屏、导航/键盘、captcha、题库/历史、平台配置、面试官/挑战、报告与最小授权反馈、真实工作台业务组件/Monaco/专注状态；检查相关截图。业务 HTTP 使用显式 API 夹具，不能写成收费模型整场端到端验证。
- **00–08 已达到约定 L1 Dev Done**；01–04 既有完成证据不重复执行。无已知票内功能缺口，不额外生成未安排的 QA/生产可靠性欠账。未修改或纳入无关的 Copilot 阶段记录、watchdog 文件及本地验证产物。
