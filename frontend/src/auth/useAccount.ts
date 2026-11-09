import { computed, nextTick, onScopeDispose, ref } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import * as api from './api';
import { clearDrafts, resumeDrafts } from '../workspace/drafts';

const sessionKey = ['identity-session'] as const;
export function useAccount() {
  const client = useQueryClient();
  const query = useQuery({ queryKey: sessionKey, queryFn: ({ signal }) => api.session(signal), retry: false,
    staleTime: 0, refetchOnWindowFocus: true, gcTime: 0 });
  const controller = new AbortController();
  let disposed = false;
  onScopeDispose(() => { disposed = true; controller.abort(); temporaryPassword.value = null; });
  const pending = ref(false);
  const error = ref('');
  const notice = ref('');
  const temporaryPassword = ref<string | null>(null);
  const createdUsername = ref('');
  const unresolvedCreate = ref(false);
  const createdUserId = ref('');
  const unresolvedReset = ref(false);
  let resetKey = crypto.randomUUID();
  let attemptedTarget = '';
  let createKey = crypto.randomUUID();
  let attemptedUsername = '';
  let passwordKey = crypto.randomUUID();
  const identity = computed(() => query.data.value ?? null);

  async function forgetIdentity() {
    await client.cancelQueries();
    client.removeQueries({ predicate: (entry) => entry.queryKey[0] !== sessionKey[0] });
    client.setQueryData(sessionKey, null);
    temporaryPassword.value = null;
  }
  async function action(operation: () => Promise<void>) {
    if (pending.value || disposed) return;
    pending.value = true;
    error.value = '';
    notice.value = '';
    try { await operation(); }
    catch (failure) {
      if (!disposed) {
        error.value = api.errorMessage(failure);
        if (failure instanceof api.ApiError && failure.code === 'UNAUTHENTICATED') await forgetIdentity();
      }
    } finally { if (!disposed) pending.value = false; }
  }
  async function signIn(username: string, password: string) {
    await action(async () => {
      const result = await api.login(username, password, controller.signal);
      if (disposed) return;
      await client.cancelQueries({ queryKey: sessionKey });
      try { await resumeDrafts(result.id); }
      catch { if (!disposed) notice.value = '登录成功，但本地草稿存储不可用，请及时保存或复制内容。'; }
      if (!disposed) client.setQueryData(sessionKey, result);
    });
  }
  async function signOut() {
    await action(async () => {
      const owner = identity.value?.id;
      await api.logout(controller.signal);
      if (owner) {
        try { await clearDrafts(owner); }
        catch { if (!disposed) error.value = '已退出登录，但本地草稿清理失败。请重新登录后重试退出，或清除本站浏览器数据。'; }
      }
      if (disposed) return;
      await forgetIdentity();
      notice.value = '已退出登录。';
    });
  }
  async function password(currentPassword: string, newPassword: string) {
    await action(async () => {
      await api.changePassword(currentPassword, newPassword, passwordKey, controller.signal);
      if (disposed) return;
      passwordKey = crypto.randomUUID();
      await forgetIdentity();
      notice.value = '密码已修改，所有旧登录已撤销。请用新密码重新登录。';
    });
  }
  async function create(username: string) {
    await action(async () => {
      const wasUnresolved = unresolvedCreate.value;
      if (!wasUnresolved) attemptedUsername = username;
      unresolvedCreate.value = true;
      let result: api.CreatedAccount;
      try { result = await api.createAccount(attemptedUsername, createKey, controller.signal); }
      catch (failure) {
        if (!wasUnresolved && failure instanceof api.ApiError && [400, 403, 409, 429].includes(failure.status)
          && failure.code !== 'IDEMPOTENCY_CONFLICT') {
          unresolvedCreate.value = false;
          createKey = crypto.randomUUID();
        }
        throw failure;
      }
      if (disposed) return;
      createdUsername.value = attemptedUsername.toLowerCase();
      createdUserId.value = result.userId;
      temporaryPassword.value = result.temporaryPassword;
      notice.value = result.credentialDelivered ? '账号已创建。请通过可信渠道交付临时密码。'
        : '账号已创建，但本次重试不会再次交付临时密码。需要管理员显式重置密码后交付。';
      createKey = crypto.randomUUID();
      unresolvedCreate.value = false;
      await client.invalidateQueries({ queryKey: sessionKey });
    });
  }
  async function reset(userId: string) {
    await action(async () => {
      const wasUnresolved = unresolvedReset.value;
      if (!wasUnresolved) attemptedTarget = userId;
      unresolvedReset.value = true;
      temporaryPassword.value = null;
      let result: api.PasswordReset;
      try { result = await api.resetPassword(attemptedTarget, resetKey, controller.signal); }
      catch (failure) {
        if (!wasUnresolved && failure instanceof api.ApiError && [400, 403, 404, 429].includes(failure.status)) {
          unresolvedReset.value = false;
          resetKey = crypto.randomUUID();
        }
        throw failure;
      }
      if (disposed) return;
      if (result.currentSessionRevoked) {
        await forgetIdentity();
        await nextTick();
      }
      if (disposed) return;
      createdUsername.value = '用户标识 ' + result.userId;
      createdUserId.value = result.userId;
      temporaryPassword.value = result.temporaryPassword;
      notice.value = result.credentialDelivered ? '密码已重置，全部旧登录已撤销。请交付临时密码并要求首次改密。'
        : '重置已完成；原请求不会再次改变密码或交付凭证。凭证丢失时请显式再次重置。';
      resetKey = crypto.randomUUID();
      unresolvedReset.value = false;
    });
  }
  return { query, identity, pending, error, notice, temporaryPassword, createdUsername, createdUserId,
    unresolvedCreate, unresolvedReset, signIn, signOut, password, create, reset };
}
