<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { FileCode2, Search, Terminal, TestTube2 } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import { ApiError } from '../api/request';
import { commandPolicy } from './errors';
import { preview, sources, type ReferenceSource, type ReferenceKind, type ReferencePage } from './referenceApi';

const props = defineProps<{ owner: string; exerciseId: string; conversationId: string; disabled: boolean; paths?: string[] }>();
const selected = defineModel<ReferencePage[]>({ required: true });
const emit = defineEmits<{ unauthorized: []; attached: [] }>();
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.conversationId}`);
const kind = ref<ReferenceKind>('FILE');
const search = ref('');
const source = ref('');
const page = ref<ReferencePage | null>(null);
const options = ref<ReferenceSource[]>([]);
const nextSources = ref<string | null>(null);
const loadedSources = ref(false);
function resetSources() { options.value = []; nextSources.value = null; loadedSources.value = false; source.value = ''; }
const pageEnd = computed(() => page.value ? page.value.offset + new globalThis.TextEncoder().encode(page.value.text).length : 0);
const pending = ref(false);
const error = ref('');
const notice = ref('');
const range = ref<[number, number]>([0, 0]);
let controller: globalThis.AbortController | null = null;
let generation = 0;

function reset() {
  generation++;
  controller?.abort();
  pending.value = false;
  page.value = null;
  error.value = '';
  notice.value = '';
  range.value = [0, 0];
}
watch(scope, () => { reset(); resetSources(); search.value = ''; }, { flush: 'sync' });
watch(kind, (next) => {
  resetSources();
  reset();
  if (next !== 'FILE') void loadSources();
}, { flush: 'sync' });
watch(source, reset, { flush: 'sync' });
watch(() => props.disabled, () => { generation++; controller?.abort(); pending.value = false; notice.value = ''; }, { flush: 'sync' });
onBeforeUnmount(reset);

const filteredPaths = computed(() => {
  const q = search.value.trim().toLowerCase();
  return (props.paths ?? []).filter(p => !q || p.toLowerCase().includes(q));
});

async function load(offset = 0, version: string | null = null, limit = 2048, addSelection = false) {
  if (props.disabled || !source.value.trim()) return;
  const epoch = ++generation;
  controller?.abort();
  controller = new globalThis.AbortController();
  pending.value = true;
  error.value = '';
  notice.value = '';
  const requestedKind = addSelection && kind.value === 'FILE' ? 'CODE' : kind.value;
  try {
    const result = await preview(props.owner, props.exerciseId, props.conversationId, requestedKind, source.value, offset, limit, version, controller.signal);
    if (epoch !== generation) return;
    if (addSelection) add(result);
    else { page.value = result; range.value = [0, 0]; }
  } catch (failure) {
    if (epoch !== generation) return;
    if (commandPolicy.identityFailure(failure)) emit('unauthorized');
    error.value = failure instanceof ApiError && failure.code === 'COPILOT_REFERENCE_CHANGED'
      ? '来源版本已变化，请重新预览后选择。'
      : `来源读取失败${failure instanceof ApiError ? `（${failure.code}）` : ''}。`;
  } finally { if (epoch === generation) pending.value = false; }
}

async function quickAttachFile(path: string) {
  if (props.disabled || selected.value.length >= 4) return;
  source.value = path;
  const epoch = ++generation;
  controller?.abort();
  controller = new globalThis.AbortController();
  pending.value = true;
  error.value = '';
  notice.value = '';
  try {
    const result = await preview(props.owner, props.exerciseId, props.conversationId, 'FILE', path, 0, 2048, null, controller.signal);
    if (epoch !== generation) return;
    page.value = result;
    range.value = [0, 0];
  } catch (failure) {
    if (epoch !== generation) return;
    if (commandPolicy.identityFailure(failure)) emit('unauthorized');
    error.value = failure instanceof ApiError && failure.code === 'COPILOT_REFERENCE_CHANGED'
      ? '来源版本已变化，请重新预览。'
      : `读取失败${failure instanceof ApiError ? `（${failure.code}）` : ''}。`;
  } finally { if (epoch === generation) pending.value = false; }
}

async function loadSources(after: string | null = null) {
  if (props.disabled || pending.value || kind.value === 'FILE') return;
  const epoch = ++generation;
  controller?.abort();
  controller = new globalThis.AbortController();
  pending.value = true;
  error.value = '';
  notice.value = '';
  try {
    const result = await sources(props.owner, props.exerciseId, props.conversationId, kind.value, after, controller.signal);
    if (epoch !== generation) return;
    options.value = result.sources;
    nextSources.value = result.nextCursor;
    loadedSources.value = true;
  } catch (failure) {
    if (epoch !== generation) return;
    if (commandPolicy.identityFailure(failure)) emit('unauthorized');
    error.value = '无法读取日志列表，请重试。';
  } finally { if (epoch === generation) pending.value = false; }
}

function add(value: ReferencePage) {
  if (props.disabled || selected.value.length >= 4) return;
  if (selected.value.some(item => item.fingerprint === value.fingerprint)) { notice.value = '此片段已引用。'; return; }
  selected.value = [...selected.value, value];
  notice.value = '已添加引用';
  emit('attached');
}

function selectionChanged(event: globalThis.Event) {
  if (event.target instanceof globalThis.HTMLTextAreaElement) range.value = [event.target.selectionStart, event.target.selectionEnd];
}

// Textarea indices address LF-normalized UTF-16; source offsets address original UTF-8 bytes.
function originalBoundary(text: string, normalized: number) {
  let original = 0;
  for (let position = 0; position < normalized && original < text.length; position++) {
    original += text[original] === '\r' && text[original + 1] === '\n' ? 2 : 1;
  }
  return original;
}

function addRange() {
  const current = page.value;
  const [start, end] = range.value;
  if (!current || end <= start) return;
  const encoder = new globalThis.TextEncoder();
  const from = originalBoundary(current.text, start);
  const through = originalBoundary(current.text, end);
  void load(current.offset + encoder.encode(current.text.slice(0, from)).length, current.version, encoder.encode(current.text.slice(from, through)).length, true);
}
</script>

<template>
  <section
    class="ref-picker"
    aria-label="上下文引用"
  >
    <div class="ref-tabs-row">
      <div
        class="ui-segmented"
        role="tablist"
        aria-label="来源类型"
      >
        <button
          type="button"
          role="tab"
          class="ui-segmented-btn"
          :aria-selected="kind === 'FILE'"
          @click="kind = 'FILE'"
        >
          <FileCode2
            :size="12"
            aria-hidden="true"
          />
          <span>文件 / 代码</span>
        </button>
        <button
          type="button"
          role="tab"
          class="ui-segmented-btn"
          :aria-selected="kind === 'TEST_LOG'"
          @click="kind = 'TEST_LOG'"
        >
          <TestTube2
            :size="12"
            aria-hidden="true"
          />
          <span>测试日志</span>
        </button>
        <button
          type="button"
          role="tab"
          class="ui-segmented-btn"
          :aria-selected="kind === 'COMMAND_LOG'"
          @click="kind = 'COMMAND_LOG'"
        >
          <Terminal
            :size="12"
            aria-hidden="true"
          />
          <span>命令日志</span>
        </button>
      </div>
      <span class="ref-count font-mono-num">{{ selected.length }}/4</span>
    </div>

    <template v-if="kind === 'FILE'">
      <div
        v-if="paths?.length"
        class="ref-file-list"
      >
        <div class="ref-search">
          <Search
            :size="12"
            aria-hidden="true"
          />
          <input
            v-model="search"
            type="search"
            aria-label="筛选工作区文件"
            placeholder="筛选文件…"
          >
        </div>
        <div class="ref-items">
          <button
            v-for="path in filteredPaths"
            :key="path"
            type="button"
            class="ref-item-btn font-mono-num"
            :class="{ 'is-active': source === path }"
            :disabled="disabled || pending"
            @click="quickAttachFile(path)"
          >
            {{ path }}
          </button>
        </div>
      </div>
      <div
        v-else
        class="ref-manual-row"
      >
        <input
          v-model="source"
          aria-label="保存文件路径"
          placeholder="输入已保存文件路径…"
          :disabled="disabled || pending"
        >
        <Button
          variant="secondary"
          size="small"
          :disabled="disabled || pending || !source.trim()"
          @click="load()"
        >
          预览
        </Button>
      </div>
    </template>

    <template v-else>
      <div
        v-if="options.length"
        class="ref-items"
      >
        <button
          v-for="item in options"
          :key="item.id"
          type="button"
          class="ref-item-btn"
          :class="{ 'is-active': source === item.id }"
          :disabled="disabled || pending"
          @click="source = item.id; load()"
        >
          <span class="truncate">{{ item.label }}</span>
          <small class="font-mono-num">r{{ item.revision }} · {{ item.state }}</small>
        </button>
      </div>
      <p
        v-else-if="loadedSources"
        class="ref-note"
      >
        暂无保留的{{ kind === 'TEST_LOG' ? '测试' : '命令' }}日志。
      </p>
      <div
        v-if="nextSources"
        class="ref-more-row"
      >
        <Button
          variant="ghost"
          size="small"
          :disabled="disabled || pending"
          @click="loadSources(nextSources)"
        >
          更多日志
        </Button>
      </div>
    </template>

    <p
      v-if="pending"
      role="status"
      class="ref-note"
    >
      正在读取…
    </p>
    <p
      v-if="error"
      role="alert"
      class="ref-error"
    >
      {{ error }}
    </p>

    <div
      v-if="page"
      class="ref-preview-box"
    >
      <div class="ref-preview-meta font-mono-num">
        <span class="truncate">{{ page.source }}</span>
        <span>{{ page.offset }}–{{ pageEnd }} / {{ page.retainedBytes }} B</span>
      </div>
      <p
        v-if="page.sourceIncomplete || page.droppedBytes !== '0'"
        class="ref-warn"
      >
        已丢弃 {{ page.droppedBytes }} 字节尾部/截断内容
      </p>
      <textarea
        :value="page.text"
        readonly
        rows="5"
        aria-label="保留来源片段，可框选部分代码"
        @select="selectionChanged"
      />
      <div class="ref-preview-actions">
        <Button
          variant="primary"
          size="small"
          :disabled="disabled || pending || selected.length >= 4"
          @click="add(page)"
        >
          引用当前片段
        </Button>
        <Button
          v-if="range[1] > range[0]"
          variant="secondary"
          size="small"
          :disabled="disabled || pending || selected.length >= 4"
          @click="addRange"
        >
          引用选中代码
        </Button>
        <Button
          v-if="page.nextOffset !== null"
          variant="ghost"
          size="small"
          :disabled="disabled || pending"
          @click="load(page.nextOffset, page.version)"
        >
          下一段
        </Button>
      </div>
    </div>

    <p
      v-if="notice"
      role="status"
      class="ref-status"
    >
      {{ notice }}
    </p>
  </section>
</template>

<style scoped>
.ref-picker {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.ref-tabs-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ref-count {
  font-size: 11px;
  color: var(--oc-fg-dim);
}

.ref-search {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border-radius: 6px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  color: var(--oc-fg-dim);
}

.ref-search input {
  width: 100%;
  border: 0;
  padding: 0;
  background: transparent;
  font-size: 12px;
  box-shadow: none;
  outline: none;
}

.ref-file-list {
  display: grid;
  gap: 6px;
}

.ref-items {
  display: grid;
  gap: 2px;
  max-height: 130px;
  overflow-y: auto;
}

.ref-item-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 5px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--oc-fg-muted);
  font-size: 11.5px;
  text-align: left;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ref-item-btn:hover:not(:disabled),
.ref-item-btn.is-active {
  background: rgba(255, 255, 255, 0.07);
  color: var(--oc-fg);
}

.ref-item-btn small {
  color: var(--oc-fg-dim);
  flex-shrink: 0;
}

.ref-manual-row {
  display: flex;
  gap: 6px;
}

.ref-manual-row input {
  flex: 1;
  min-width: 0;
  padding: 5px 8px;
  font-size: 12px;
}

.ref-preview-box {
  display: grid;
  gap: 6px;
  padding-top: 6px;
  border-top: 1px solid var(--oc-border);
}

.ref-preview-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 11px;
  color: var(--oc-fg-muted);
}

.ref-preview-box textarea {
  width: 100%;
  padding: 7px 9px;
  font-family: var(--oc-font-mono);
  font-size: 11.5px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  border-radius: 6px;
  resize: none;
}

.ref-preview-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.ref-note {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-fg-muted);
}

.ref-error {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-danger);
}

.ref-warn {
  margin: 0;
  font-size: 11px;
  color: var(--oc-warning);
}

.ref-status {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-accent);
}
</style>
