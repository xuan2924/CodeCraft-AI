import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, expect, it, vi } from 'vitest';
import CopilotProjectInstructions from './CopilotProjectInstructions.vue';
import { projectInstructions } from './projectInstructionsApi';
vi.mock('./projectInstructionsApi', () => ({ projectInstructions: vi.fn() }));
let view: VueWrapper | undefined;
let client: QueryClient;
function panel() {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  view = mount(CopilotProjectInstructions, { props: { owner: 'owner', exerciseId: 'exercise', taskId: 'task', running: false }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  return view;
}
afterEach(() => { view?.unmount(); client?.clear(); vi.resetAllMocks(); });
it('shows persisted source scope and read failure distinctly, rendering instructions only as text', async () => {
  vi.mocked(projectInstructions).mockResolvedValue({ exerciseId: 'exercise', revision: '7', scopeTruncated: true, sources: [
    { path: 'AGENTS.md', scope: '.', status: 'LOADED', content: '<script>instruction</script>' },
    { path: 'src/AGENTS.md', scope: 'src', status: 'READ_FAILED', content: null },
  ] });
  const wrapper = panel(); await flushPromises();
  expect(wrapper.text()).toContain('正式修订 r7'); expect(wrapper.text()).toContain('作用域 src');
  expect(wrapper.text()).toContain('读取失败，未加载'); expect(wrapper.text()).toContain('部分规则未加载');
  expect(wrapper.text()).toContain('<script>instruction</script>'); expect(wrapper.find('script').exists()).toBe(false);
});
it('isolates delayed old task sources from a new empty conversation', async () => {
  let complete: (value: Awaited<ReturnType<typeof projectInstructions>>) => void = () => {};
  vi.mocked(projectInstructions).mockImplementation(() => new Promise(resolve => { complete = resolve; }));
  const wrapper = panel(); await flushPromises(); await wrapper.setProps({ taskId: null });
  complete({ exerciseId: 'exercise', revision: '7', scopeTruncated: false, sources: [{ path: 'AGENTS.md', scope: '.', status: 'LOADED', content: 'old-private' }] });
  await flushPromises(); expect(wrapper.text()).not.toContain('old-private'); expect(wrapper.text()).toContain('New 不继承');
});
