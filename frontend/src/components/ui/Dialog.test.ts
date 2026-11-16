import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it } from 'vitest';
import { defineComponent, ref } from 'vue';
import UiDialog from './Dialog.vue';

afterEach(() => { document.body.innerHTML = ''; });
describe('Dialog', () => {
  it('exposes its title and description, closes on Escape and returns focus to its trigger', async () => {
    const Host = defineComponent({ components: { UiDialog }, setup: () => ({ open: ref(false) }),
      template: '<UiDialog v-model:open="open" title="确认删除" description="此操作不会删除其他演练。"><template #trigger><button>打开确认</button></template><button>确认</button></UiDialog>' });
    const wrapper = mount(Host, { attachTo: document.body });
    const trigger = wrapper.get('button');
    (trigger.element as HTMLButtonElement).focus();
    await trigger.trigger('click');
    await flushPromises();
    const dialog = document.querySelector('[role="dialog"]');
    expect(dialog?.textContent).toContain('确认删除');
    expect(document.getElementById(dialog!.getAttribute('aria-describedby')!)?.textContent).toContain('不会删除其他演练');
    document.activeElement?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await flushPromises();
    expect(document.querySelector('[role="dialog"]')).toBeNull();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(document.activeElement).toBe(trigger.element);
    wrapper.unmount();
  });
});
