<script setup lang="ts">
import { computed, defineComponent, h, onBeforeUnmount, ref, watch, type PropType, type VNodeChild } from 'vue';
import { marked, type Token, type Tokens } from 'marked';

const props = defineProps<{ text: string; thinking?: string }>();
const tokens = computed(() => marked.lexer(props.text));
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

function safeHref(href: string | null | undefined): string | null {
  if (!href) return null;
  try {
    const parsed = new globalThis.URL(href);
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.toString() : null;
  } catch {
    return null;
  }
}

function renderInline(items: Token[] | undefined): VNodeChild[] {
  if (!items || !items.length) return [];
  return items.map((token, idx): VNodeChild => {
    switch (token.type) {
      case 'strong':
        return h('strong', { key: idx }, renderInline((token as Tokens.Strong).tokens));
      case 'em':
        return h('em', { key: idx }, renderInline((token as Tokens.Em).tokens));
      case 'del':
        return h('del', { key: idx }, renderInline((token as Tokens.Del).tokens));
      case 'codespan':
        return h('code', { key: idx, class: 'inline-code' }, (token as Tokens.Codespan).text);
      case 'br':
        return h('br', { key: idx });
      case 'escape':
        return (token as Tokens.Escape).text;
      case 'link': {
        const link = token as Tokens.Link;
        const url = safeHref(link.href);
        const label = link.tokens?.length ? renderInline(link.tokens) : link.text;
        return url
          ? h('a', { key: idx, href: url, target: '_blank', rel: 'noopener noreferrer', class: 'md-link' }, label)
          : h('span', { key: idx }, label);
      }
      case 'text': {
        const textToken = token as Tokens.Text;
        return textToken.tokens?.length ? h('span', { key: idx }, renderInline(textToken.tokens)) : textToken.text;
      }
      case 'html':
      default:
        return token.raw;
    }
  });
}

function renderCodeBlock(token: Tokens.Code, key: number): VNodeChild {
  return h('div', { key, class: 'code-block', 'aria-label': '代码块' }, [
    h('header', [
      h('span', token.lang || '代码'),
      h('button', {
        type: 'button',
        disabled: copying.value,
        onClick: () => void copy(token.text),
      }, '复制代码'),
    ]),
    h('pre', [h('code', token.text)]),
  ]);
}

function renderBlocks(items: Token[]): VNodeChild[] {
  return items.flatMap((token, idx): VNodeChild[] => {
    switch (token.type) {
      case 'space':
        return [];
      case 'code':
        return [renderCodeBlock(token as Tokens.Code, idx)];
      case 'heading': {
        const heading = token as Tokens.Heading;
        const depth = Math.min(6, Math.max(1, heading.depth));
        return [h(`h${depth}`, { key: idx, class: `md-h md-h${depth}` }, renderInline(heading.tokens))];
      }
      case 'paragraph':
        return [h('p', { key: idx, class: 'md-p' }, renderInline((token as Tokens.Paragraph).tokens))];
      case 'blockquote':
        return [h('blockquote', { key: idx, class: 'md-quote' }, renderBlocks((token as Tokens.Blockquote).tokens))];
      case 'list': {
        const list = token as Tokens.List;
        const tag = list.ordered ? 'ol' : 'ul';
        const attrs: Record<string, unknown> = { key: idx, class: 'md-list' };
        if (list.ordered && list.start !== '' && Number(list.start) !== 1) attrs.start = Number(list.start);
        return [h(tag, attrs, list.items.map((item, itemIdx) =>
          h('li', { key: itemIdx }, renderBlocks(item.tokens)),
        ))];
      }
      case 'table': {
        const table = token as Tokens.Table;
        return [h('div', { key: idx, class: 'md-table-wrap' }, [
          h('table', { class: 'md-table' }, [
            h('thead', [
              h('tr', table.header.map((cell, cellIdx) =>
                h('th', { key: cellIdx, style: table.align[cellIdx] ? { textAlign: table.align[cellIdx] } : undefined }, renderInline(cell.tokens)),
              )),
            ]),
            h('tbody', table.rows.map((row, rowIdx) =>
              h('tr', { key: rowIdx }, row.map((cell, cellIdx) =>
                h('td', { key: cellIdx, style: table.align[cellIdx] ? { textAlign: table.align[cellIdx] } : undefined }, renderInline(cell.tokens)),
              )),
            )),
          ]),
        ])];
      }
      case 'hr':
        return [h('hr', { key: idx, class: 'md-hr' })];
      case 'text': {
        const textToken = token as Tokens.Text;
        return [h('p', { key: idx, class: 'md-p' }, textToken.tokens?.length ? renderInline(textToken.tokens) : textToken.text)];
      }
      case 'html':
      default:
        return [h('pre', { key: idx, class: 'prose' }, token.raw)];
    }
  });
}

const MarkdownContent = defineComponent({
  name: 'MarkdownContent',
  props: {
    items: { type: Array as PropType<Token[]>, required: true },
  },
  setup(componentProps) {
    return () => h('div', { class: 'md-root' }, renderBlocks(componentProps.items));
  },
});
</script>

<template>
  <div class="message-body">
    <details v-if="thinking">
      <summary>思考</summary>
      <pre class="thinking">{{ thinking }}</pre>
    </details>
    <MarkdownContent :items="tokens" />
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
.md-root { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.md-root :deep(.md-p) { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.md-root :deep(.md-h) { margin: 4px 0 2px; font-weight: 600; line-height: 1.35; }
.md-root :deep(.md-h1) { font-size: 15.5px; }
.md-root :deep(.md-h2) { font-size: 14.5px; }
.md-root :deep(.md-h3),
.md-root :deep(.md-h4),
.md-root :deep(.md-h5),
.md-root :deep(.md-h6) { font-size: 13.5px; }
.md-root :deep(.inline-code) {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  font-size: 12px;
}
.md-root :deep(.md-link) { color: var(--oc-accent); text-decoration: underline; overflow-wrap: anywhere; }
.md-root :deep(.md-quote) {
  margin: 4px 0;
  padding: 4px 10px;
  border-left: 3px solid var(--oc-border-strong);
  color: var(--oc-fg-muted);
}
.md-root :deep(.md-list) { margin: 2px 0; padding-left: 18px; display: flex; flex-direction: column; gap: 3px; }
.md-root :deep(.md-hr) { border: 0; border-top: 1px solid var(--oc-border); margin: 6px 0; }
.md-root :deep(.md-table-wrap) { overflow-x: auto; max-width: 100%; }
.md-root :deep(.md-table) { border-collapse: collapse; width: 100%; font-size: 12px; }
.md-root :deep(.md-table th),
.md-root :deep(.md-table td) { border: 1px solid var(--oc-border); padding: 4px 8px; text-align: left; }
.md-root :deep(.md-table th) { background: var(--oc-bg-subtle); font-weight: 600; }
.md-root :deep(.code-block) {
  margin: 4px 0;
  border: 1px solid var(--oc-border-strong);
  border-radius: 8px;
  background: var(--oc-bg-input);
  overflow: hidden;
}
.md-root :deep(.code-block header) {
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
.md-root :deep(.code-block header button) {
  padding: 3px 9px;
  border-radius: 6px;
  border: 1px solid var(--oc-border-strong);
  background: var(--oc-surface-2);
  color: var(--oc-fg);
  font-size: 11.5px;
  cursor: pointer;
}
.md-root :deep(.code-block header button:hover) {
  background: var(--oc-surface-hover);
}
.md-root :deep(.code-block pre) { padding: 10px 12px; overflow-x: auto; white-space: pre; font-size: 12.5px; }
</style>
