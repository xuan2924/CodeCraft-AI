<script setup lang="ts">
import { onMounted, onScopeDispose, ref, watch } from 'vue';
import { editor, Uri } from 'monaco-editor/editor';
import 'monaco-editor/languages/definitions/java/register';
import EditorWorker from 'monaco-editor/editor/editor.worker?worker';

const props = defineProps<{ path: string; before: string; after: string }>();
const host = ref<globalThis.HTMLElement>();
let instance: editor.IStandaloneDiffEditor | undefined;
let original: editor.ITextModel | undefined;
let modified: editor.ITextModel | undefined;
let resize: globalThis.ResizeObserver | undefined;
const scope = globalThis.crypto.randomUUID();
globalThis.MonacoEnvironment = { getWorker: () => new EditorWorker() };
function sync() {
  if (!instance) return;
  instance.setModel(null); original?.dispose(); modified?.dispose();
  const language = props.path.endsWith('.java') ? 'java' : 'plaintext';
  original = editor.createModel(props.before, language, Uri.parse(`inmemory://${scope}/before/${props.path}`));
  modified = editor.createModel(props.after, language, Uri.parse(`inmemory://${scope}/after/${props.path}`));
  instance.setModel({ original, modified });
}
onMounted(() => {
  if (!host.value) return;
  instance = editor.createDiffEditor(host.value, { readOnly: true, originalEditable: false, originalAriaLabel: '提案修改前', modifiedAriaLabel: '提案修改后',
    minimap: { enabled: false }, scrollBeyondLastLine: false, maxComputationTime: 1000, automaticLayout: false });
  resize = new globalThis.ResizeObserver(() => instance?.layout()); resize.observe(host.value); sync();
});
watch(() => [props.path, props.before, props.after], sync);
onScopeDispose(() => { resize?.disconnect(); instance?.dispose(); original?.dispose(); modified?.dispose(); });
</script>
<template>
  <div
    ref="host"
    class="code-diff"
  />
</template>
<style scoped>
.code-diff { height: 420px; width: 100%; min-width: 0; border: 1px solid var(--line); }
</style>
