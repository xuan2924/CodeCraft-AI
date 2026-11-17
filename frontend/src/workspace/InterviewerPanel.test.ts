import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import InterviewerPanel from './InterviewerPanel.vue';
import * as api from './interviewerApi';

vi.mock('./interviewerApi', () => ({ history: vi.fn(), ask: vi.fn() }));
const task: api.InterviewerTask = { id: 'task', exerciseId: 'exercise', question: '需求是什么？', answer: null, state: 'QUEUED',
  reason: null, platformVersionId: 'version', phase: 'READY', revision: '1', createdAt: '2026-01-01T00:00:00Z', completedAt: null };
const wrappers: ReturnType<typeof mount>[] = [];
function render() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const wrapper = mount(InterviewerPanel, { props: { owner: 'owner', exerciseId: 'exercise', credential: 'credential', disabled: false, phase: 'READY' },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } });
  wrappers.push(wrapper); return wrapper;
}
beforeEach(() => { vi.clearAllMocks(); vi.mocked(api.history).mockResolvedValue([]); vi.mocked(api.ask).mockResolvedValue(task); });
afterEach(() => { wrappers.splice(0).forEach((wrapper) => wrapper.unmount()); });
it('shows prompt chips when empty and sends a real command without submitting automatically', async () => {
  const wrapper = render(); await flushPromises();
  const chips = wrapper.findAll('.prompt-chip');
  expect(chips.length).toBe(3);
  await chips[0]!.trigger('click');
  expect(wrapper.get('textarea').element.value).toContain('边界');
  await wrapper.get('textarea').setValue('需求是什么？');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(api.ask).toHaveBeenCalledWith('owner', 'exercise', '需求是什么？', expect.any(String), 'credential', expect.any(AbortSignal));
  expect(wrapper.get('textarea').element.value).toBe('');
});
it('retains the original question and key when an outcome is unknown', async () => {
  vi.mocked(api.ask).mockRejectedValueOnce(new Error('network'));
  const wrapper = render(); await flushPromises();
  await wrapper.get('textarea').setValue('需求是什么？');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(wrapper.get('textarea').element.value).toBe('需求是什么？');
  expect(wrapper.get('textarea').attributes('readonly')).toBeDefined();
  await wrapper.findAll('button').find((button) => button.text() === '核对原提问')!.trigger('click'); await flushPromises();
  expect(vi.mocked(api.ask).mock.calls[1]?.slice(0, 5)).toEqual(vi.mocked(api.ask).mock.calls[0]?.slice(0, 5));
});
it('pause leaves history readable but prevents new requests', async () => {
  vi.mocked(api.history).mockResolvedValue([{ ...task, state: 'SUCCEEDED', answer: '<script>not executable</script>' }]);
  const wrapper = render(); await wrapper.setProps({ phase: 'PAUSED', disabled: true }); await flushPromises();
  expect(wrapper.text()).toContain('<script>not executable</script>');
  expect(wrapper.find('script').exists()).toBe(false);
  expect(wrapper.get('#interviewer-question').attributes('readonly')).toBeDefined();
  await wrapper.get('form').trigger('submit');
  expect(api.ask).not.toHaveBeenCalled();
});
it('a failed read is not shown as an empty conversation', async () => {
  vi.mocked(api.history).mockRejectedValue(new Error('network'));
  const wrapper = render(); await flushPromises();
  expect(wrapper.get('[role="alert"]').text()).toContain('无法读取对话');
  expect(wrapper.text()).not.toContain('尚无提问');
  expect(api.ask).not.toHaveBeenCalled();
});
it('ignores a late command from the previous exercise', async () => {
  let resolve: (value: api.InterviewerTask) => void = () => {};
  vi.mocked(api.ask).mockImplementation(() => new Promise((done) => { resolve = done; }));
  const wrapper = render(); await flushPromises();
  await wrapper.get('textarea').setValue('old question'); await wrapper.get('form').trigger('submit');
  await wrapper.setProps({ exerciseId: 'new-exercise' }); await flushPromises();
  await wrapper.get('textarea').setValue('new question');
  resolve(task); await flushPromises();
  expect(wrapper.get('textarea').element.value).toBe('new question');
});

it('supports HINT mode with quick topics, validation, and renders hint level and stage badges', async () => {
  vi.mocked(api.history).mockResolvedValue([
    { ...task, id: 'h1', state: 'SUCCEEDED', phase: 'ACTIVE', stage: 'CHALLENGE', mode: 'HINT', topic: 'boundary', hintLevel: 2, answer: '注意边界溢出。' },
  ]);
  const wrapper = mount(InterviewerPanel, {
    props: { owner: 'owner', exerciseId: 'exercise', credential: 'credential', disabled: false, phase: 'ACTIVE', stage: 'CHALLENGE' },
    global: { plugins: [[VueQueryPlugin, { queryClient: new QueryClient({ defaultOptions: { queries: { retry: false } } }) }]] },
  });
  wrappers.push(wrapper);
  await flushPromises();
  expect(wrapper.text()).toContain('挑战阶段');
  expect(wrapper.text()).toContain('提示 L2 · boundary');
  expect(wrapper.text()).toContain('CHALLENGE');

  await wrapper.findAll('button').find((button) => button.text() === '提示锦囊')!.trigger('click');
  await wrapper.findAll('button').find((button) => button.text() === 'boundary')!.trigger('click');
  expect(wrapper.text()).toContain('当前可见记录该主题已获提示至 L2');

  await wrapper.get('#interviewer-topic').setValue('bad topic!');
  expect(wrapper.text()).toContain('提示主题仅支持');
  await wrapper.get('#interviewer-question').setValue('这里边界怎么处理？');
  await wrapper.get('form').trigger('submit');
  expect(api.ask).not.toHaveBeenCalled();

  await wrapper.get('#interviewer-topic').setValue('precision');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(api.ask).toHaveBeenCalledWith('owner', 'exercise', '这里边界怎么处理？', expect.any(String), 'credential', expect.any(AbortSignal), { mode: 'HINT', topic: 'precision' });
});

it('disables DEFENSE mode in READY phase and allows it in ACTIVE phase', async () => {
  vi.mocked(api.history).mockResolvedValue([
    { ...task, id: 'd1', state: 'SUCCEEDED', phase: 'ACTIVE', stage: 'BASE', mode: 'DEFENSE', answer: '为什么这样拆类？' },
  ]);
  const wrapper = render(); await flushPromises();
  const defenseInReady = wrapper.findAll('button').find((button) => button.text() === '代码拷问')!;
  expect(defenseInReady.attributes('disabled')).toBeDefined();

  await wrapper.setProps({ phase: 'ACTIVE', stage: 'BASE' }); await flushPromises();
  const defenseInActive = wrapper.findAll('button').find((button) => button.text() === '代码拷问')!;
  expect(defenseInActive.attributes('disabled')).toBeUndefined();
  await defenseInActive.trigger('click');
  await wrapper.get('#interviewer-question').setValue('请拷问我的结算设计');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(api.ask).toHaveBeenCalledWith('owner', 'exercise', '请拷问我的结算设计', expect.any(String), 'credential', expect.any(AbortSignal), { mode: 'DEFENSE' });
  expect(wrapper.text()).toContain('代码拷问');
});
