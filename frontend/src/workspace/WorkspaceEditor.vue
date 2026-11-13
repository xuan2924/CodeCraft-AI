<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { NButton, useThemeVars } from 'naive-ui';
import { ApiError } from '../api/request';
import * as api from './api';
import { buffer, committed, dirty, observe, useLatestBaseline, type Buffer } from './editorBuffer';
import { listDrafts, putDraft, readDraftEpoch, removeDraft, type Draft } from './drafts';
import CodeEditor from './CodeEditor.vue';

const props = defineProps<{ owner: string; exercise: api.Exercise; file?: api.WorkspaceFile; selected: string; credential: string; locked?: boolean; testBusy?: boolean; answeringClosed?: boolean }>();
const emit = defineEmits<{ saved: []; select: [path: string]; 'stale-control': []; 'run-test': [revision: string] }>();
const theme = useThemeVars();
const buffers = reactive(new Map<string, Buffer>());
const current = computed(() => buffers.get(props.selected));
const edited = computed(() => [...buffers.values()].some(dirty));
const canWrite = computed(() => !props.answeringClosed && !!props.credential && props.exercise.state === 'ACTIVE' &&
  (!props.exercise.deadlineAt || Date.now() < Date.parse(props.exercise.deadlineAt)));
const busy = ref(false);
const notice = ref('尚未修改；编辑内容需显式保存。');
const error = ref('');
const testPrompt = ref(false);
const confirmedRevision = ref(props.exercise.revision);
watch(() => props.exercise.revision, (revision) => {
  if (revision !== null && (confirmedRevision.value === null || BigInt(revision) > BigInt(confirmedRevision.value))) confirmedRevision.value = revision;
});
const backup = ref('本地草稿尚未更新。');
const recoverable = ref<Draft[]>([]);
const newPath = ref('src/test/java/Example.java');
const restored = new Map<string, Draft>();
const persisted = new Map<string, Draft>();
const pageId = globalThis.crypto.randomUUID();
const owner = props.owner;
const exercise = props.exercise.exerciseId;
// Capture once: explicit logout fences queued backups and old pages, even after a later login.
const writerEpoch = readDraftEpoch(owner).catch(() => null);
const controller = new globalThis.AbortController();
let disposed = false;
let backupTimer: ReturnType<typeof globalThis.setTimeout> | undefined;
let pollTimer: ReturnType<typeof globalThis.setTimeout> | undefined;
let backupQueue = Promise.resolve();
let loadGeneration = 0;
interface Pending { key: string; revision: string; changes: api.FileChange[]; operationId?: string; select?: string }
const unresolved = ref<Pending | null>(null);
const editorLocked = computed(() => props.locked || props.answeringClosed || props.exercise.state !== 'ACTIVE' || !current.value || !/^src\/(main|test)\/java\//.test(props.selected) ||
  unresolved.value?.changes.some((change) => change.path === props.selected && change.kind === 'DELETE') === true);
const leaving = ref(false);
let finishLeave: ((value: boolean) => void) | undefined;

watch(() => props.file, (file) => {
  if (!file || file.workspaceInstanceId !== props.exercise.workspaceInstanceId) return;
  const existing = buffers.get(file.path);
  if (existing) observe(existing, file);
  else buffers.set(file.path, buffer(file));
}, { immediate: true });
watch(() => props.selected, async (path) => {
  const generation = ++loadGeneration;
  recoverable.value = [];
  if (!path) return;
  try {
    const drafts = await listDrafts(owner, exercise, path);
    if (!disposed && generation === loadGeneration) recoverable.value = drafts.filter((draft) => draft.id !== pageId);
  } catch { if (!disposed) backup.value = '本地草稿存储不可用，请及时保存或复制内容。'; }
}, { immediate: true });

function scheduleBackup() {
  if (backupTimer) globalThis.clearTimeout(backupTimer);
  backupTimer = globalThis.setTimeout(() => { void flushBackup(); }, 300);
}
function flushBackup(): Promise<void> {
  if (backupTimer) globalThis.clearTimeout(backupTimer);
  backupTimer = undefined;
  const drafts = [...buffers.values()].filter(dirty).map((value): Draft => ({ owner, exercise, id: pageId,
    path: value.path, content: value.content, baseContent: value.baseContent, revision: value.revision, updatedAt: Date.now() }));
  const cleanPaths = [...buffers.values()].filter((value) => !dirty(value)).map((value) => value.path);
  backupQueue = backupQueue.then(async () => {
    const epoch = await writerEpoch;
    if (drafts.length && epoch === null) throw new Error('Local draft session unavailable');
    for (const draft of drafts) { await putDraft(draft, epoch!); persisted.set(draft.path, draft); }
    for (const path of cleanPaths) {
      const previous = persisted.get(path);
      if (previous) { await removeDraft(previous); persisted.delete(path); }
    }
    if (!disposed) backup.value = drafts.length ? '本地草稿已更新；不等于已保存到工作区，仅限此浏览器。' : '当前页面没有未保存修改；其他页面的草稿不受影响。';
  }).catch(() => { if (!disposed) backup.value = '本地草稿更新失败，请及时保存或复制内容。'; });
  return backupQueue;
}
function edit(value: string) {
  if (!current.value || editorLocked.value) return;
  current.value.content = value;
  notice.value = '有未保存修改。';
  scheduleBackup();
}
function restore(draft: Draft) {
  const value = buffers.get(draft.path);
  if (!value || editorLocked.value || (dirty(value) && !globalThis.confirm('恢复草稿将替换当前编辑器内容，是否继续？'))) return;
  const latest = value.remote ?? { workspaceInstanceId: props.exercise.workspaceInstanceId, path: value.path, revision: value.revision, content: value.baseContent };
  value.content = draft.content; value.baseContent = draft.baseContent; value.revision = draft.revision;
  value.remote = latest.revision === draft.revision ? null : latest;
  restored.set(value.path, draft);
  recoverable.value = recoverable.value.filter((item) => item.id !== draft.id);
  notice.value = '草稿已恢复到编辑器，尚未写入服务器。';
  scheduleBackup();
}
async function cleanMatching(path: string, submitted: string, includeRecovered = true) {
  await backupQueue;
  for (const records of includeRecovered ? [persisted, restored] : [persisted]) {
    const draft = records.get(path);
    if (draft?.content === submitted) { await removeDraft(draft); records.delete(path); }
  }
}
async function discard(value: Buffer) {
  if (!globalThis.confirm(`丢弃 ${value.path} 的当前草稿？其他页面的草稿不受影响。`)) return;
  const text = value.content;
  await flushBackup();
  if (disposed) return;
  if (value.content !== text) { error.value = '丢弃期间产生了新输入，请重新确认。'; return; }
  try { await cleanMatching(value.path, text, false); }
  catch { backup.value = '草稿清理失败，刷新时可能仍看到旧备份。'; return; }
  if (disposed) return;
  if (value.content !== text) { error.value = '丢弃期间产生了新输入，请重新确认。'; return; }
  value.content = value.remote?.content ?? value.baseContent;
  useLatestBaseline(value);
}
async function submit(pending: Pending, polling = false): Promise<boolean> {
  if (busy.value || disposed || (polling && unresolved.value?.key !== pending.key)) return false;
  if (pollTimer) globalThis.clearTimeout(pollTimer);
  pollTimer = undefined;
  busy.value = true; error.value = ''; unresolved.value = pending;
  try {
    const result = pending.operationId ? await api.operation(pending.operationId, controller.signal)
      : await api.save(exercise, pending.revision, pending.changes, pending.key, props.credential, controller.signal);
    if (disposed) return false;
    if (result.exerciseId !== exercise) throw new ApiError(502, 'INVALID_RESPONSE');
    if (result.state === 'PENDING' || result.state === 'PENDING_RECONCILIATION') {
      pending.operationId = result.operationId;
      notice.value = '保存结果待核对；不会自动换请求重新写入。';
      pollTimer = globalThis.setTimeout(() => { void submit(pending, true); }, 1000);
      return false;
    }
    if (result.state === 'REJECTED') { unresolved.value = null; throw new ApiError(409, result.failureCode ?? 'WRITE_NOT_COMMITTED'); }
    if (result.revision === null) throw new ApiError(502, 'INVALID_RESPONSE');
    unresolved.value = null;
    confirmedRevision.value = result.revision;
    for (const change of pending.changes) {
      const value = buffers.get(change.path);
      if (change.kind === 'DELETE') buffers.delete(change.path);
      else if (value) committed(value, change.content, result.revision);
      try { await cleanMatching(change.path, change.content); }
      catch { backup.value = '正式保存已确认，但本地旧备份清理失败。'; }
    }
    if (disposed) return false;
    notice.value = edited.value ? '提交内容已保存；保存期间的新输入仍未保存。' : `已保存到工作区，修订 ${result.revision}。`;
    emit('saved');
    if (pending.select !== undefined) emit('select', pending.select);
    if (edited.value) scheduleBackup();
    return true;
  } catch (failure) {
    if (disposed) return false;
    error.value = api.message(failure);
    if (failure instanceof ApiError && failure.code === 'STALE_CONTROL') emit('stale-control');
    if (failure instanceof ApiError && failure.status >= 400 && failure.status < 500 && !polling && !pending.operationId) unresolved.value = null;
    emit('saved'); // Read-only refresh reveals conflicts; never replays a mutation.
    return false;
  } finally { if (!disposed) busy.value = false; }
}
async function saveBuffers(values: Buffer[]): Promise<boolean> {
  if (!canWrite.value || busy.value || unresolved.value) return false;
  if (!values.length) return true;
  if (values.some((value) => value.remote || value.revision !== values[0]!.revision)) {
    error.value = '请先比较冲突并为这些文件确认同一最新基线。'; return false;
  }
  const changes: api.FileChange[] = values.map((value) => ({ path: value.path, kind: 'UPDATE', content: value.content }));
  return submit({ key: globalThis.crypto.randomUUID(), revision: values[0]!.revision, changes });
}
async function saveForLogout(): Promise<boolean> {
  if (busy.value || unresolved.value || disposed) return false;
  if (edited.value && !await saveBuffers([...buffers.values()].filter(dirty))) return false;
  await flushBackup();
  return !disposed && !edited.value && !unresolved.value;
}
async function prepareEnd(choice: 'save' | 'discard' | 'retain'): Promise<boolean> {
  if (disposed) return false;
  if (choice === 'retain') { await flushBackup(); return !disposed; }
  if (busy.value || unresolved.value || disposed || !canWrite.value) return false;
  if (choice === 'save') return saveForLogout();
  return discardAll();
}
defineExpose({ saveForLogout, prepareEnd });
function requestTest() {
  if (!canWrite.value || props.locked || props.testBusy || busy.value || unresolved.value) return;
  if (edited.value) { testPrompt.value = true; return; }
  if (confirmedRevision.value !== null) emit('run-test', confirmedRevision.value);
}
async function saveAndTest() {
  if (!testPrompt.value || props.testBusy || props.locked) return;
  if (!await saveBuffers([...buffers.values()].filter(dirty))) return;
  if (disposed || !testPrompt.value) return;
  if (edited.value || unresolved.value) { error.value = '保存期间产生了新输入或结果未确认，未启动测试。'; return; }
  testPrompt.value = false;
  requestTest();
}
function saveCurrent() { if (current.value && !props.locked) void saveBuffers([current.value]); }
function closeFile() {
  if (!current.value || busy.value || unresolved.value || props.locked) return;
  if (dirty(current.value) && !globalThis.confirm('文件有未保存修改。关闭视图会保留本页缓冲区，不代表已保存或已备份；是否关闭？')) return;
  emit('select', '');
}
function createFile() {
  if (!canWrite.value || !props.exercise.revision || busy.value || unresolved.value) return;
  void submit({ key: globalThis.crypto.randomUUID(), revision: props.exercise.revision,
    changes: [{ path: newPath.value, kind: 'CREATE', content: '' }], select: newPath.value });
}
function removeOrRename(rename: boolean) {
  const value = current.value;
  if (!value || dirty(value) || value.remote || !canWrite.value || busy.value || unresolved.value) return;
  if (!globalThis.confirm(rename ? `将 ${value.path} 重命名为 ${newPath.value}？` : `删除正式文件 ${value.path}？`)) return;
  const changes: api.FileChange[] = [{ path: value.path, kind: 'DELETE', content: '' }];
  if (rename) changes.push({ path: newPath.value, kind: 'CREATE', content: value.content });
  void submit({ key: globalThis.crypto.randomUUID(), revision: value.revision, changes, select: rename ? newPath.value : '' });
}
function beforeUnload(event: globalThis.BeforeUnloadEvent) {
  if (props.locked || edited.value || unresolved.value) { event.preventDefault(); event.returnValue = ''; }
}
globalThis.addEventListener('beforeunload', beforeUnload);
onBeforeRouteLeave(() => {
  if (props.locked) return false;
  if (!edited.value && !unresolved.value) return true;
  if (finishLeave) return false;
  leaving.value = true;
  return new Promise<boolean>((resolve) => { finishLeave = resolve; });
});
async function discardAll(): Promise<boolean> {
  const confirmed = new Map([...buffers.values()].map((value) => [value.path, value.content]));
  await flushBackup();
  try {
    for (const [path, text] of confirmed) await cleanMatching(path, text, false);
  } catch { error.value = '本地草稿未能清理，请重试或取消。'; return false; }
  await backupQueue;
  if (disposed) return false;
  if ([...buffers.values()].some((value) => value.content !== confirmed.get(value.path))) {
    error.value = '丢弃期间产生了新输入，请重新确认。'; return false;
  }
  for (const value of buffers.values()) { value.content = value.remote?.content ?? value.baseContent; useLatestBaseline(value); }
  return true;
}
async function leave(choice: 'save' | 'discard' | 'cancel') {
  if (choice === 'cancel') { finishLeave?.(false); finishLeave = undefined; leaving.value = false; return; }
  if (busy.value || unresolved.value) { error.value = '先核对在途操作；不能把离开页面当作取消保存。'; return; }
  if (choice === 'save') {
    if (!(await saveBuffers([...buffers.values()].filter(dirty)))) return;
    if (edited.value) { error.value = '保存期间产生了新输入，请处理后再离开。'; return; }
  }
  if (choice === 'discard') {
    if (!await discardAll()) return;
  } else {
    await backupQueue;
    if (disposed || edited.value) { error.value = '保存期间产生了新输入，请处理后再离开。'; return; }
  }
  finishLeave?.(true); finishLeave = undefined; leaving.value = false;
}
onScopeDispose(() => {
  void flushBackup();
  disposed = true; controller.abort();
  if (pollTimer) globalThis.clearTimeout(pollTimer);
  globalThis.removeEventListener('beforeunload', beforeUnload);
  finishLeave?.(false);
});
</script>

<template>
  <fieldset
    :disabled="locked"
    class="editor-panel"
    aria-label="文件编辑"
    :style="{ '--editor-border': theme.borderColor }"
  >
    <h2>{{ selected || '选择或创建文件' }}</h2>
    <p v-if="answeringClosed || props.exercise.state !== 'ACTIVE'" role="status">
      作答已关闭。这里保留本地内容供查看或复制，不会自动保存，也不是最终冻结代码；备份内容可展开查看。
    </p>
    <p role="status">
      {{ busy ? '正在保存或核对…' : notice }}
    </p>
    <p role="status">
      {{ backup }}
    </p>
    <p
      v-if="error"
      role="alert"
    >
      {{ error }}
    </p>
    <div v-if="testPrompt" role="group" aria-label="运行前保存确认">
      <p>有未保存修改，测试不会使用这些草稿。保存所有已打开文件后运行，或返回编辑。</p>
      <NButton :disabled="busy || !!unresolved || testBusy || !canWrite" @click="saveAndTest">保存后运行</NButton>
      <NButton @click="testPrompt = false">返回编辑</NButton>
    </div>
    <div class="editor-actions">
      <NButton :disabled="!canWrite || busy || !!unresolved || testBusy || testPrompt" @click="requestTest">
        运行基础测试
      </NButton>
      <NButton
        :disabled="!current || !canWrite || busy || !!unresolved"
        @click="saveCurrent"
      >
        保存文件
      </NButton>
      <NButton
        :disabled="!current || busy || !!unresolved"
        @click="closeFile"
      >
        关闭文件
      </NButton>
      <NButton
        v-if="unresolved"
        :disabled="busy"
        @click="submit(unresolved)"
      >
        核对原保存
      </NButton>
      <NButton
        v-if="current && dirty(current)"
        :disabled="busy || !!unresolved"
        @click="discard(current)"
      >
        丢弃当前草稿
      </NButton>
    </div>
    <div
      v-for="draft in recoverable"
      :key="draft.id"
      class="draft-choice"
    >
      <span>可恢复草稿 · {{ new Date(draft.updatedAt).toLocaleString() }} · 页面 {{ draft.id }}</span>
      <details><summary>查看备份内容</summary><pre>{{ draft.content }}</pre></details>
      <NButton
        :disabled="editorLocked || busy"
        @click="restore(draft)"
      >
        恢复此草稿
      </NButton>
    </div>
    <div :class="{ 'conflict-columns': !!current?.remote }">
      <div>
        <h3 v-if="current?.remote">
          本地草稿（可整理）
        </h3>
        <CodeEditor
          :path="selected"
          :model-value="current?.content ?? ''"
          :readonly="editorLocked"
          @update:model-value="edit"
          @save="saveCurrent"
        />
      </div>
      <section
        v-if="current?.remote"
        aria-label="当前服务端内容"
      >
        <h3>当前服务端 · 修订 {{ current.remote.revision }}</h3>
        <pre>{{ current.remote.content }}</pre>
        <details><summary>查看编辑基线</summary><pre>{{ current.baseContent }}</pre></details>
        <NButton
          :disabled="busy || !!unresolved"
          @click="useLatestBaseline(current); scheduleBackup()"
        >
          保留本地内容，采用此基线
        </NButton>
        <p>此操作不提交代码；整理后仍需显式保存。服务器再次变化时会再次冲突。</p>
      </section>
    </div>
    <div class="file-actions">
      <label for="file-destination">新建或重命名的目标路径</label>
      <input
        id="file-destination"
        v-model="newPath"
        autocomplete="off"
      >
      <NButton
        :disabled="!canWrite || busy || !!unresolved"
        @click="createFile"
      >
        创建文本文件
      </NButton>
      <NButton
        :disabled="!canWrite || !current || dirty(current) || busy || !!unresolved"
        @click="removeOrRename(true)"
      >
        重命名文件
      </NButton>
      <NButton
        :disabled="!canWrite || !current || dirty(current) || busy || !!unresolved"
        @click="removeOrRename(false)"
      >
        删除文件
      </NButton>
      <p>删除或重命名前，先保存或明确丢弃该文件的草稿。</p>
    </div>
    <div
      v-if="leaving"
      role="dialog"
      aria-modal="true"
      aria-label="处理未保存内容"
      class="leave-dialog"
    >
      <h3>离开前处理未保存内容</h3>
      <NButton
        :disabled="busy || !!unresolved || !canWrite"
        @click="leave('save')"
      >
        保存后离开
      </NButton>
      <NButton
        :disabled="busy || !!unresolved"
        @click="leave('discard')"
      >
        丢弃并离开
      </NButton>
      <NButton @click="leave('cancel')">
        取消离开
      </NButton>
    </div>
  </fieldset>
</template>

<style scoped>
.editor-panel { min-width: 0; border: 0; margin: 0; padding: 0; }
.editor-actions, .file-actions, .draft-choice { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.file-actions { margin-top: 16px; }
.file-actions input { width: min(100%, 36rem); padding: 8px; font-family: Consolas, monospace; }
.file-actions label, .file-actions p { width: 100%; }
.conflict-columns { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; }
.conflict-columns pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 45vh; overflow: auto; }
.leave-dialog { padding: 20px; border: 2px solid var(--editor-border); }
@media (max-width: 760px) { .conflict-columns { grid-template-columns: 1fr; } }
</style>
