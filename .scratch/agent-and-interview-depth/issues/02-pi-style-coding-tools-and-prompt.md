# 02: Pi 风格精炼编码工具链（read/grep/find/edit/write）与 Prompt 减负

**What to build:** 提供与 `pi-coding-agent` 一致的流畅工具链（自动分页截断的 `read`、带上下文行与有界正则的 `grep`、通配符 `find`、基于 `oldText -> newText` 局部精确替换且免填修订号的原子 `edit` 与 `write`），精简说教式 System Prompt，并在前端 Copilot 面板清晰呈现新工具调用与提案状态。

**Blocked by:** 01: Copilot 工具目录内聚、两轴结果模型与规划基线预重构

**Status:** ready-for-agent

- [ ] 实现 `read(path, start_line?, end_line?)`（并兼容 `view_file`）：单次最多返回 200 行 / 64 KiB，超出范围时正常返回前 200 行并附带续读提示 `[Showing lines X-Y of Z. Use start_line=... to continue]`，不再因请求行数超限直接报 `INVALID_ARGUMENTS`；成功读取后在下一次模型请求准备时更新规划基线。
- [ ] 实现 `grep(pattern, path_prefix?, is_regex?, literal?, context_lines?, limit?)`（兼容 `grep_code`）与 `find(pattern)`（兼容 `glob_files`）：支持路径前缀过滤、带复杂度保护的正则表达式、默认附带匹配行上下 문上下文行，并在达到条数或字节上限时给出明确截断提示。
- [ ] 实现 `edit(files: [{path, action: EDIT|CREATE|DELETE, edits?: [{oldText, newText}], content?}])` 与 `write(path, content)`（兼容 `propose_changes`）：模型无需手填 `expected_revision`，服务端按规划基线校验所有目标文件；`EDIT` 校验每个 `oldText` 在原文件中唯一且不重叠（支持 LF/CRLF 归一化并保留原文件换行风格），匹配失败或多重匹配时返回带文件行数的可诊断结果；验证通过后生成单个不可变提案、整批 Diff、`ASK/AUTO` 审批决定与 `FILE_BATCH` 原子提交。
- [ ] 精简 `prompts/copilot/system.txt`，移除冗余说教与已过时的 `base-only` / 强制手动批准描述，对齐 `challenge` 阶段与 `AUTO` 审批的真实行为。
- [ ] 前端 Copilot 消息、工具卡片与提案面板适配 `read / grep / find / edit / write` 工具名称与摘要展示，通过前后端单元与构建验证。

## Comments
