import { mount, flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import ConnectionForm from './ConnectionForm.vue';
import * as api from './api';

vi.mock('./api', async (original) => ({ ...await original<typeof import('./api')>(), saveConnection: vi.fn() }));
const connection: api.Connection = { id: '10000000-0000-0000-0000-000000000001', name: 'Fixture', baseUrl: 'https://example.test/v1',
  protocol: 'OPENAI_CHAT', preset: 'CUSTOM', version: '2', configurationVersion: '1', keyConfigured: true, deleted: false };
const mounted: ReturnType<typeof mount>[] = [];
function form(value: api.Connection | null = null) {
  const wrapper = mount(ConnectionForm, { props: { owner: 'owner-a', connection: value } });
  mounted.push(wrapper); return wrapper;
}
beforeEach(() => { vi.mocked(api.saveConnection).mockReset(); });
afterEach(() => { for (const wrapper of mounted.splice(0)) wrapper.unmount(); });

it('requires explicit save and clears Key while retaining a frozen original payload for manual retry', async () => {
  vi.mocked(api.saveConnection).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(connection);
  const wrapper = form();
  await wrapper.get('#connection-new-name').setValue('Fixture');
  await wrapper.get('#connection-new-url').setValue('https://example.test/v1');
  await wrapper.get('#connection-new-key').setValue('fixture-secret');
  expect(api.saveConnection).not.toHaveBeenCalled();
  await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(wrapper.get<HTMLInputElement>('#connection-new-key').element.value).toBe('');
  expect(wrapper.text()).toContain('提交结果尚未确认');
  const first = vi.mocked(api.saveConnection).mock.calls[0];
  expect(first?.[1]).toMatchObject({ apiKey: 'fixture-secret', expectedVersion: null });
  expect(Object.isFrozen(first?.[1])).toBe(true);
  await wrapper.get('form').trigger('submit');
  expect(api.saveConnection).toHaveBeenCalledTimes(1);
  const retry = wrapper.findAll('button').find((button) => button.text() === '用原请求重试');
  if (!retry) throw new Error('Missing explicit retry');
  await retry.trigger('click'); await flushPromises();
  expect(vi.mocked(api.saveConnection).mock.calls[1]?.slice(0, 3)).toEqual(first?.slice(0, 3));
  expect(wrapper.emitted('saved')?.[0]?.[0]).toEqual(connection);
});
it('keeps the original optimistic version and draft across same-resource refetches; blank Key means retain', async () => {
  vi.mocked(api.saveConnection).mockResolvedValue(connection);
  const wrapper = form(connection);
  await wrapper.get(`#connection-${connection.id}-name`).setValue('My draft');
  await wrapper.setProps({ connection: { ...connection, name: 'External rename', version: '3' } });
  await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(vi.mocked(api.saveConnection).mock.calls[0]?.[1]).toMatchObject({ name: 'My draft', expectedVersion: '2', apiKey: null });
});
it('clears the Key and prevents old-owner completion after scope changes', async () => {
  let finish: (value: api.Connection) => void = () => {};
  vi.mocked(api.saveConnection).mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
  const wrapper = form(connection);
  await wrapper.get(`#connection-${connection.id}-key`).setValue('fixture-secret');
  await wrapper.get('form').trigger('submit');
  const signal = vi.mocked(api.saveConnection).mock.calls[0]?.[3];
  await wrapper.setProps({ owner: 'owner-b', connection: null });
  finish(connection); await flushPromises();
  expect(signal?.aborted).toBe(true);
  expect(wrapper.emitted('saved')).toBeUndefined();
  expect(wrapper.get<HTMLInputElement>('#connection-new-key').element.value).toBe('');
  expect(wrapper.get<HTMLInputElement>('#connection-new-name').element.value).toBe('');
});
it('discloses the destination operator risk before collecting credentials', () => {
  const wrapper = form();
  expect(wrapper.text()).toContain('运营方会收到 Key 和请求内容');
  expect(wrapper.text()).toContain('同名模型不保证实际模型或价格相同');
  expect(api.saveConnection).not.toHaveBeenCalled();
});
it('rejects non-HTTPS locally without making a credential request', async () => {
  const wrapper = form(connection);
  await wrapper.get(`#connection-${connection.id}-url`).setValue('http://example.test');
  await wrapper.get('form').trigger('submit');
  expect(api.saveConnection).not.toHaveBeenCalled();
  expect(wrapper.get('[role="alert"]').text()).toContain('HTTPS');
});
