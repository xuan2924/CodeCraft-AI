import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import WorkspaceLogout from './WorkspaceLogout.vue';
import * as drafts from './drafts';
import { cancelSaveLogout, requestSaveLogout, saveLogoutOwner } from './logoutIntent';

const account = vi.hoisted(() => ({ identity: { value: { id: 'owner' } as { id: string } | null }, signOut: vi.fn() }));
vi.mock('../auth/useAccount', () => ({ useAccount: () => account }));
const navigation = vi.hoisted(() => ({ guard: (): boolean => true }));
vi.mock('vue-router', () => ({ onBeforeRouteLeave: (guard: () => boolean) => { navigation.guard = guard; } }));
vi.mock('./drafts', () => ({ listAccountDrafts: vi.fn(), readDraftEpoch: vi.fn(), removeDraft: vi.fn(), resumeDrafts: vi.fn(), releaseDraftLogout: vi.fn(), sealDraftsForLogout: vi.fn() }));
let page: VueWrapper | undefined;
beforeEach(() => {
  account.identity.value = { id: 'owner' };
  account.signOut.mockResolvedValue(false);
  vi.mocked(drafts.releaseDraftLogout).mockResolvedValue(true);
  vi.mocked(drafts.listAccountDrafts).mockResolvedValue([]);
  vi.mocked(drafts.readDraftEpoch).mockResolvedValue(1);
  vi.mocked(drafts.sealDraftsForLogout).mockResolvedValue(true);
  requestSaveLogout('owner');
});
afterEach(() => { page?.unmount(); page = undefined; cancelSaveLogout(); vi.resetAllMocks(); });
async function render(save = vi.fn(async () => true)) {
  page = mount(WorkspaceLogout, { props: { owner: 'owner', exerciseId: 'exercise', save } });
  await flushPromises();
  return { page, save };
}
async function finish(view: VueWrapper) {
  await view.findAll('button').find((button) => button.text() === '保存编辑内容并完成退出')!.trigger('click');
  await flushPromises();
}
it('releases only its own seal when disposal interrupts finalization', async () => {
  let complete!: (value: boolean) => void;
  vi.mocked(drafts.sealDraftsForLogout).mockImplementationOnce(() => new Promise((resolve) => { complete = resolve; }));
  const { page: current } = await render();
  await finish(current);
  expect(navigation.guard()).toBe(false);
  const unload = new Event('beforeunload', { cancelable: true });
  globalThis.dispatchEvent(unload);
  expect(unload.defaultPrevented).toBe(true);
  current.unmount(); page = undefined;
  const afterDisposal = new Event('beforeunload', { cancelable: true });
  globalThis.dispatchEvent(afterDisposal);
  expect(afterDisposal.defaultPrevented).toBe(false);
  complete(true); await flushPromises();
  expect(account.signOut).not.toHaveBeenCalled();
  expect(drafts.releaseDraftLogout).toHaveBeenCalledWith('owner', 2);
});

it('keeps the session and drafts when saving fails or remains unresolved', async () => {
  const { page } = await render(vi.fn(async () => false));
  await finish(page);
  expect(page.get('[role="alert"]').text()).toContain('保存未确认');
  expect(drafts.sealDraftsForLogout).not.toHaveBeenCalled();
  expect(account.signOut).not.toHaveBeenCalled();
  expect(saveLogoutOwner.value).toBe('owner');
});
it('requires explicit disposition of all backup variants rather than choosing the newest', async () => {
  vi.mocked(drafts.listAccountDrafts).mockResolvedValue([
    { owner: 'owner', exercise: 'exercise', path: 'Main.java', id: 'page-a', revision: '0', baseContent: 'base', content: 'first variant', updatedAt: 1 },
    { owner: 'owner', exercise: 'exercise', path: 'Main.java', id: 'page-b', revision: '0', baseContent: 'base', content: 'second variant', updatedAt: 2 },
  ]);
  const { page } = await render();
  await finish(page);
  expect(page.text()).toContain('first variant');
  expect(page.text()).toContain('second variant');
  expect(page.get('[role="alert"]').text()).toContain('仍有未处理备份');
  expect(drafts.removeDraft).not.toHaveBeenCalled();
  expect(drafts.sealDraftsForLogout).not.toHaveBeenCalled();
  expect(account.signOut).not.toHaveBeenCalled();
});
it('does not log out when a new backup arrives before the atomic final fence', async () => {
  vi.mocked(drafts.sealDraftsForLogout).mockResolvedValueOnce(false);
  const { page } = await render();
  await finish(page);
  expect(page.get('[role="alert"]').text()).toContain('状态已变化');
  expect(account.signOut).not.toHaveBeenCalled();
});
it('logs out only after saving and sealing an empty account draft set', async () => {
  const order: string[] = [];
  vi.mocked(drafts.sealDraftsForLogout).mockImplementationOnce(async () => { order.push('seal'); return true; });
  account.signOut.mockImplementationOnce(async () => { order.push('logout'); account.identity.value = null; return true; });
  const { page } = await render(vi.fn(async () => { order.push('save'); return true; }));
  expect(order).toEqual([]);
  await finish(page);
  expect(order).toEqual(['save', 'seal', 'logout']);
  expect(drafts.sealDraftsForLogout).toHaveBeenCalledWith('owner', 1);
  expect(saveLogoutOwner.value).toBeNull();
});
it('restores local draft admission when logout cannot be confirmed', async () => {
  const { page } = await render();
  await finish(page);
  expect(page.get('[role="alert"]').text()).toContain('退出尚未确认');
  expect(drafts.releaseDraftLogout).toHaveBeenCalledWith('owner', 2);
  expect(page.emitted('restart')).toHaveLength(1);
  expect(page.emitted('busy')).toEqual([[true], [false]]);
  expect(saveLogoutOwner.value).toBe('owner');
});
it('does not reopen or remount after a newer epoch supersedes an unsuccessful logout seal', async () => {
  vi.mocked(drafts.releaseDraftLogout).mockResolvedValueOnce(false);
  const { page } = await render();
  await finish(page);
  expect(drafts.releaseDraftLogout).toHaveBeenCalledWith('owner', 2);
  expect(drafts.resumeDrafts).not.toHaveBeenCalled();
  expect(page.emitted('restart')).toBeUndefined();
});

it('ignores completion of an old account save after identity changes', async () => {
  let complete!: (value: boolean) => void;
  const { page } = await render(vi.fn(() => new Promise<boolean>((resolve) => { complete = resolve; })));
  await finish(page);
  account.identity.value = { id: 'different-owner' };
  complete(true); await flushPromises();
  expect(drafts.sealDraftsForLogout).not.toHaveBeenCalled();
  expect(account.signOut).not.toHaveBeenCalled();
});
