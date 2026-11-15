import { ApiError } from '../api/request';
import { identityFailure, errorMessage as modelMessage } from '../modelaccess/errors';
import { message as workspaceMessage } from '../workspace/api';

const messages: Record<string, string> = {
  SKILL_NOT_FOUND: '显式选择的 Skill 已停用、删除或不可访问，任务未启动；请重新读取可选项。',
  COPILOT_INVALID_REQUEST: '请求格式不合法：任务/方向文字最多 4 KiB；新任务需确认费用，拒绝理由最多 2 KiB。',
  COPILOT_STEER_NOT_ACTIVE: '当前任务不能接收方向调整，指令未受理；不会自动另起任务。',
  COPILOT_STEER_LIMIT: '已有 32 条方向调整等待生效，请先等待处理，不会覆盖旧指令。',
  STEER_SUPERSEDED: '原计划已被方向调整取代，旧批次剩余项未执行；已完成结果保留。',
  STEER_PENDING: '已接收方向调整，等待纳入下一次模型请求。',
  TASK_FINISHED: '原任务已结束，此方向调整未纳入模型请求。',
  COPILOT_APPROVAL_MODE_INVALID: '审批模式或版本不合法，请重新读取。',
  COPILOT_APPROVAL_MODE_CONFLICT: '模式已被其他请求更新；请重新读取后确认，不会覆盖新选择。',
  COPILOT_PROPOSAL_NOT_FOUND: '提案不存在或不可访问。',
  COPILOT_PROPOSAL_DECIDED: '提案已有决定，请核对原提案状态，不会重复应用。',
  APPROVAL_REQUIRED: '提案等待你核对整批 Diff 并批准或拒绝。',
  CHILD_RUNNING: '等待子任务确定结束并交回结果；父任务的方向调整将在交接后纳入，不会立即改变子目标。',
  COPILOT_DELEGATION_LIMIT: '此父任务已达到 8 次委派上限；没有创建新的子任务。',
  TEST_RUNNING: '等待关联测试的确定结果；你仍可编辑和保存，结果仅对应测试标明的版本。',
  AUTHORIZATION_ENDED: '原任务登录授权已失效，不会继续应用修改或请求模型。',
  WRITE_RECONCILIATION: '审批已保存，等待权威写入回执；不会重复应用。',
  PROPOSAL_REJECTED: '用户已拒绝此批修改，任务将基于拒绝继续。',
  TASK_STOPPED: '任务已停止，此提案没有获得写入授权。',
  COPILOT_REFERENCE_CHANGED: '引用来源版本已变化，未用新内容替换旧片段；请移除旧引用并重新预览后提交。',
  COPILOT_REFERENCE_UNAVAILABLE: '引用来源已不可用或超出读取范围；已提交的固定节选仍保留在原任务中。',
  COPILOT_FOLLOWUP_LIMIT: '当前会话已有 32 个待执行项，请取消部分排队请求。',
  COPILOT_FOLLOWUP_NOT_ACTIVE: '当前没有可追加的活动任务或可恢复队列，请重新读取。',
  COPILOT_FOLLOWUP_STARTED: '此排队项已创建独立任务，请从主任务区域停止，不会撤回已完成操作。',
  COPILOT_FORK_BOUNDARY: '此消息不是完整工具批次边界或来源已不可用，请选择最后一个工具回执或后续完整回答。',
  COPILOT_FORK_LIMIT: '此分支超过 2000 条消息或 16 MiB 协议内容上限，未创建部分分支；可使用 New 新会话。',
  COPILOT_CONVERSATION_LIMIT: '本演练已达到 100 个会话上限，请选择已有会话。',
  COPILOT_CONVERSATION_NOT_FOUND: '会话不存在或不属于当前演练，请重新读取会话列表。',
  COPILOT_BUSY: '本演练已有 Copilot 任务，不会排队；请切换会话查看原任务，等待完成或显式停止。',
  COPILOT_NOT_FOUND: '任务不存在或不可访问，请重新读取。',
  COPILOT_IDEMPOTENCY_CONFLICT: '原请求身份与内容不一致，请核对已保存任务。',
  COPILOT_CONTEXT_REQUIRED: '上下文不足以安全继续，未派发下一次对话请求。可尝试手动压缩；摘要调用可能已计费。',
  COPILOT_SUMMARY_INVALID: '摘要结构、证据引用或压缩收益不合格，原上下文保持；不会无限重试。',
  COPILOT_COMPACTION_BUSY: '已有压缩请求，等待当前请求收敛后再操作。',
  COPILOT_COMPACTION_NOT_FOUND: '压缩记录不存在或不可访问。',
  COPILOT_EVIDENCE_NOT_FOUND: '当前会话没有这条可读取的证据。',
  CONTEXT_VERSION_CHANGED: '上下文版本已变化，旧摘要未发布。',
  COPILOT_INVALID_STATE: '任务状态已变化，请重新读取。',
  COPILOT_STORAGE_UNAVAILABLE: '任务存储暂时不可用，结果可能尚未确认；不要换键重发。',
  MODEL_SELECTION_REQUIRED: '请先显式选择本次演练使用的模型。',
  MODEL_INVALID_RESPONSE: '模型返回了不完整或不合法的回复，未执行未确认的工具。',
  MODEL_OUTPUT_LIMIT_EXCEEDED: '模型回复超过单次输出上限，本次不会自动重试。',
  NO_PROGRESS: '重复相同工具操作且没有进展，任务已停止循环。',
  SERVICE_INTERRUPTED: '后台执行中断，已确认记录保留；不会自动重发收费请求。',
  MODEL_RESULT_UNKNOWN: '模型请求结果未知，供应商可能已计费；不会自动重发。',
  USER_STOP: '用户已请求停止；已完成的操作保留。',
  EXERCISE_ENDED: '演练已结束或到期，不再派发后续操作。',
};
export function errorMessage(failure: unknown): string {
  if (!(failure instanceof ApiError)) return '网络结果未确认，请核对原任务。';
  if (Object.hasOwn(messages, failure.code)) return messages[failure.code]!;
  if (failure.code.startsWith('MODEL_')) return modelMessage(failure);
  return workspaceMessage(failure);
}
export const commandPolicy = {
  errorMessage, identityFailure,
  definitiveRejection(failure: unknown) {
    return failure instanceof ApiError && [400, 403, 404, 409, 410, 422, 429].includes(failure.status) && [
      'COPILOT_REFERENCE_CHANGED', 'COPILOT_REFERENCE_UNAVAILABLE', 'COPILOT_FOLLOWUP_LIMIT', 'COPILOT_FOLLOWUP_NOT_ACTIVE', 'COPILOT_FOLLOWUP_STARTED', 'COPILOT_FORK_BOUNDARY', 'COPILOT_FORK_LIMIT', 'COPILOT_CONVERSATION_LIMIT', 'COPILOT_CONVERSATION_NOT_FOUND', 'COPILOT_INVALID_REQUEST', 'COPILOT_COMPACTION_BUSY', 'COPILOT_COMPACTION_NOT_FOUND', 'COPILOT_STEER_NOT_ACTIVE', 'COPILOT_STEER_LIMIT', 'COPILOT_APPROVAL_MODE_INVALID', 'COPILOT_APPROVAL_MODE_CONFLICT', 'COPILOT_BUSY', 'COPILOT_NOT_FOUND', 'COPILOT_PROPOSAL_NOT_FOUND', 'COPILOT_PROPOSAL_DECIDED', 'IDEMPOTENCY_CONFLICT', 'COPILOT_IDEMPOTENCY_CONFLICT', 'COPILOT_INVALID_STATE',
      'SKILL_NOT_FOUND', 'MODEL_SELECTION_REQUIRED', 'MODEL_CONNECTION_NOT_FOUND', 'MODEL_CONFIGURATION_NOT_FOUND', 'MODEL_VERSION_CONFLICT',
      'STALE_CONTROL', 'CONTROL_TRANSITION_PENDING', 'INVALID_STATE', 'EXERCISE_DELETED', 'STORAGE_BUDGET_EXCEEDED',
      'CSRF_REJECTED', 'PASSWORD_CHANGE_REQUIRED', 'FORBIDDEN',
    ].includes(failure.code);
  },
};
