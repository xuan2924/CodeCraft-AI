import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it } from 'vitest';
import { defineComponent, ref } from 'vue';
import UiPopover from './Popover.vue';

afterEach(() => { document.body.innerHTML = ''; });

describe('Popover', () => {
  it('opens from trigger, exposes content, and closes on Escape', async () => {
    const Host = defineComponent({
      components: { UiPopover },
      setup: () => ({ open: ref(false) }),
      template: `
        <UiPopover v-model:open="open" label="快捷菜单">
          <template #trigger><button type="button">打开浮层</button></template>
          <div>浮层内容</div>
        </UiPopover>
      `,
    });
    const wrapper = mount(Host, { attachTo: document.body });
    await wrapper.get('button').trigger('click');
    await flushPromises();
    expect(document.body.textContent).toContain('浮层内容');
    document.activeElement?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await flushPromises();
    expect(wrapper.vm.open).toBe(false);
    wrapper.unmount();
  });
});
