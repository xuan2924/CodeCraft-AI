# 公开仓库与轻量 CI

## 用户确认的边界

- 使用 `gh` 创建 `xuan2924/CodeCraft-AI` public 仓库供 Actions 使用；当前单人开发，速度优先，不建设生产级发布与合并治理。
- 主分支 push/可选 PR：quick；纯 Markdown/票据变化不触发。nightly（UTC 19:23 / 北京时间 03:23）：非收费 smoke。手动可选 quick/smoke/heavy；heavy 不成为日常门禁。
- 不设置分支保护、强制 PR、审批或必过检查；不配置模型凭证、环境审批或部署密钥。

## 公开前检查

- Gitleaks 8.30.1 官方发布包，核对发布方 SHA-256；扫描当时全部 109 个 Git 提交，零发现。报告与工具只存 ignored `.tools/`，扫描输出脱敏。
- 同时将本机 `.env` 与相关环境中的 8 个敏感值对全部 109 个提交作精确匹配，零命中；没有打印值。`.env`、`.runtime`、工具报告及构建/依赖目录不入库。
- 发布现有 Git 历史；提交作者元数据和已有文档属于公开内容，扫描不构成绝对无泄漏保证。未提交的本地票据备注与 watchdog 文件不随本次发布。
- Actions 固定已有官方 action SHA，确认远程提交存在。工作流只有 `contents: read`，checkout 不持久化凭证，不自动上传浏览器 trace、日志、数据库等诊断载荷。
- 仓库已启用 GitHub secret scanning 和 secret-scanning push protection；创建后告警为 0、仓库 Actions secrets 为空。默认分支 master，规则集为 0、分支未受保护；本轮没有发布应用服务或开放本机端口。

## 执行结果

验证代码版本：`6008e0d`（本记录的后续纯文档提交不影响结果）。

| 检查 | 实际结果 |
|---|---|
| [最终 push quick](https://github.com/xuan2924/CodeCraft-AI/actions/runs/43685631661) | 通过；job 1 分 28 秒，quick 本体 29.22 秒；Java 317、前端 200/39 文件、Node 26 及 Go/类型/Lint 通过；现有前端 36 条 warning、零 error |
| [nightly 同档手动 smoke](https://github.com/xuan2924/CodeCraft-AI/actions/runs/43685632950) | 通过；job 3 分 42 秒，3 个浏览器黄金路径 19.2 秒；真实 Java/Go/PostgreSQL/Docker，模型检查使用显式 synthetic fixture，零收费调用；未上传 artifacts |
| 首次冷缓存 quick | [通过](https://github.com/xuan2924/CodeCraft-AI/actions/runs/43684545266)，job 2 分 41 秒、quick 48.11 秒，超软目标但未超硬时限；未因此放宽本地时限 |
| Workflow 静态检查 | 官方 Actionlint 1.7.12 校验和核对后检查通过；`git diff --check` 通过 |

首轮 [smoke](https://github.com/xuan2924/CodeCraft-AI/actions/runs/43684567350) 在 readiness 返回 503；[定向诊断复验](https://github.com/xuan2924/CodeCraft-AI/actions/runs/43685232460) 确认 runner socket 为 `660 / gid 118`，Go 的日志存在 socket permission denied，而非发布文件权限或端口冲突。Compose 原默认补充组为 0；CI 现读取实际 socket GID 写入已有 `DOCKER_SOCKET_GID` 入口，只给可信协调服务匹配组，不改 socket 权限、不改成 root、不授予题目容器 socket。修复后原 smoke 通过；临时诊断已移除，没有靠延长就绪超时或跳过断言解决。

题目发布时初始工程的预期失败输出属于负样本验证，不等于 smoke 失败。nightly 使用同一 smoke lane，但本轮是手动触发验证，没有等待定时器或宣称实际定时触发过。收费模型与全量 heavy 未运行，不新增生产验收欠账。
