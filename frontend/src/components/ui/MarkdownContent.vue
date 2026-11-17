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
  line-height: 1.75;
  font-size: 13.5px;
  overflow-wrap: break-word;
  word-break: normal;
  min-width: 0;
  color: var(--oc-fg);
}
.markdown-content :deep(> :first-child) { margin-top: 0; }
.markdown-content :deep(h1) { font-size: 1.25rem; font-weight: 600; margin: 1.3em 0 0.55em; }
.markdown-content :deep(h2) { font-size: 1.05rem; font-weight: 600; margin: 1.4em 0 0.5em; padding-bottom: 6px; border-bottom: 1px solid var(--oc-border); }
.markdown-content :deep(h3) { font-size: 0.95rem; font-weight: 600; margin: 1.15em 0 0.45em; }
.markdown-content :deep(p),
.markdown-content :deep(ul),
.markdown-content :deep(ol) { margin: 0.7em 0; color: rgba(244, 244, 245, 0.88); }
.markdown-content :deep(ul),
.markdown-content :deep(ol) { padding-left: 1.35em; }
.markdown-content :deep(li) { margin: 0.3em 0; }
.markdown-content :deep(code) {
  font-family: var(--oc-font-mono);
  font-size: 0.85em;
  padding: 0.125rem 0.375rem;
  border-radius: 0.25rem;
  background: rgba(255, 255, 255, 0.08);
  border: 0;
  color: #e4e4e7;
  overflow-wrap: break-word;
  word-break: normal;
}
.markdown-content :deep(pre) {
  padding: 12px 14px;
  overflow-x: auto;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  border-radius: 8px;
  line-height: 1.6;
}
.markdown-content :deep(pre code) {
  background: none;
  border: 0;
  padding: 0;
  color: var(--oc-fg);
  overflow-wrap: normal;
  word-break: normal;
}
.markdown-content :deep(blockquote) {
  margin: 12px 0;
  border-left: 2px solid var(--oc-border-strong);
  padding: 2px 0 2px 12px;
  color: var(--oc-fg-muted);
}
.markdown-content :deep(table) { display: block; overflow-x: auto; border-collapse: collapse; margin: 12px 0; }
.markdown-content :deep(th),
.markdown-content :deep(td) { padding: 7px 11px; border: 1px solid var(--oc-border); font-size: 12.5px; }
.markdown-content :deep(th) { background: var(--oc-surface-2); color: var(--oc-fg-muted); font-weight: 550; }
</style>
