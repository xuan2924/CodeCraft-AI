import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import CodeEditor from './CodeEditor.vue';

const resources = vi.hoisted(() => {
  interface Model { getValue: () => string; setValue: (value: string) => void; dispose: ReturnType<typeof vi.fn> }
  const models: Model[] = [];
  const change = { dispose: vi.fn() };
  let selected: Model | null = null;
  const instance = { getModel: () => selected, setModel: (model: Model | null) => { selected = model; },
    getValue: () => selected?.getValue() ?? '', updateOptions: vi.fn(), layout: vi.fn(), dispose: vi.fn(),
    onDidChangeModelContent: vi.fn(() => change) };
  return { models, instance, change };
});
vi.mock('monaco-editor/editor', () => ({
  Uri: { parse: (value: string) => value },
  editor: {
    defineTheme: vi.fn(),
    create: () => resources.instance,
    createModel: (initial: string) => {
      let value = initial;
      const model = { getValue: () => value, setValue: (next: string) => { value = next; }, dispose: vi.fn() };
      resources.models.push(model);
      return model;
    },
  },
}));
vi.mock('monaco-editor/languages/definitions/java/register', () => ({}));
vi.mock('monaco-editor/editor/editor.worker?worker', () => ({ default: class {} }));
let page: VueWrapper | undefined;
const disconnect = vi.fn();
beforeEach(() => {
  resources.models.length = 0;
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect = disconnect; });
});
afterEach(() => { page?.unmount(); page = undefined; vi.unstubAllGlobals(); vi.clearAllMocks(); });

it('disposes the closed file model and recreates it from the preserved buffer on reopen', async () => {
  page = mount(CodeEditor, { props: { path: 'Main.java', modelValue: 'unsaved buffer' } });
  const original = resources.models[0]!;
  await page.setProps({ path: '', modelValue: '' });
  expect(original.dispose).toHaveBeenCalledTimes(1);
  expect(resources.instance.getModel()).toBeNull();
  expect(resources.models).toHaveLength(1);
  await page.setProps({ path: 'Main.java', modelValue: 'unsaved buffer' });
  expect(resources.models).toHaveLength(2);
  expect(resources.instance.getValue()).toBe('unsaved buffer');
  page.unmount(); page = undefined;
  expect(original.dispose).toHaveBeenCalledTimes(1);
  expect(resources.models[1]!.dispose).toHaveBeenCalledTimes(1);
});

it('retains file models while switching and releases all external resources on disposal', async () => {
  page = mount(CodeEditor, { props: { path: 'Main.java', modelValue: 'first draft' } });
  const host = page.get('[data-testid="source"]').element;
  await page.setProps({ path: 'Other.java', modelValue: 'second draft' });
  await page.setProps({ path: 'Main.java', modelValue: 'first draft' });
  expect(resources.models).toHaveLength(2);
  expect(resources.instance.getValue()).toBe('first draft');
  host.dispatchEvent(new KeyboardEvent('keydown', { key: 's', ctrlKey: true, cancelable: true }));
  expect(page.emitted('save')).toHaveLength(1);
  page.unmount();
  const afterDisposal = new KeyboardEvent('keydown', { key: 's', ctrlKey: true, cancelable: true });
  host.dispatchEvent(afterDisposal);
  expect(afterDisposal.defaultPrevented).toBe(false);
  expect(resources.change.dispose).toHaveBeenCalledTimes(1);
  expect(resources.instance.dispose).toHaveBeenCalledTimes(1);
  expect(disconnect).toHaveBeenCalledTimes(1);
  for (const model of resources.models) expect(model.dispose).toHaveBeenCalledTimes(1);
  page = undefined;
});
