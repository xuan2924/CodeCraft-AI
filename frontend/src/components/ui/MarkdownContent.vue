<script setup lang="ts">
import { computed } from 'vue';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
const props = defineProps<{ source: string }>();
// No raw HTML, remote images, inline styles or embedded content from problem authors.
const html = computed(() => DOMPurify.sanitize(marked.parse(props.source, { async: false, breaks: false }), {
  USE_PROFILES: { html: true }, FORBID_TAGS: ['img', 'style', 'form', 'input', 'button'], FORBID_ATTR: ['style', 'id'],
}));
</script>
<template>
  <!-- Sanitized at the only HTML boundary above. -->
  <!-- eslint-disable-next-line vue/no-v-html -->
  <div
    class="markdown-content"
    v-html="html"
  />
</template>
<style scoped>
.markdown-content {
  line-height: 1.8;
  font-size: 14px;
  overflow-wrap: break-word;
  min-width: 0;
  color: var(--oc-fg);
}
.markdown-content :deep(> :first-child) { margin-top: 0; }
.markdown-content :deep(h1) { font-size: 1.35rem; font-weight: 650; margin: 1.4em 0 0.6em; }
.markdown-content :deep(h2) { font-size: 1.1rem; font-weight: 600; margin: 1.6em 0 0.55em; padding-bottom: 6px; border-bottom: 1px solid var(--oc-border); }
.markdown-content :deep(h3) { font-size: 0.98rem; font-weight: 600; margin: 1.2em 0 0.45em; }
.markdown-content :deep(p),
.markdown-content :deep(ul),
.markdown-content :deep(ol) { margin: 0.75em 0; color: rgba(243, 244, 242, 0.88); }
.markdown-content :deep(ul),
.markdown-content :deep(ol) { padding-left: 1.35em; }
.markdown-content :deep(li) { margin: 0.35em 0; }
.markdown-content :deep(code) {
  font-family: var(--oc-font-mono);
  font-size: 0.87em;
  padding: 2px 6px;
  border-radius: 5px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  color: var(--oc-accent);
  overflow-wrap: anywhere;
}
.markdown-content :deep(pre) {
  padding: 14px 16px;
  overflow: auto;
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
  border-radius: 10px;
  line-height: 1.65;
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.3);
}
.markdown-content :deep(pre code) {
  background: none;
  border: 0;
  padding: 0;
  color: var(--oc-fg);
  overflow-wrap: normal;
}
.markdown-content :deep(blockquote) {
  margin: 14px 0;
  border-left: 3px solid var(--oc-accent);
  padding: 4px 0 4px 14px;
  color: var(--oc-fg-muted);
  background: rgba(160, 196, 165, 0.04);
  border-radius: 0 6px 6px 0;
}
.markdown-content :deep(table) { display: block; overflow: auto; border-collapse: collapse; margin: 14px 0; }
.markdown-content :deep(th),
.markdown-content :deep(td) { padding: 8px 12px; border: 1px solid var(--oc-border); font-size: 13px; }
.markdown-content :deep(th) { background: var(--oc-surface-2); color: var(--oc-fg-muted); font-weight: 550; }
</style>
