import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { createMemoryHistory, createRouter } from 'vue-router';
import AccountPage from './AccountPage.vue';
import { validPassword, validUsername } from './api';
import { clearDrafts } from '../workspace/drafts';
import { cancelSaveLogout, saveLogoutOwner } from '../workspace/logoutIntent';
vi.mock('../workspace/drafts', () => ({ clearDrafts: vi.fn(async () => {}), resumeDrafts: vi.fn(async () => {}) }));
vi.mock('./api', async (original) => ({ ...await original<typeof import('./api')>(),
  captcha: vi.fn(async () => ({ id: 'a'.repeat(43), image: 'data:image/png;base64,AAAA', expiresAt: new Date(Date.now() + 120000).toISOString() })),
}));

const fixtures = {
  user: { id: 'fixture-user', username: 'invited', role: 'USER', passwordChangeRequired: false, expiresAt: '2026-12-01T00:00:00Z' },
  admin: { id: 'fixture-admin', username: 'admin', role: 'ADMIN', passwordChangeRequired: false, expiresAt: '2026-12-01T00:00:00Z' },
};
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 });
const rejected = (status: number, code: string) => new Response(JSON.stringify({ code, data: null }), { status });
const pages: VueWrapper[] = [];
const clients: QueryClient[] = [];
async function render(fetcher: typeof fetch) {
  vi.stubGlobal('fetch', fetcher);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  clients.push(client);
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: AccountPage }, { path: '/workspace', name: 'workspace', component: { template: '<div />' } }, { path: '/models', name: 'models', component: { template: '<div />' } }, { path: '/skills', name: 'skills', component: { template: '<div />' } }] });
  await router.push('/');
  const page = mount(AccountPage, { global: { plugins: [[VueQueryPlugin, { queryClient: client }], router] } });
  pages.push(page);
  await flushPromises();
  return { page, client, router };
}
afterEach(() => {
  cancelSaveLogout();
  for (const page of pages.splice(0)) page.unmount();
  for (const client of clients.splice(0)) client.clear();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

it('offers a save-and-logout flow without logging out or clearing drafts immediately', async () => {
  const fetcher = vi.fn<typeof fetch>(async (input) => String(input) === '/api/auth/session' ? ok(fixtures.user) : ok(null));
  const { page, router } = await render(fetcher);
  await page.get('.logout-button').trigger('click');
  await flushPromises();
  expect(page.get('[role="dialog"]').text()).toContain('保存后退出');
  await page.findAll('button').find((button) => button.text() === '保存后退出')!.trigger('click');
  await flushPromises();
  expect(saveLogoutOwner.value).toBe('fixture-user');
  expect(router.currentRoute.value.name).toBe('workspace');
  expect(clearDrafts).not.toHaveBeenCalled();
  expect(fetcher.mock.calls.some(([input]) => String(input) === '/api/auth/logout')).toBe(false);
});

it('shows model and Skill entries without claiming the later interview stage exists', async () => {
  const { page } = await render(vi.fn<typeof fetch>(async () => ok(fixtures.user)));
  expect(page.get('a[href="/models"]').text()).toBe('个人模型连接');
  expect(page.get('a[href="/skills"]').text()).toBe('个人 Skill');
  expect(page.text()).toContain('在真实工作区中练习分析、编码与验证');
});

it('clears account drafts only after confirmed logout', async () => {
  const { page } = await render(vi.fn<typeof fetch>(async (input) => {
    if (String(input) === '/api/auth/session') return ok(fixtures.user);
    if (String(input) === '/api/auth/csrf') return ok({ token: 'fixture' });
    return ok(null);
  }));
  expect(clearDrafts).not.toHaveBeenCalled();
  await page.get('.logout-button').trigger('click');
  await flushPromises();
  expect(clearDrafts).not.toHaveBeenCalled();
  expect(page.get('[role="dialog"]').text()).toContain('保存后退出');
  await page.findAll('button').find((button) => button.text() === '丢弃账号草稿并退出')!.trigger('click');
  await flushPromises();
  expect(clearDrafts).toHaveBeenCalledWith('fixture-user');
});

describe('identity form boundaries', () => {
  it('uses the same Unicode and username rules as Java', () => {
    expect(validPassword('😀'.repeat(8))).toBe(true);
    expect(validPassword('😀'.repeat(128))).toBe(true);
    expect(validPassword(' '.repeat(8))).toBe(true);
    expect(validPassword('😀'.repeat(7))).toBe(false);
    expect(validPassword('x'.repeat(129))).toBe(false);
    expect(validPassword('1234567\ud800')).toBe(false);
    expect(validUsername('Alice_09-X')).toBe(true);
    expect(validUsername(' Alice')).toBe(false);
    expect(validUsername('ab')).toBe(false);
  });

  it('shows a session failure with a manual retry instead of a false logged-out state', async () => {
    const fetcher = vi.fn<typeof fetch>().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(rejected(401, 'UNAUTHENTICATED'));
    const { page } = await render(fetcher);
    expect(page.get('[role="alert"]').text()).toContain('无法确认登录状态');
    expect(page.find('#login-password').exists()).toBe(false);
    await page.get('button').trigger('click');
    await flushPromises();
    expect(page.find('#login-password').exists()).toBe(true);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('blocks duplicate login, clears password, and does not put credentials in query/mutation caches', async () => {
    let finish!: (response: Response) => void;
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(rejected(401, 'UNAUTHENTICATED'))
      .mockResolvedValueOnce(ok({ token: 'fixture-csrf' }))
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { finish = resolve; }));
    const { page, client } = await render(fetcher);
    await page.get('#login-username').setValue('invited');
    await page.get('#login-password').setValue('test-password');
    await page.get('#login-captcha').setValue('ABCDE');
    await page.get('form').trigger('submit');
    await flushPromises();
    await page.get('form').trigger('submit');
    expect(fetcher).toHaveBeenCalledTimes(3);
    expect(page.get('#login-password').attributes('disabled')).toBeDefined();
    finish(rejected(401, 'INVALID_CREDENTIALS'));
    await flushPromises();
    expect(page.get('[role="alert"]').text()).toContain('账号或密码不正确');
    expect((page.get('#login-password').element as HTMLInputElement).value).toBe('');
    expect(JSON.stringify(client.getQueryCache().getAll().map((query) => query.state.data))).not.toContain('test-password');
    expect(client.getMutationCache().getAll()).toHaveLength(0);
  });

  it('shows only password change and logout for a restricted administrator', async () => {
    const { page } = await render(vi.fn<typeof fetch>().mockResolvedValue(ok({ ...fixtures.admin, passwordChangeRequired: true })));
    expect(page.text()).toContain('先修改临时密码');
    expect(page.find('#invited-username').exists()).toBe(false);
    expect(page.get('#new-password').attributes('type')).toBe('password');
  });

  it('retains the same create payload/key after an unknown result and never caches temporary credentials', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(ok(fixtures.admin))
      .mockResolvedValueOnce(ok({ token: 'fixture-csrf' }))
      .mockRejectedValueOnce(new TypeError('connection dropped'))
      .mockResolvedValueOnce(ok({ token: 'fixture-csrf-2' }))
      .mockResolvedValueOnce(ok({ userId: 'new-user', operationId: 'operation', credentialDelivered: false, temporaryPassword: null }))
      .mockResolvedValueOnce(ok(fixtures.admin));
    const { page, client } = await render(fetcher);
    await page.get('#invited-username').setValue('new_user');
    await page.get('form').trigger('submit');
    await flushPromises();
    expect(page.get('#invited-username').attributes('disabled')).toBeDefined();
    expect(page.text()).toContain('用原请求核对并重试');
    await page.get('form').trigger('submit');
    await flushPromises();
    const attempts = fetcher.mock.calls.filter(([url]) => url === '/api/admin/users');
    expect(attempts).toHaveLength(2);
    expect(attempts[0]?.[1]?.body).toEqual(attempts[1]?.[1]?.body);
    expect(new Headers(attempts[0]?.[1]?.headers).get('Idempotency-Key'))
      .toEqual(new Headers(attempts[1]?.[1]?.headers).get('Idempotency-Key'));
    expect(page.text()).toContain('本次重试不会再次交付');
    expect(page.find('#temporary-password').exists()).toBe(false);
    expect(client.getMutationCache().getAll()).toHaveLength(0);
  });

  it('self reset signs out but retains the one-time delivery outside authenticated controls', async () => {
    const target = '00000000-0000-0000-0000-000000000001';
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(ok({ ...fixtures.admin, id: target }))
      .mockResolvedValueOnce(ok({ token: 'fixture-csrf' }))
      .mockResolvedValueOnce(ok({ userId: target, operationId: 'reset', credentialDelivered: true,
        temporaryPassword: 'reset-fixture-password', currentSessionRevoked: true }));
    const { page, client } = await render(fetcher);
    await page.get('#reset-target').setValue(target);
    await page.get('#password-reset-form').trigger('submit');
    await flushPromises();
    expect(page.find('#login-password').exists()).toBe(true);
    expect(page.find('#password-reset-form').exists()).toBe(false);
    expect((page.get('#temporary-password').element as HTMLInputElement).value).toBe('reset-fixture-password');
    expect(JSON.stringify(client.getQueryCache().getAll().map((query) => query.state.data))).not.toContain('reset-fixture-password');
    expect(client.getMutationCache().getAll()).toHaveLength(0);
  });

  it('unknown reset results retain the target/key and replay cannot disclose a credential', async () => {
    const target = '00000000-0000-0000-0000-000000000001';
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(ok(fixtures.admin))
      .mockResolvedValueOnce(ok({ token: 'fixture-csrf' })).mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce(ok({ token: 'fixture-csrf' }))
      .mockResolvedValueOnce(ok({ userId: target, operationId: 'reset', credentialDelivered: false,
        temporaryPassword: null, currentSessionRevoked: false }));
    const { page } = await render(fetcher);
    await page.get('#reset-target').setValue(target);
    await page.get('#password-reset-form').trigger('submit');
    await flushPromises();
    expect(page.get('#reset-target').attributes('disabled')).toBeDefined();
    await page.get('#password-reset-form').trigger('submit');
    await flushPromises();
    const attempts = fetcher.mock.calls.filter(([url]) => String(url).endsWith('/password-reset'));
    expect(attempts).toHaveLength(2);
    expect(attempts[0]?.[0]).toBe(attempts[1]?.[0]);
    expect(new Headers(attempts[0]?.[1]?.headers).get('Idempotency-Key'))
      .toBe(new Headers(attempts[1]?.[1]?.headers).get('Idempotency-Key'));
    expect(page.find('#temporary-password').exists()).toBe(false);
    expect(page.text()).toContain('原请求不会再次改变密码或交付凭证');
  });

  it('does not sign out or report success when logout fails', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(ok(fixtures.user))
      .mockResolvedValueOnce(ok({ token: 'fixture-csrf' })).mockRejectedValueOnce(new TypeError('offline'));
    const { page } = await render(fetcher);
    await page.get('.logout-button').trigger('click');
    await flushPromises();
    await page.findAll('button').find((button) => button.text() === '丢弃账号草稿并退出')!.trigger('click');
    await flushPromises();
    expect(clearDrafts).not.toHaveBeenCalled();
    expect(page.text()).toContain('账号已就绪');
    expect(page.get('[role="alert"]').text()).toContain('操作结果尚未确认');
  });

  it('aborts requests and ignores late login responses after unmount', async () => {
    let finish!: (response: Response) => void;
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(rejected(401, 'UNAUTHENTICATED'))
      .mockResolvedValueOnce(ok({ token: 'fixture-csrf' }))
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { finish = resolve; }));
    const { page, client } = await render(fetcher);
    await page.get('#login-username').setValue('invited');
    await page.get('#login-password').setValue('test-password');
    await page.get('#login-captcha').setValue('ABCDE');
    await page.get('form').trigger('submit');
    await flushPromises();
    page.unmount();
    expect(fetcher.mock.calls[2]?.[1]?.signal?.aborted).toBe(true);
    finish(ok(fixtures.user));
    await flushPromises();
    expect(client.getQueryData(['identity-session'])).not.toEqual(fixtures.user);
  });
});
