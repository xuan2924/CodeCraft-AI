import { ApiError } from '../api/request';

const messages: Record<string, string> = {
  MODEL_INVALID_REQUEST: '请检查地址、模型参数与输入格式。',
  MODEL_NAME_CONFLICT: '名称已被使用，请换一个名称。',
  MODEL_VERSION_CONFLICT: '配置已变更，请重新读取后再提交，不会覆盖别人的修改。',
  MODEL_CONNECTION_NOT_FOUND: '连接已删除或不可访问，请重新读取列表。',
  MODEL_CONFIGURATION_NOT_FOUND: '模型已删除或不可访问，请重新读取列表。',
  MODEL_CHECK_ALREADY_ACTIVE: '当前账号已有一组检查正在处理，请等待结果。',
  MODEL_CHECK_RATE_LIMITED: '五分钟内最多启动三组检查，失败也计数，请稍后再试。',
  MODEL_CHECK_FEE_ACKNOWLEDGEMENT_REQUIRED: '请先确认真实模型请求可能产生费用。',
  MODEL_AUTHENTICATION_FAILED: '供应商拒绝了鉴权，请检查保存的 Key。',
  MODEL_ENDPOINT_NOT_FOUND: '供应商地址或模型不存在，请核对配置。',
  MODEL_PROVIDER_RATE_LIMITED: '供应商限流。本次不会自动重试。',
  MODEL_PROVIDER_REQUEST_REJECTED: '供应商拒绝了参数，请核对模型 ID 与思考设置。',
  MODEL_PROVIDER_UNAVAILABLE: '供应商暂时不可用，本次不会自动重试。',
  MODEL_NETWORK_UNAVAILABLE: '无法连接供应商，请核对地址与网络。',
  MODEL_REDIRECT_REJECTED: '地址返回重定向，已拒绝转发 Key；请填写最终 HTTPS 地址。',
  MODEL_RUNTIME_BUSY: '模型执行资源暂时繁忙，请稍后手动重试。',
  MODEL_CALL_CANCELLED: '检查已中断；已发出的请求仍可能收费。',
  MODEL_STORAGE_UNAVAILABLE: '模型配置存储暂时不可用，请稍后重新读取。',
  MODEL_PROTOCOL_UNSUPPORTED: '当前协议或思考模式不受支持，不会自动切换。',
  MODEL_INVALID_RESPONSE: '模型未完成约定的检查闭环，不代表连接或工具能力已通过。',
  MODEL_REQUEST_TIMEOUT: '请求已超时。供应商仍可能收费，不会自动重试。',
  MODEL_OUTPUT_LIMIT_EXCEEDED: '回复超过检查限额，本次检查未通过。',
  MODEL_CREDENTIAL_UNAVAILABLE: '已保存凭证暂时无法解密，请联系维护者；不会使用备用 Key。',
  MODEL_ENCRYPTION_UNAVAILABLE: '服务端凭证加密未就绪，请联系维护者。',
  MODEL_RECEIPT_EXPIRED: '原请求回执已过期，请关闭表单并重新读取配置后核对。',
  MODEL_IDEMPOTENCY_CONFLICT: '原请求身份与内容冲突，请重新读取配置后核对。',
  UNAUTHENTICATED: '登录已失效，请重新登录。',
  PASSWORD_CHANGE_REQUIRED: '请先修改临时密码。',
  FORBIDDEN: '当前账号不能执行此操作。',
  CSRF_REJECTED: '安全校验未通过，请重新读取页面后再操作。',
};
export function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return '网络结果未确认，请检查连接。';
  const message = Object.hasOwn(messages, error.code) ? messages[error.code] : undefined;
  return message ?? '请求未完成，请检查网络或稍后重新读取。';
}
export function identityFailure(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || error.status === 403 && ['FORBIDDEN', 'PASSWORD_CHANGE_REQUIRED'].includes(error.code));
}
/** Only an explicit first-attempt rejection establishes that this command made no change. */
export function definitiveRejection(error: unknown): boolean {
  return error instanceof ApiError && [400, 403, 404, 409, 422, 429].includes(error.status) && [
    'MODEL_INVALID_REQUEST', 'MODEL_NAME_CONFLICT', 'MODEL_VERSION_CONFLICT', 'MODEL_CONNECTION_NOT_FOUND', 'MODEL_CONFIGURATION_NOT_FOUND',
    'MODEL_CHECK_ALREADY_ACTIVE', 'MODEL_CHECK_RATE_LIMITED', 'MODEL_CHECK_FEE_ACKNOWLEDGEMENT_REQUIRED', 'FORBIDDEN', 'PASSWORD_CHANGE_REQUIRED', 'CSRF_REJECTED',
  ].includes(error.code);
}
