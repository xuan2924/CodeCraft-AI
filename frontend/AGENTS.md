# 前端通用编码规范

## 架构与状态边界

- **分层组织**：Vue 3 Composition API + `<script setup lang="ts">`。页面负责布局组合，组件负责展示交互，composable 封装可复用逻辑，`*Api.ts` 封装请求与校验。
- **状态单一事实来源**：服务端数据由 TanStack Vue Query 管理，mutation 后失效/更新对应缓存；Pinia（如 `layoutStore`）仅存少量共享客户端状态，表单与弹窗等局部状态留在组件内，**不把服务端实体或凭证抄进 Pinia**。
- **类型与生命周期**：外部响应校验后使用，禁止用 `any` 或双重断言绕过边界；Monaco、xterm 等第三方有状态实例使用局部引用或 `shallowRef`，由所有者销毁；定时器、事件订阅与 SSE 在组件卸载或作用域失效时清理，异步回调应用前核对当前页面/账号归属。

## 网络、安全与交互

- **统一请求与幂等**：复用 `src/api/request.ts` 与 `src/api/useCommand.ts` 处理鉴权、CSRF、操作凭证与结果未知命令的原键恢复，不自动重放凭证写入或未知副作用。
- **统一 SSE 管理**：通过 `useTaskStream.ts` 订阅、去重与重连对齐服务端快照；连接中断与任务终态分开表达。
- **路由与主题**：路由集中在 `src/routes.ts` 定义，跳转使用命名路由；视觉主题以 `tokens.ts` 与 `src/components/ui/`（shadcn-vue + Reka UI）为唯一来源，不覆盖组件库或编辑器内部布局样式。
- **安全与可用性**：非可信文本（如 AI 输出、日志、代码）按文本渲染，Markdown 渲染走净化防护 XSS；凭证不进 URL、日志或本地持久化；表单具备完整可访问性与键盘可用性，缺失值明确展示 `-` 或空态，区分有效 `0` 与缺失。

## 验证

- 改完后跑对应范围的 `typecheck`、`lint`、`test:unit`；路由、打包或全局样式调整时跑 `npm --prefix frontend run build`。状态流与交互测试覆盖正常、空态、失败与卸载清理分支。
