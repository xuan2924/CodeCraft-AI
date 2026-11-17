# 阶段 3：Copilot 编码体验增强

## 解决的核心问题

前两阶段的 Copilot 只能做有限只读和受控测试，缺少真实日常编码助手的流畅体验。本阶段把演练工作区内的 Copilot 升级为具备**项目多会话、联网开发 Bash、代码历史撤销/回滚、项目指令自动加载、排队与子任务协作**的完整工程助手。

## 关键架构选择

1. **开发 `bash` 与权威 `run_tests` 职责分离**：
   - 开发 `bash` 运行在独立可写的 Docker 工作区副本中，**允许联网下载依赖**、运行用户调试命令，结束后将变化同步回正式工作区并递增 `revision`；在 `ASK` 模式下批准的是命令本身，不预批准事后文件 Diff。
   - 权威 `run_tests` 始终禁网、只读挂载镜像内权威测试 `/opt/acceptance`，保证成绩可信。
2. **统一修改历史与安全撤销/回滚**：手动保存、Copilot Patch 与开发 Bash 产生的变更统一进入 `workspace_history`。撤销（Undo）或回滚（Rollback）不会擦除历史或做“时间旅行”，而是生成一次**新的受授权工作区修订**；若后续已有新改动产生重叠冲突，则拒绝静默覆盖。
3. **同一工作区上的多会话组织（New / Fork）**：一个演练可创建多个对话或从合法消息边界（工具调用与结果成对处）`Fork` 出新分支；**所有会话共享同一份代码与修订**，切换聊天不会切换或回滚文件。
4. **控制流与辅助协作原语**：
   - **`steer` vs `follow-up`**：`steer` 在当前任务下一安全点插队纠偏；`follow-up` 排队等当前任务彻底结束后再作为新任务派发。
   - **`btw` 旁支讨论**：主线旁边开只读讨论线程，主任务不中断，结论可显式带回主线。
   - **`delegate_task` 子任务**：委派单层子任务复用同一模型/工具/审批/停止传播体系，避免独立失控通道。
   - **联网资料（Tavily）**：`web_search` / `web_read` 仅在 Java 侧使用 `COPILOT_WEB_API_KEY`，Key 绝不进 Go 或题目容器。

## 真实踩坑与演进

- **叶子改对，装配边界仍是旧限制（高频返工根因）**：
  - 例如 Bash 协议层把命令长度放宽到 32 KiB，但实际 Runner 执行器仍截断在旧的 4096 字节；
  - 又如允许导入 256 KiB 的大 Skill，但查询接口 `GET` 却复用了带 4 KiB 限制的通用 Jackson `ObjectMapper` 导致读取失败。
  - **教训**：改扩展上限或新能力时，必须沿“Controller 入口 → 存储 → 装配读取”整条链路核对实际边界。
- **异步响应竞态覆盖新输入/复活已删文件**：Bash 删除文件后迟到的文件响应会“复活”已删除文件，页面切账号/换会话后旧回调可能覆盖新输入框。随后统一采用作用域 ID（`owner` / `conversationId`）与版本修订检查拦截迟到回调。

## 关键代码入口

- 多会话与历史：`CopilotConversationService.java`、`CopilotForkBoundary.java`、`WorkspaceHistoryService.java`
- 命令与协作：`CopilotCommandService.java`、`CopilotFollowupService.java`、`CopilotBtwService.java`、`CopilotDelegationService.java`、`CopilotWebTools.java`
- 前端组件：`CopilotConversations.vue`、`WorkspaceHistory.vue`、`CopilotFollowups.vue`、`CopilotBtw.vue`
