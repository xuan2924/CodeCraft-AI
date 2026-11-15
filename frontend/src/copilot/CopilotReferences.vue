<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ApiError } from '../api/request';
import { commandPolicy } from './errors';
import { preview, sources, type ReferenceSource, type ReferenceKind, type ReferencePage } from './referenceApi';
const props = defineProps<{ owner: string; exerciseId: string; conversationId: string; disabled: boolean; paths?: string[] }>();
const selected = defineModel<ReferencePage[]>({ required: true });
const emit = defineEmits<{ unauthorized: [] }>();
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.conversationId}`);
const kind = ref<ReferenceKind>('FILE'); const source = ref(''); const page = ref<ReferencePage | null>(null);
const options = ref<ReferenceSource[]>([]); const nextSources = ref<string | null>(null); const loadedSources = ref(false);
function resetSources() { options.value = []; nextSources.value = null; loadedSources.value = false; source.value = ''; }
const pageEnd = computed(() => page.value ? page.value.offset + new globalThis.TextEncoder().encode(page.value.text).length : 0);
const pending = ref(false); const error = ref(''); const notice = ref(''); const range = ref<[number, number]>([0, 0]);
let controller: globalThis.AbortController | null = null; let generation = 0;
function reset() { generation++; controller?.abort(); pending.value = false; page.value = null; error.value = ''; notice.value = ''; range.value = [0, 0]; }
watch(scope, () => { reset(); resetSources(); }, { flush: 'sync' });
watch(kind, resetSources, { flush: 'sync' });
watch([kind, source], reset, { flush: 'sync' });
watch(() => props.disabled, () => { generation++; controller?.abort(); pending.value = false; notice.value = ''; }, { flush: 'sync' });
onBeforeUnmount(reset);
async function load(offset = 0, version: string | null = null, limit = 2048, addSelection = false) {
  if (props.disabled || !source.value.trim()) return;
  const epoch = ++generation; controller?.abort(); controller = new globalThis.AbortController();
  pending.value = true; error.value = ''; notice.value = '';
  const requestedKind = addSelection && kind.value === 'FILE' ? 'CODE' : kind.value;
  try {
    const result = await preview(props.owner, props.exerciseId, props.conversationId, requestedKind, source.value, offset, limit, version, controller.signal);
    if (epoch !== generation) return;
    if (addSelection) add(result); else { page.value = result; range.value = [0, 0]; }
  } catch (failure) {
    if (epoch !== generation) return;
    if (commandPolicy.identityFailure(failure)) emit('unauthorized');
    error.value = failure instanceof ApiError && failure.code === 'COPILOT_REFERENCE_CHANGED'
      ? '来源版本已变化，未用新内容替换旧引用。请重新预览后明确选择。'
      : `来源读取失败，未取得空内容成功回执${failure instanceof ApiError ? `（${failure.code}）` : ''}。`;
  } finally { if (epoch === generation) pending.value = false; }
}
async function loadSources(after: string | null = null) {
  if (props.disabled || pending.value || kind.value === 'FILE') return;
  const epoch = ++generation; controller?.abort(); controller = new globalThis.AbortController(); pending.value = true; error.value = ''; notice.value = '';
  try {
    const result = await sources(props.owner, props.exerciseId, props.conversationId, kind.value, after, controller.signal);
    if (epoch !== generation) return;
    options.value = result.sources; nextSources.value = result.nextCursor; loadedSources.value = true;
  } catch (failure) {
    if (epoch !== generation) return;
    if (commandPolicy.identityFailure(failure)) emit('unauthorized');
    error.value = '无法读取保留日志任务列表；可重试，未执行任何测试或命令。';
  } finally { if (epoch === generation) pending.value = false; }
}
function add(value: ReferencePage) {
  if (props.disabled || selected.value.length >= 4) return;
  if (selected.value.some(item => item.fingerprint === value.fingerprint)) { notice.value = '此片段已引用。'; return; }
  selected.value = [...selected.value, value]; notice.value = '已加入引用；提交时服务端再次核对来源。';
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
  const current = page.value; const [start, end] = range.value;
  if (!current || end <= start) return;
  const encoder = new globalThis.TextEncoder();
  const from = originalBoundary(current.text, start); const through = originalBoundary(current.text, end);
  void load(current.offset + encoder.encode(current.text.slice(0, from)).length, current.version, encoder.encode(current.text.slice(from, through)).length, true);
}
async function copy() {
  const current = page.value; if (!current) return; const epoch = generation;
  try { await globalThis.navigator.clipboard.writeText(current.text); if (epoch === generation) notice.value = '当前保留片段已复制。'; }
  catch { if (epoch === generation) notice.value = '复制失败，可在只读文本框选择并手动复制。'; }
}
</script>
<template>
  <section aria-label="上下文引用">
    <h3>文件、代码与日志引用 · {{ selected.length }}/4</h3>
    <p>每项仅保留最多 2 KiB 节选，不注入整个项目。引用来自服务端保存版本；发送前保存草稿若改变版本，会明确拒绝旧引用，请重新预览。</p>
    <label>来源类型<select
      v-model="kind"
      :disabled="disabled || pending"
    ><option value="FILE">文件或选中代码</option><option value="TEST_LOG">测试日志</option><option value="COMMAND_LOG">命令日志</option></select></label>
    <label v-if="kind === 'FILE' && paths?.length">保存文件<select
      v-model="source"
      :disabled="disabled || pending"
    ><option value="">选择文件</option><option
      v-for="path in paths"
      :key="path"
      :value="path"
    >{{ path }}</option></select></label>
    <label v-else>{{ kind === 'FILE' ? '保存文件路径' : '原测试／命令任务 ID' }}<input
      v-model="source"
      :disabled="disabled || pending"
    ></label>
    <template v-if="kind !== 'FILE'">
      <button
        type="button"
        :disabled="disabled || pending"
        @click="loadSources()"
      >
        选择已有日志任务
      </button>
      <label v-if="options.length">保留任务<select
        v-model="source"
        :disabled="disabled || pending"
      ><option value="">选择任务</option><option
        v-for="item in options"
        :key="item.id"
        :value="item.id"
      >{{ item.label }} · {{ item.state }} · 修订 {{ item.revision }} · {{ item.id }}</option></select></label>
      <p v-else-if="loadedSources">
        当前页没有保留日志任务；不可据此重新执行历史命令。
      </p>
      <button
        v-if="nextSources"
        type="button"
        :disabled="disabled || pending"
        @click="loadSources(nextSources)"
      >
        更多保留日志任务
      </button>
    </template>
    <button
      type="button"
      :disabled="disabled || pending || !source.trim()"
      @click="load()"
    >
      预览当前来源
    </button>
    <p
      v-if="pending"
      role="status"
    >
      正在读取保留来源…
    </p><p
      v-if="error"
      role="alert"
    >
      {{ error }}
    </p>
    <div v-if="page">
      <p>{{ page.source }} · 版本 {{ page.version }}</p>
      <p>展示 UTF-8 字节 {{ page.offset }}–{{ pageEnd }} / {{ page.retainedBytes }}；{{ page.nextOffset === null ? '已到保留内容末尾' : '还有保留内容可读取' }}。</p>
      <p v-if="page.sourceIncomplete || page.droppedBytes !== '0'">
        底层来源不完整／已丢弃 {{ page.droppedBytes }} 字节，不能取回；不等同于当前展示分页。
      </p>
      <p v-if="!page.text">
        来源已确认，目前没有保留文本。
      </p>
      <textarea
        :value="page.text"
        readonly
        aria-label="保留来源片段，可选择代码或日志"
        @select="selectionChanged"
      />
      <button
        v-if="page.nextOffset !== null"
        type="button"
        :disabled="disabled || pending"
        @click="load(page.nextOffset, page.version)"
      >
        继续读取保留来源
      </button>
      <button
        type="button"
        :disabled="disabled || pending || selected.length >= 4"
        @click="add(page)"
      >
        引用当前片段
      </button>
      <button
        type="button"
        :disabled="disabled || pending || selected.length >= 4 || range[1] <= range[0]"
        @click="addRange"
      >
        引用选中代码／日志
      </button>
      <button
        type="button"
        @click="copy"
      >
        复制当前来源片段
      </button>
    </div>
    <p
      v-if="notice"
      role="status"
    >
      {{ notice }}
    </p>
    <ol>
      <li
        v-for="(item, index) in selected"
        :key="item.fingerprint"
      >
        <details>
          <summary>{{ item.kind }} · {{ item.source }} · 字节 {{ item.offset }} · 版本 {{ item.version }}</summary><pre>{{ item.text }}</pre><p v-if="item.sourceIncomplete || item.droppedBytes !== '0'">
            来源不完整，已丢弃 {{ item.droppedBytes }} 字节。
          </p>
        </details>
        <button
          type="button"
          :disabled="disabled || pending"
          @click="selected = selected.filter((_item, position) => position !== index)"
        >
          移除此引用
        </button>
      </li>
    </ol>
  </section>
</template>
<style scoped>
section { border: 1px solid var(--border); padding: .75rem; margin: .75rem 0; }
label { display: block; margin: .5rem 0; }
textarea { width: 100%; min-height: 8rem; box-sizing: border-box; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
