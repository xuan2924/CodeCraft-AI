import { mount, flushPromises } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, expect, it, vi } from 'vitest';
import SkillPanel from './SkillPanel.vue';
vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), listSkills: vi.fn().mockResolvedValue([]) }));
afterEach(() => vi.clearAllMocks());
it('explains private next-task semantics and offers an accessible text editor', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = mount(SkillPanel, { props: { owner: 'owner' }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  await flushPromises();
  expect(wrapper.text()).toContain('下一任务');
  expect(wrapper.text()).toContain('尚无 Skill');
  await wrapper.get('[data-test="new-skill"]').trigger('click');
  expect(wrapper.find('textarea[aria-label="SKILL.md 原文"]').exists()).toBe(true);
  wrapper.unmount(); client.clear();
});
