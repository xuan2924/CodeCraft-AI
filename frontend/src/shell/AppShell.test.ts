import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';
import { describe, expect, it } from 'vitest';
import AppShell from './AppShell.vue';
import type { Identity } from '../auth/api';

const user: Identity = { id: 'user-1', username: '受邀用户', role: 'USER', passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' };
async function setup(identity: Identity | null = user) {
  const router = createRouter({ history: createMemoryHistory(), routes: ['status', 'account', 'workspace', 'models', 'skills'].map((name) => ({ name, path: `/${name}`, component: { template: '<p>页面</p>' } })) });
  await router.push('/workspace');
  const wrapper = mount(AppShell, { props: { identity, pending: false, failed: false }, slots: { default: '<p>业务内容</p>' }, global: { plugins: [router, createPinia()] } });
  return { wrapper, router };
}

describe('application navigation', () => {
  it('shows only role-appropriate destinations and resets expanded navigation when identity changes', async () => {
    const { wrapper } = await setup();
    expect(wrapper.text()).toContain('个人模型');
    expect(wrapper.text()).not.toContain('账号管理');
    const toggle = wrapper.get('button[aria-controls="application-navigation"]');
    await toggle.trigger('click');
    expect(toggle.attributes('aria-expanded')).toBe('true');
    await wrapper.setProps({ identity: { ...user, id: 'admin-1', role: 'ADMIN' } });
    expect(toggle.attributes('aria-expanded')).toBe('false');
    expect(wrapper.text()).toContain('账号管理');
    expect(wrapper.text()).not.toContain('个人模型');
    expect(wrapper.text()).not.toContain('演练工作区');
    wrapper.unmount();
  });
  it('keeps failure distinct from a signed-out session and closes the menu after navigation', async () => {
    const { wrapper, router } = await setup();
    const toggle = wrapper.get('button[aria-controls="application-navigation"]');
    await toggle.trigger('click');
    await router.push('/models');
    await flushPromises();
    expect(toggle.attributes('aria-expanded')).toBe('false');
    await wrapper.setProps({ identity: null, failed: true });
    expect(wrapper.get('[role="alert"]').text()).toContain('无法确认登录状态');
    expect(wrapper.text()).not.toContain('个人模型');
    await wrapper.get('[data-testid="retry-session"]').trigger('click');
    expect(wrapper.emitted('retry')).toHaveLength(1);
    wrapper.unmount();
  });
});
