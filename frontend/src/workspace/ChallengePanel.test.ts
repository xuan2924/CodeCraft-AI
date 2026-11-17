import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { beforeEach, expect, it, vi } from 'vitest';
import ChallengePanel from './ChallengePanel.vue';
import * as api from './challengeApi';

vi.mock('./challengeApi', () => ({ enter: vi.fn() }));
const state: api.Challenge = { available: true, entered: false, eligible: false, reason: 'CURRENT_BASE_PASS_AND_IDLE_REQUIRED', enteredRevision: null, baseTestId: null, publishedChallenge: null };
const props = { owner: 'owner', exerciseId: 'exercise', credential: 'control', disabled: false, state };
function render(value = props) {
  return mount(ChallengePanel, { props: value, global: { plugins: [[VueQueryPlugin, { queryClient: new QueryClient() }]] } });
}
beforeEach(() => vi.clearAllMocks());
it('does not expose entry for a base-only problem', () => {
  const wrapper = render({ ...props, state: { ...state, available: false } });
  expect(wrapper.find('button').exists()).toBe(false); wrapper.unmount();
});
it('requires a current passing revision and explicit confirmation', async () => {
  const wrapper = render();
  expect(wrapper.get('button').attributes('disabled')).toBeDefined();
  expect(wrapper.text()).toContain('当前保存版本基础验收通过');
  await wrapper.setProps({ state: { ...state, eligible: true } });
  vi.mocked(api.enter).mockResolvedValue({ ...state, entered: true });
  await wrapper.get('button').trigger('click'); await flushPromises();
  expect(api.enter).toHaveBeenCalledWith('owner', 'exercise', expect.any(String), 'control', expect.any(AbortSignal));
  wrapper.unmount();
});
it('retries an unknown command with its original key rather than entering twice', async () => {
  vi.mocked(api.enter).mockRejectedValue(new Error('network'));
  const wrapper = render({ ...props, state: { ...state, eligible: true } });
  await wrapper.get('button').trigger('click'); await flushPromises();
  await wrapper.findAll('button').find((button) => button.text() === '核对原挑战请求')!.trigger('click'); await flushPromises();
  expect(vi.mocked(api.enter).mock.calls[1]?.slice(0, 4)).toEqual(vi.mocked(api.enter).mock.calls[0]?.slice(0, 4));
  wrapper.unmount();
});
it('only renders the server-confirmed published challenge and its immutable revision', () => {
  const wrapper = render({ ...props, state: { ...state, entered: true, enteredRevision: '7', baseTestId: 'base-test',
    publishedChallenge: { requirements: '<script>challenge text</script>', acceptanceFiles: { 'Test.java': 'class Test {}' } } } });
  expect(wrapper.text()).toContain('基线修订 r7');
  expect(wrapper.text()).toContain('<script>challenge text</script>');
  expect(wrapper.find('script').exists()).toBe(false);
  expect(wrapper.find('button').exists()).toBe(false);
  wrapper.unmount();
});
