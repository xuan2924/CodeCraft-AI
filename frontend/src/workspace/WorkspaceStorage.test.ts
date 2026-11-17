import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import WorkspaceStorage from './WorkspaceStorage.vue';

const MIB = 1048576;
const budget = { limitBytes: 512 * MIB, recoveryBytes: 256 * MIB, goBytes: 200 * MIB, databaseBytes: 100 * MIB, reservedBytes: 20 * MIB, measuredAt: '2026-01-01T00:00:00Z' };
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 });
const deleted = () => new Response(JSON.stringify({ code: 'EXERCISE_DELETED', data: null }), { status: 404 });
let view: VueWrapper | undefined;
let client: QueryClient | undefined;
afterEach(() => { view?.unmount(); client?.clear(); vi.unstubAllGlobals(); });
function render() {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  view = mount(WorkspaceStorage, { props: { owner: 'owner', exerciseId: 'exercise' }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
}

it('separates actual occupancy from in-flight credit and reserved finalization space', async () => {
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => ok(budget)));
  render(); await flushPromises(); await flushPromises();
  expect(view!.text()).toContain('300.0 MiB');
  expect(view!.text()).toContain('20.0 MiB');
  expect(view!.text()).toContain('256.0 MiB');
  expect(view!.text()).toContain('普通新增空间已满');
});

it('never treats malformed responses or ambiguous failures as zero space or authoritative deletion', async () => {
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => ok({ ...budget, databaseBytes: -1 })));
  render(); await flushPromises(); await flushPromises();
  expect(view!.text()).toContain('无法确认数据占用');
  expect(view!.text()).not.toContain('0.0 MiB');
  expect(view!.emitted('deleted')).toBeUndefined();
});

it('ignores old-account errors but propagates deletion confirmed for the current scope', async () => {
  let resolveOld!: (value: Response) => void;
  let currentDeleted = false;
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input) => String(input).includes('/exercise/')
    ? new Promise<Response>((resolve) => { resolveOld = resolve; }) : currentDeleted ? deleted() : ok(budget)));
  render(); await flushPromises();
  await view!.setProps({ owner: 'new-owner', exerciseId: 'new-exercise' });
  await flushPromises();
  resolveOld(deleted());
  await flushPromises();
  expect(view!.emitted('deleted')).toBeUndefined();
  currentDeleted = true;
  await client!.invalidateQueries({ queryKey: ['workspace', 'new-owner', 'storage', 'new-exercise'] });
  await flushPromises();
  expect(view!.emitted('deleted')).toEqual([['new-exercise', 'new-owner']]);
});
