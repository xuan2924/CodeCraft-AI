# 02: Pi 风格精炼编码工具链（read/grep/find/edit/write）与 Prompt 减负

**What to build:** 提供与 `pi-coding-agent` 一致的流畅工具链（自动分页截断的 `read`、带上下文行与有界正则的 `grep`、通配符 `find`、基于 `oldText -> newText` 局部精确替换且免填修订号的原子 `edit` 与 `write`），精简说教式 System Prompt，并在前端 Copilot 面板清晰呈现新工具调用与提案状态。

**Blocked by:** 01: Copilot 工具目录内聚、两轴结果模型与规划基线预重构

**Status:** ready-for-agent

- [x] 实现 `read(path, start_line?, end_line?)`（并兼容 `view_file`）：单次最多返回 200 行 / 64 KiB，超出范围时正常返回前 200 行并附带续读提示 `[Showing lines X-Y of Z. Use start_line=... to continue]`，不再因请求行数超限直接报 `INVALID_ARGUMENTS`；成功读取后在下一次模型请求准备时更新规划基线。
- [x] 实现 `grep(pattern, path_prefix?, is_regex?, literal?, context_lines?, limit?)`（兼容 `grep_code`）与 `find(pattern)`（兼容 `glob_files`）：支持路径前缀过滤、带复杂度保护的正则表达式、默认附带匹配行上下 문上下文行，并在达到条数或字节上限时给出明确截断提示。
- [x] 实现 `edit(files: [{path, action: EDIT|CREATE|DELETE, edits?: [{oldText, newText}], content?}])` 与 `write(path, content)`（兼容 `propose_changes`）：模型无需手填 `expected_revision`，服务端按规划基线校验所有目标文件；`EDIT` 校验每个 `oldText` 在原文件中唯一且不重叠（支持 LF/CRLF 归一化并保留原文件换行风格），匹配失败或多重匹配时返回带文件行数的可诊断结果；验证通过后生成单个不可变提案、整批 Diff、`ASK/AUTO` 审批决定与 `FILE_BATCH` 原子提交。
- [x] 精简 `prompts/copilot/system.txt`，移除冗余说教与已过时的 `base-only` / 强制手动批准描述，对齐 `challenge` 阶段与 `AUTO` 审批的真实行为。
- [ ] 前端 Copilot 消息、工具卡片与提案面板适配 `read / grep / find / edit / write` 工具名称与摘要展示，通过前后端单元与构建验证。

## Comments

### 实施决定（2026-11-17，backend worker）

- `read` 与旧 `view_file` 超范围正常分页，200 行/64 KiB 且明确续读；`find` 沿用受控 glob；`grep` 新名称默认上下文 2 行，旧 `grep_code` 保持默认 0 行以兼容旧返回。路径前缀、匹配限额与结果字节/行限额均有边界。
- 不增加正则依赖：采用保守 Java regex 子集，禁止分组/交替/回溯引用，至多一个量词且含量词必须以 `^` 锚定；单行 regex 输入最多 64 KiB。超出子集返回 `INVALID_ARGUMENTS`，结果超限明确截断，避免无界回溯。
- `edit` 的匹配全部针对原始 LF 归一化文本，唯一匹配且区间不重叠；保留原 CRLF 风格。失败包含安全错误码、路径与行数，不中断任务；整批转换为既有不可变提案证据/ASK-AUTO/FILE_BATCH 通路，`write` 按存在性选择 CREATE/UPDATE。参数预算约束模型输入，展开后的完整文本由既有工作区 codec 与容量约束验证，不把局部编辑误限为 192 KiB 文件。
- System Prompt 已精简且说明 challenge 与 ASK/AUTO。前端由独立 owner 交付，不在本 worker 变更范围内。
- 定向测试覆盖分页、regex 保护、上下文/前缀、精确编辑 CRLF/重叠/歧义、现代工具目录、无 revision 的工具分派及持久化冲突/重读刷新；实际执行结果见以下收口。

### 后端 L1 收口（2026-11-17，pi-backend）

- 全量 Java 编译、Checkstyle 与 469 项单元/边界测试通过；Copilot 定向 PostgreSQL 持久化 7 项通过（具体边界及扩大回归超时记录见 01）。
- 独立审查发现正则复杂度校验可被换行绕过，已改为逐字符禁止分组/交替等构造；新增 LF/CR/CRLF/NEL/Unicode 行分隔符回归，确保读工作区前拒绝。精确编辑 CRLF 测试的 JSON 转义夹具一并修复。
- 后端工具、审批提案契约及 prompt 无已知剩余功能缺口。前端展示由 agy-frontend 独立交付，本记录不代替前端验收。
