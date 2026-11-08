import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, expect, test, vi } from 'vitest';
import StatusPage from './StatusPage.vue';

const cleanups: (() => void)[] = [];
afterEach(() => {
  for (const cleanup of cleanups.splice(0)) cleanup();
  vi.unstubAllGlobals();
});

function render() {
  const client = new QueryClient();
  const wrapper = mount(StatusPage, { global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  cleanups.push(() => { wrapper.unmount(); client.clear(); });
  return wrapper;
}

test('shows unavailable dependency from a real-shaped 503 response, not a generic success', async () => {
  // Explicit HTTP boundary substitute; real services are exercised by Playwright separately.
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
    JSON.stringify({ database: 'AVAILABLE', sandbox: 'UNAVAILABLE' }), { status: 503 },
  )));
  const page = render();
  await flushPromises();
  expect(page.get('[data-testid="database"]').text()).toContain('可用');
  expect(page.get('[data-testid="sandbox"]').text()).toContain('不可用');
  expect(page.text()).toContain('账号、AI 与完整演练尚未接入');
});

test('hides stale success when a refresh cannot reach Java and allows retry', async () => {
  const fetch = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify({ database: 'AVAILABLE', sandbox: 'AVAILABLE' })))
    .mockRejectedValueOnce(new Error('jdbc:secret-connection-and-stack'))
    .mockResolvedValueOnce(new Response(JSON.stringify({ database: 'AVAILABLE', sandbox: 'AVAILABLE' })));
  vi.stubGlobal('fetch', fetch);
  const page = render();
  await flushPromises();
  await page.get('button').trigger('click');
  await flushPromises();
  expect(page.get('[role="alert"]').text()).toContain('无法取得有效状态');
  expect(page.find('table').exists()).toBe(false);
  expect(page.text()).not.toContain('secret-connection');
  await page.get('button').trigger('click');
  await flushPromises();
  expect(page.find('table').exists()).toBe(true);
});

test('pending checks disable repeat submissions and are cancelled on unmount', async () => {
  const fetch = vi.fn().mockImplementation(() => new Promise(() => {}));
  vi.stubGlobal('fetch', fetch);
  const page = render();
  await flushPromises();
  expect(page.get('button').attributes('disabled')).toBeDefined();
  await page.get('button').trigger('click');
  expect(fetch).toHaveBeenCalledTimes(1);
  const request: RequestInit = fetch.mock.calls[0][1];
  page.unmount();
  expect(request.signal?.aborted).toBe(true);
});

test.each([
  { status: 200, body: { database: 'AVAILABLE', sandbox: 'UNAVAILABLE' } },
  { status: 503, body: { database: 'AVAILABLE', sandbox: 'AVAILABLE' } },
  { status: 200, body: { database: 'pretend-ready', sandbox: 'AVAILABLE' } },
])('rejects malformed or inconsistent status: $status $body', async ({ status, body }) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status })));
  const page = render();
  await flushPromises();
  expect(page.find('[role="alert"]').exists()).toBe(true);
  expect(page.find('table').exists()).toBe(false);
});
