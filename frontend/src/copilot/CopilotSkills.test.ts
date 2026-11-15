import { mount, flushPromises } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, expect, it, vi } from 'vitest';
import CopilotSkills from './CopilotSkills.vue';
import { snapshotPackage } from './skillApi';
vi.mock('../skills/api', async importOriginal => ({ ...await importOriginal<typeof import('../skills/api')>(), listSkills: vi.fn().mockResolvedValue([
  { id: '10000000-0000-0000-0000-000000000001', name: 'guide', description: 'Guidance', version: '1', enabled: true },
]) }));
vi.mock('./skillApi', () => ({ taskSkills: vi.fn().mockResolvedValue([
  { skillId: '10000000-0000-0000-0000-000000000001', versionId: '20000000-0000-0000-0000-000000000001', name: 'guide', description: 'Guidance', explicitlySelected: false, explicitLoaded: false },
]), snapshotPackage: vi.fn() }));
afterEach(() => vi.clearAllMocks());
it('aborts and hides late private snapshot text after task or owner changes', async () => {
  let complete: ((value: { formatVersion: 1; files: { path: string; content: string }[] }) => void) | undefined;
  vi.mocked(snapshotPackage).mockImplementationOnce(() => new Promise(resolve => { complete = resolve; }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = mount(CopilotSkills, { props: { owner: 'owner', taskId: 'task', disabled: false, modelValue: [] }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  await flushPromises();
  await wrapper.findAll('button').find(button => button.text() === '查看 guide 留存原文')?.trigger('click');
  await flushPromises();
  expect(snapshotPackage).toHaveBeenCalledTimes(1);
  const signal = vi.mocked(snapshotPackage).mock.calls[0]?.[3];
  await wrapper.setProps({ owner: 'other', taskId: 'other-task' });
  expect(signal?.aborted).toBe(true);
  complete?.({ formatVersion: 1, files: [{ path: 'SKILL.md', content: 'private-original-owner' }] });
  await flushPromises();
  expect(wrapper.text()).not.toContain('private-original-owner');
  expect(wrapper.find('[aria-label="私有 Skill 留存原文"]').exists()).toBe(false);
  wrapper.unmount(); client.clear();
});
it('distinguishes task catalog from loaded guidance and emits explicit next-task selection', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = mount(CopilotSkills, { props: { owner: 'owner', taskId: 'task', disabled: false, modelValue: [] }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  await flushPromises();
  expect(wrapper.text()).toContain('清单不等于正文已加载');
  expect(wrapper.text()).toContain('未显式加载');
  await wrapper.get('input[type="checkbox"]').setValue(true);
  expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([['10000000-0000-0000-0000-000000000001']]);
  wrapper.unmount(); client.clear();
});
