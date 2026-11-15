import { mount, flushPromises } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ApiError } from '../api/request';
import { listSkills, saveSkill, skillPackage, type Skill } from './api';
import SkillPanel from './SkillPanel.vue';
vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), listSkills: vi.fn(), saveSkill: vi.fn(), skillPackage: vi.fn() }));
beforeEach(() => { vi.mocked(listSkills).mockResolvedValue([]); });
afterEach(() => vi.resetAllMocks());
function fixture() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = mount(SkillPanel, { props: { owner: 'owner' }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  return { client, wrapper };
}
const skill: Skill = { id: '10000000-0000-0000-0000-000000000001', name: 'review', description: 'Guidance', version: '1',
  contentVersion: '20000000-0000-0000-0000-000000000001', enabled: false, deleted: false, contentBytes: 40 };
it('explains private next-task semantics and offers an accessible text editor', async () => {
  const { client, wrapper } = fixture();
  await flushPromises();
  expect(wrapper.text()).toContain('下一任务');
  expect(wrapper.text()).toContain('尚无 Skill');
  await wrapper.get('[data-test="new-skill"]').trigger('click');
  expect(wrapper.find('textarea[aria-label="SKILL.md 原文"]').exists()).toBe(true);
  wrapper.unmount(); client.clear();
});
it('suppresses duplicate submissions and aborts owned work on unmount without applying a late save', async () => {
  let finish: ((value: Skill) => void) | undefined;
  vi.mocked(saveSkill).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  const { client, wrapper } = fixture();
  const invalidate = vi.spyOn(client, 'invalidateQueries');
  await flushPromises();
  await wrapper.get('[data-test="new-skill"]').trigger('click');
  await wrapper.get('form').trigger('submit'); await wrapper.get('form').trigger('submit');
  expect(saveSkill).toHaveBeenCalledTimes(1);
  const signal = vi.mocked(saveSkill).mock.calls[0]?.[4];
  expect(signal?.aborted).toBe(false);
  wrapper.unmount();
  expect(signal?.aborted).toBe(true);
  finish?.(skill); await flushPromises();
  expect(invalidate).not.toHaveBeenCalled(); client.clear();
});
it('drops late private text after owner changes and clears the old editor', async () => {
  vi.mocked(listSkills).mockResolvedValueOnce([skill]).mockResolvedValue([]);
  let finish: ((value: { formatVersion: 1; files: { path: string; content: string }[] }) => void) | undefined;
  vi.mocked(skillPackage).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  const { client, wrapper } = fixture(); await flushPromises();
  await wrapper.findAll('button').find(button => button.text() === '编辑 review')?.trigger('click');
  expect(skillPackage).toHaveBeenCalledTimes(1);
  const signal = vi.mocked(skillPackage).mock.calls[0]?.[2];
  await wrapper.setProps({ owner: 'new-owner' });
  expect(signal?.aborted).toBe(true);
  finish?.({ formatVersion: 1, files: [{ path: 'SKILL.md', content: 'private-old-owner' }] });
  await flushPromises();
  expect(wrapper.find('form').exists()).toBe(false);
  expect(wrapper.text()).not.toContain('private-old-owner');
  wrapper.unmount(); client.clear();
});
it('keeps the draft and reports rejected atomic saves without replaying them', async () => {
  vi.mocked(saveSkill).mockRejectedValue(new ApiError(422, 'SKILL_LIMIT_EXCEEDED'));
  const { client, wrapper } = fixture(); await flushPromises();
  await wrapper.get('[data-test="new-skill"]').trigger('click');
  await wrapper.get('textarea[aria-label="SKILL.md 原文"]').setValue('draft remains');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(wrapper.get('[role="alert"]').text()).toContain('整次操作已拒绝');
  expect(wrapper.get('textarea[aria-label="SKILL.md 原文"]').element).toHaveProperty('value', 'draft remains');
  expect(saveSkill).toHaveBeenCalledTimes(1);
  wrapper.unmount(); client.clear();
});
