import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import ExerciseRetentionEntry from './ExerciseRetentionEntry.vue';

let view: VueWrapper | undefined;
let client: QueryClient | undefined;
afterEach(() => { view?.unmount(); client?.clear(); vi.unstubAllGlobals(); });
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }));
const progress = { exerciseId: 'old', state: 'PENDING', requestedAt: '2026-01-01T00:00:00Z', completedAt: null,
  nextAttemptAt: '2026-01-01T00:00:00Z', failureCode: null, interventionRequired: false };
const entry = { exerciseId: 'old', endedAt: '2026-01-01T00:00:00Z', retainUntil: '2026-01-31T00:00:00Z', deletionState: null };
async function render(fetcher: typeof fetch) {
  vi.stubGlobal('fetch', fetcher);
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  view = mount(ExerciseRetentionEntry, { props: { owner: 'owner', entry }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  await flushPromises();
  return view;
}
async function click(text: string) { await view!.findAll('button').find((button) => button.text() === text)!.trigger('click'); await flushPromises(); }

it('requires confirmation and revokes local access on acceptance, before physical completion', async () => {
  let resolve: (response: Response) => void = () => {};
  let deletes = 0;
  await render(vi.fn<typeof fetch>(async (path, options) => {
    if (String(path) === '/api/auth/csrf') return ok({ token: 'csrf' });
    if (options?.method === 'DELETE') { deletes++; return new Promise<Response>((done) => { resolve = done; }); }
    return ok(progress);
  }));
  expect(view!.text()).toContain('2026');
  await click('删除演练');
  expect(deletes).toBe(0);
  expect(view!.text()).toContain('当前浏览器该演练的草稿');
  await click('确认删除');
  expect(deletes).toBe(1);
  expect(view!.emitted('deleted')).toBeUndefined();
  expect(view!.findAll('button').find((button) => button.text() === '确认删除')!.attributes('disabled')).toBeDefined();
  resolve(ok(progress));
  await flushPromises();
  expect(view!.emitted('deleted')).toEqual([['old']]);
  expect(view!.text()).toContain('正在删除');
});

it.each([401, 404, 503])('does not treat an ambiguous %s as accepted deletion', async (status) => {
  await render(vi.fn<typeof fetch>(async (path) => String(path) === '/api/auth/csrf' ? ok({ token: 'csrf' })
    : new Response(JSON.stringify({ code: 'RESOURCE_NOT_FOUND', data: null }), { status })));
  await click('删除演练');
  await click('确认删除');
  expect(view!.emitted('deleted')).toBeUndefined();
});
