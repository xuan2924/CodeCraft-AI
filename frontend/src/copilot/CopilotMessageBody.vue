<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { marked, type Token, type Tokens } from 'marked';

const props = defineProps<{ text: string; thinking?: string }>();
interface DisplayBlock { code: boolean; text: string; language: string }
function displayBlocks(tokens: Token[]): DisplayBlock[] {
  return tokens.flatMap((token): DisplayBlock[] => {
    // The lexer has no custom extensions; these tags identify built-in block tokens.
    if (token.type === 'blockquote') return displayBlocks((token as Tokens.Blockquote).tokens);
    if (token.type === 'list') {
      const list = token as Tokens.List;
      return list.items.flatMap((item, index) => [
        { code: false, text: list.ordered ? `${Number(list.start) + index}. ` : '• ', language: '' },
        ...displayBlocks(item.tokens),
      ]);
    }
    return [token.type === 'code' ? { code: true, text: token.text, language: token.lang ?? '' }
      : { code: false, text: token.raw, language: '' }];
  });
}
const blocks = computed(() => displayBlocks(marked.lexer(props.text)));
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
.message-body { min-width: 0; font-size: 13px; line-height: 1.6; }
summary { cursor: pointer; font-size: 12px; color: var(--oc-fg-muted); }
pre { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.prose { font-family: inherit; }
.thinking {
  padding: 10px 12px;
  margin-top: 6px;
  border-radius: 8px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  font-size: 12px;
  color: var(--oc-fg-muted);
}
.code-block {
  margin: 8px 0;
  border: 1px solid var(--oc-border-strong);
  border-radius: 8px;
  background: var(--oc-bg-input);
  overflow: hidden;
}
.code-block header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 6px 12px;
  border-bottom: 1px solid var(--oc-border);
  background: rgba(255, 255, 255, 0.025);
  font-size: 11.5px;
  color: var(--oc-fg-muted);
}
.code-block header button {
  padding: 3px 9px;
  border-radius: 6px;
  border: 1px solid var(--oc-border-strong);
  background: var(--oc-surface-2);
  color: var(--oc-fg);
  font-size: 11.5px;
  cursor: pointer;
}
.code-block header button:hover {
  background: var(--oc-surface-hover);
}
.code-block pre { padding: 10px 12px; overflow-x: auto; white-space: pre; font-size: 12.5px; }
</style>
