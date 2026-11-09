<script setup lang="ts">
import { onMounted, onScopeDispose, ref, watch } from 'vue';
import { editor, Uri } from 'monaco-editor/editor';
import 'monaco-editor/languages/definitions/java/register';
import EditorWorker from 'monaco-editor/editor/editor.worker?worker';

const props = defineProps<{ path: string; modelValue: string; readonly?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [value: string]; save: [] }>();
const host = ref<globalThis.HTMLElement>();
let instance: editor.IStandaloneCodeEditor | undefined;
let change: { dispose(): void } | undefined;
let resize: globalThis.ResizeObserver | undefined;
const models = new Map<string, editor.ITextModel>();
const scope = globalThis.crypto.randomUUID();
let applying = false;
globalThis.MonacoEnvironment = { getWorker: () => new EditorWorker() };
function sync() {
  if (!instance) return;
  applying = true;
  try {
    if (!props.path) {
      const closed = instance.getModel();
      instance.setModel(null);
      if (closed) {
        closed.dispose();
        for (const [path, model] of models) if (model === closed) models.delete(path);
      }
      instance.updateOptions({ readOnly: true });
      return;
    }
    let model = models.get(props.path);
    if (!model) {
      model = editor.createModel(props.modelValue, props.path.endsWith('.java') ? 'java' : 'plaintext', Uri.parse(`inmemory://${scope}/${props.path || 'empty'}`));
      models.set(props.path, model);
    }
    if (instance.getModel() !== model) instance.setModel(model);
    if (model.getValue() !== props.modelValue) model.setValue(props.modelValue);
    instance.updateOptions({ readOnly: props.readonly ?? false });
  } finally { applying = false; }
}
function shortcut(event: globalThis.KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); emit('save'); }
}
onMounted(() => {
  if (!host.value) return;
  instance = editor.create(host.value, { model: null, minimap: { enabled: false }, ariaLabel: '文件编辑器',
    scrollBeyondLastLine: false, fontSize: 14, wordWrap: 'on', tabSize: 4, automaticLayout: false });
  change = instance.onDidChangeModelContent(() => { if (!applying) emit('update:modelValue', instance!.getValue()); });
  resize = new globalThis.ResizeObserver(() => instance?.layout());
  resize.observe(host.value);
  host.value.addEventListener('keydown', shortcut, true);
  sync();
});
watch(() => [props.path, props.modelValue, props.readonly], sync);
onScopeDispose(() => {
  host.value?.removeEventListener('keydown', shortcut, true);
  resize?.disconnect(); change?.dispose(); instance?.dispose();
  for (const model of models.values()) model.dispose();
  models.clear();
});
</script>

<template>
  <div
    ref="host"
    class="code-editor"
    data-testid="source"
  />
</template>

<style scoped>
.code-editor { height: 56vh; min-height: 280px; width: 100%; min-width: 0; border: 1px solid var(--editor-border, #d9d9d9); }
</style>
