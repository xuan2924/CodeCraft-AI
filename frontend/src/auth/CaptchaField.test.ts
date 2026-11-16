import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import CaptchaField from './CaptchaField.vue';
import * as api from './api';
vi.mock('./api', async (original) => ({ ...await original<typeof import('./api')>(), captcha: vi.fn() }));
const challenge = { id: 'a'.repeat(43), image: 'data:image/png;base64,AAAA', expiresAt: '2026-01-01T00:02:00Z' };
afterEach(() => { vi.useRealTimers(); vi.resetAllMocks(); });

it('invalidates input when refreshed or expired without exposing the answer in alt text', async () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-01-01T00:00:00Z'));
  vi.mocked(api.captcha).mockResolvedValue(challenge);
  const page = mount(CaptchaField, { props: { disabled: false } });
  await flushPromises();
  await page.get('input').setValue('ABCDE');
  expect(page.emitted('change')?.at(-1)).toEqual([challenge.id, 'ABCDE']);
  expect(page.get('img').attributes('alt')).not.toContain('ABCDE');
  await page.get('button').trigger('click');
  expect(page.emitted('change')?.at(-1)).toEqual(['', '']);
  await flushPromises();
  await vi.advanceTimersByTimeAsync(120000);
  expect(page.text()).toContain('验证码已过期');
  expect(page.find('img').exists()).toBe(false);
  page.unmount();
});

it('blocks overlapping refreshes and cancels/ignores responses after disposal', async () => {
  let resolve!: (value: api.Captcha) => void;
  vi.mocked(api.captcha).mockImplementation(() => new Promise((done) => { resolve = done; }));
  const changed = vi.fn();
  const page = mount(CaptchaField, { props: { disabled: false, onChange: changed } });
  await page.get('button').trigger('click');
  expect(api.captcha).toHaveBeenCalledTimes(1);
  const signal = vi.mocked(api.captcha).mock.calls[0]![0];
  const count = changed.mock.calls.length;
  page.unmount();
  expect(signal.aborted).toBe(true);
  resolve(challenge);
  await flushPromises();
  expect(changed).toHaveBeenCalledTimes(count);
});

it('loads a challenge after a password-change transition finishes', async () => {
  vi.mocked(api.captcha).mockResolvedValue({ ...challenge, expiresAt: new Date(Date.now() + 120000).toISOString() });
  const page = mount(CaptchaField, { props: { disabled: true } });
  expect(api.captcha).not.toHaveBeenCalled();
  await page.setProps({ disabled: false });
  await flushPromises();
  expect(page.find('img').exists()).toBe(true);
  page.unmount();
});

it('shows request failures and supports explicit keyboard-operable retry', async () => {
  vi.mocked(api.captcha).mockRejectedValueOnce(new api.ApiError(429, 'RATE_LIMITED'));
  const page = mount(CaptchaField, { props: { disabled: false } });
  await flushPromises();
  expect(page.get('[role="alert"]').text()).toContain('请求过于频繁');
  expect(page.get('input').attributes('disabled')).toBeDefined();
  expect(page.get('button').attributes('disabled')).toBeUndefined();
  page.unmount();
});
