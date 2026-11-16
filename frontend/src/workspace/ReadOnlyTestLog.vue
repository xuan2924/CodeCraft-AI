<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, watch } from 'vue';
import type { Terminal } from '@xterm/xterm';
import { colors } from '../components/ui/tokens';
import '@xterm/xterm/css/xterm.css';

const props = withDefaults(defineProps<{ logs: { channel: string; text: string }[]; label?: string }>(), { label: '测试' });
const terminalTheme = computed(() => ({ background: colors.background,
  foreground: colors.foreground, selectionBackground: colors.selected }));
const host = ref<globalThis.HTMLDivElement>();
const unavailable = ref(false);
// Local rendering is capped separately from the server's retained log window.
const limit = 65536;
const clipped = computed(() => props.logs.reduce((sum, log) => sum + log.text.length, 0) > limit);
const text = computed(() => {
  let remaining = limit;
  const tail: string[] = [];
  for (let index = props.logs.length - 1; index >= 0 && remaining > 0; index--) {
    const log = props.logs[index]!;
    const part = log.text.slice(-remaining); remaining -= part.length;
    // Never pass untrusted control bytes to xterm's parser (including OSC 8/52,
    // title, cursor movement, C1 and Unicode bidi formatting). No parser state
    // crosses chunks, so a split escape sequence cannot bypass the filter.
    const safe = Array.from(part).map((character) => {
      const point = character.codePointAt(0)!;
      return (point < 32 && point !== 9 && point !== 10) || (point >= 127 && point <= 159)
        || (point >= 0x202a && point <= 0x202e) || (point >= 0x2066 && point <= 0x2069) ? '�' : character;
    }).join('');
    tail.push(`[${log.channel}] ${safe}`);
  }
  return tail.reverse().join('');
});
let terminal: Terminal | undefined;
let resize: globalThis.ResizeObserver | undefined;
let disposed = false;
let writing = false;
let pending = false;
let timer: ReturnType<typeof globalThis.setTimeout> | undefined;
function render() {
  timer = undefined;
  if (disposed || !terminal || writing || !pending) return;
  pending = false; writing = true;
  terminal.reset();
  terminal.write(text.value, () => { writing = false; if (!disposed && pending) timer = globalThis.setTimeout(render, 100); });
}
watch(text, () => { pending = true; if (!timer && !writing) timer = globalThis.setTimeout(render, 100); });
watch(terminalTheme, (theme) => { if (terminal) terminal.options.theme = theme; });
onMounted(async () => {
  if (typeof globalThis.ResizeObserver === 'undefined') { unavailable.value = true; return; }
  try {
    const { Terminal: Xterm } = await import('@xterm/xterm');
    if (disposed || !host.value) return;
    terminal = new Xterm({ disableStdin: true, convertEol: true, scrollback: 2000, rows: 18, cols: 80,
      fontFamily: 'Consolas, "Cascadia Mono", monospace', fontSize: 14, cursorBlink: false,
      linkHandler: { activate: () => {} },
      theme: terminalTheme.value });
    terminal.open(host.value);
    resize = new globalThis.ResizeObserver(() => {
      if (terminal && host.value && !disposed) terminal.resize(Math.max(20, Math.min(160, Math.floor(host.value.clientWidth / 8.5))), 18);
    });
    resize.observe(host.value);
    pending = true; render();
  } catch { unavailable.value = true; terminal?.dispose(); terminal = undefined; }
});
onScopeDispose(() => { disposed = true; globalThis.clearTimeout(timer); resize?.disconnect(); terminal?.dispose(); terminal = undefined; });
</script>

<template>
  <div class="readonly-log">
    <p
      v-if="clipped"
      role="status"
    >
      显示缓冲已截断，仅渲染末尾 64K 字符；终端最多保留 2000 行，结果不受影响。
    </p>
    <p
      v-if="unavailable"
      role="status"
    >
      终端显示不可用，下面仍可查看只读文本。
    </p>
    <div
      ref="host"
      class="terminal-host"
      :aria-label="`只读${label}终端，无输入功能`"
    />
    <details :open="unavailable">
      <summary>查看日志文本（可复制，stdout / stderr 分别标记）</summary>
      <pre
        class="test-output"
        tabindex="0"
        :aria-label="`只读${label}日志文本`"
      >{{ text }}</pre>
    </details>
  </div>
</template>

<style scoped>
.readonly-log { display: grid; gap: 8px; }
.readonly-log p { margin: 0; font-size: 12.5px; color: var(--oc-fg-muted); }
.terminal-host {
  max-width: 100%;
  overflow: hidden;
  border-radius: 8px;
  border: 1px solid var(--oc-border);
  padding: 8px;
  background: #111612;
}
details summary { cursor: pointer; font-size: 12.5px; color: var(--oc-fg-muted); }
.test-output {
  max-height: 24rem;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  unicode-bidi: plaintext;
  padding: 12px;
  border-radius: 8px;
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
  font-size: 12.5px;
  line-height: 1.55;
}
</style>
