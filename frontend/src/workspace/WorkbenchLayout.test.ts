import { mount } from '@vue/test-utils';
import { defineComponent, onMounted, onUnmounted } from 'vue';
import { expect, it, vi } from 'vitest';
import WorkbenchLayout from './WorkbenchLayout.vue';
it('focus only hides the question pane, retaining mounted editor and visible approval/stop controls', async () => {
  const mounted = vi.fn(); const disposed = vi.fn();
  const Editor = defineComponent({ setup() { onMounted(mounted); onUnmounted(disposed); return {}; }, template: '<textarea aria-label="编辑器草稿">unsaved draft</textarea>' });
  const wrapper = mount(WorkbenchLayout, { props: { leftHidden: false }, slots: { files: '<p>题目</p>', editor: Editor, assistant: '<button>停止当前 AI 任务</button><button>审批变更</button>', commands: '<button>查看测试</button>' } });
  await wrapper.setProps({ leftHidden: true });
  expect(wrapper.get('textarea').element.value).toBe('unsaved draft'); expect(mounted).toHaveBeenCalledTimes(1); expect(disposed).not.toHaveBeenCalled();
  expect(wrapper.findAll('button').find(button => button.text() === '审批变更')!.isVisible()).toBe(true);
  expect(wrapper.findAll('button').find(button => button.text() === '停止当前 AI 任务')!.isVisible()).toBe(true);
  await wrapper.setProps({ leftHidden: false });
  expect(mounted).toHaveBeenCalledTimes(1); wrapper.unmount(); expect(disposed).toHaveBeenCalledTimes(1);
});
