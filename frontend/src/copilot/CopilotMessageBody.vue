<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { marked } from 'marked';

const props = defineProps<{ text: string; thinking?: string }>();
const blocks = computed(() => marked.lexer(props.text).map((token) => token.type === 'code'
  ? { code: true, text: token.text, language: token.lang ?? '' }
  : { code: false, text: token.raw, language: '' }));
const copyStatus = ref('');
const copying = ref(false);
let generation = 0;
watch(() => props.text, () => { generation++; copyStatus.value = ''; copying.value = false; }, { flush: 'sync' });
onBeforeUnmount(() => { generation++; });
async function copy(text: string) {
  if (copying.value) return;
  const current = generation;
  copying.value = true;
  try {
    await globalThis.navigator.clipboard.writeText(text);
    if (current === generation) copyStatus.value = '已复制代码';
  } catch {
    if (current === generation) copyStatus.value = '复制失败，请选中代码手动复制。';
  } finally { if (current === generation) copying.value = false; }
}
</script>

<template>
  <div class="message-body">
    <details v-if="thinking">
      <summary>思考</summary>
      <pre class="thinking">{{ thinking }}</pre>
    </details>
    <template
      v-for="(block, index) in blocks"
      :key="index"
    >
      <div
        v-if="block.code"
        class="code-block"
        aria-label="代码块"
      >
        <header>
          <span>{{ block.language || '代码' }}</span>
          <button
            type="button"
            :disabled="copying"
            @click="copy(block.text)"
          >
            复制代码
          </button>
        </header>
        <pre><code>{{ block.text }}</code></pre>
      </div>
      <pre
        v-else
        class="prose"
      >{{ block.text }}</pre>
    </template>
    <p
      v-if="copyStatus"
      role="status"
    >
      {{ copyStatus }}
    </p>
  </div>
</template>

<style scoped>
.message-body { min-width: 0; }
summary { cursor: pointer; }
pre { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.prose { font-family: inherit; }
.thinking { padding: 0.75rem 0; }
.code-block { margin: 0.75rem 0; border: 1px solid var(--line); border-radius: 4px; overflow: hidden; }
.code-block header { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: 0.4rem 0.75rem; border-bottom: 1px solid var(--line); }
.code-block pre { padding: 0.75rem; overflow-x: auto; white-space: pre; }
</style>
