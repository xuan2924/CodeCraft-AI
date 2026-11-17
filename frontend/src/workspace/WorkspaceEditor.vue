<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { Play, Save, X } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import Tooltip from '../components/ui/Tooltip.vue';
import { ApiError } from '../api/request';
import * as api from './api';
import { buffer, committed, dirty as contentDirty, observe, useLatestBaseline, type Buffer } from './editorBuffer';
import { listDrafts, putDraft, readDraftEpoch, removeDraft, type Draft } from './drafts';
import CodeEditor from './CodeEditor.vue';
import ClosedDrafts from './ClosedDrafts.vue';

const props = defineProps<{ owner: string; exercise: api.Exercise; file?: api.WorkspaceFile; selected: string; credential: string; locked?: boolean; testBusy?: boolean; testTarget?: 'base' | 'challenge'; answeringClosed?: boolean }>();
const emit = defineEmits<{ saved: []; select: [path: string]; 'stale-control': []; 'run-test': [revision: string]; 'write-busy': [value: boolean] }>();
const buffers = reactive(new Map<string, Buffer>());
const deleted = reactive(new Map<string, string>());
const openTabs = ref<string[]>([]);
watch(() => props.selected, (path) => {
  if (path && !openTabs.value.includes(path)) openTabs.value.push(path);
}, { immediate: true });
watch(() => props.exercise.workspaceInstanceId, () => {
  openTabs.value = props.selected ? [props.selected] : [];
});
function tabLabel(path: string): string {
  const idx = path.lastIndexOf('/');
  return idx >= 0 ? path.slice(idx + 1) : path;
}
function closeTab(path: string) {
  if (busy.value || unresolved.value || props.locked) return;
  const target = buffers.get(path);
  if (target && dirty(target) && !globalThis.confirm('文件有未保存修改。关闭视图会保留本页缓冲区，不代表已保存或已备份；是否关闭？')) return;
  const idx = openTabs.value.indexOf(path);
  if (idx >= 0) openTabs.value.splice(idx, 1);
  if (props.selected === path) {
    emit('select', openTabs.value[idx] ?? openTabs.value[idx - 1] ?? '');
  }
}
function dirty(value: Buffer): boolean { return contentDirty(value) || deleted.has(value.path); }
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
watch(() => busy.value || unresolved.value !== null, value => emit('write-busy', value), { immediate: true, flush: 'sync' });
const editorLocked = computed(() => props.locked || props.answeringClosed || props.exercise.state !== 'ACTIVE' || !current.value || !/^src\/(main|test)\/java\//.test(props.selected) ||
  unresolved.value?.changes.some((change) => change.path === props.selected && change.kind === 'DELETE') === true);
const leaving = ref(false);
let finishLeave: ((value: boolean) => void) | undefined;

watch(() => props.file, (file) => {
  if (!file || file.workspaceInstanceId !== props.exercise.workspaceInstanceId) return;
  if (props.exercise.revision !== null && !props.exercise.paths.includes(file.path) && BigInt(file.revision) <= BigInt(props.exercise.revision)) return;
  const deletion = deleted.get(file.path);
  if (deletion && BigInt(file.revision) <= BigInt(deletion)) return;
  deleted.delete(file.path);
  const existing = buffers.get(file.path);
  if (existing) observe(existing, file);
  else buffers.set(file.path, buffer(file));
}, { immediate: true });
watch(() => [props.exercise.revision, props.exercise.paths] as const, ([revision, paths]) => {
  if (revision === null) return;
  for (const value of buffers.values()) {
    if (paths.includes(value.path) || BigInt(revision) <= BigInt(value.remote?.revision ?? value.revision)
      || BigInt(revision) <= BigInt(deleted.get(value.path) ?? '-1')) continue;
    if (dirty(value)) { deleted.set(value.path, revision); value.remote = null; }
    else { buffers.delete(value.path); deleted.delete(value.path); if (props.selected === value.path) emit('select', ''); }
  }
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
  if (deleted.has(value.path)) {
    buffers.delete(value.path); deleted.delete(value.path);
    if (props.selected === value.path) emit('select', '');
    return;
  }
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
  if (values.some((value) => deleted.has(value.path) || value.remote || value.revision !== values[0]!.revision)) {
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
  closeTab(current.value.path);
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
  for (const value of buffers.values()) {
    if (deleted.has(value.path)) { buffers.delete(value.path); deleted.delete(value.path); }
    else { value.content = value.remote?.content ?? value.baseContent; useLatestBaseline(value); }
  }
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
  >
    <div class="editor-header-bar">
      <div
        class="editor-tabs-strip"
        role="tablist"
        aria-label="已打开文件"
      >
        <div
          v-for="tab in openTabs"
          :key="tab"
          class="editor-tab-item"
          :class="{ 'is-active': selected === tab }"
        >
          <button
            type="button"
            role="tab"
            class="editor-tab-btn"
            :aria-selected="selected === tab"
            :title="tab"
            @click="emit('select', tab)"
          >
            <span
              v-if="buffers.get(tab) && dirty(buffers.get(tab)!)"
              class="dirty-dot"
              title="未保存修改"
            />
            <span class="font-mono-num">{{ tabLabel(tab) }}</span>
          </button>
          <button
            type="button"
            class="editor-tab-close"
            :aria-label="`关闭 ${tabLabel(tab)}`"
            :disabled="busy || !!unresolved"
            @click.stop="closeTab(tab)"
          >
            <X
              :size="11"
              aria-hidden="true"
            />
          </button>
        </div>
        <span
          v-if="!openTabs.length"
          class="editor-empty-tab"
        >选择或创建文件</span>
      </div>
      <div class="editor-actions">
        <Tooltip :text="`${busy ? '正在保存或核对…' : notice} · ${backup}`">
          <span
            role="status"
            class="editor-status-chip font-mono-num"
          >
            <span
              class="ui-status-dot"
              :data-tone="busy ? 'warning' : current && dirty(current) ? 'warning' : 'accent'"
            />
            <span>{{ busy ? '核对中…' : notice }}</span>
          </span>
        </Tooltip>
        <Button
          variant="primary"
          size="small"
          :disabled="!canWrite || busy || !!unresolved || testBusy || testPrompt"
          @click="requestTest"
        >
          <Play
            :size="12"
            aria-hidden="true"
          />
          <span>{{ testTarget === 'challenge' ? '运行挑战与基础回归' : '运行基础测试' }}</span>
        </Button>
        <Button
          variant="ghost"
          size="small"
          :disabled="!current || !canWrite || busy || !!unresolved"
          @click="saveCurrent"
        >
          <Save
            :size="13"
            aria-hidden="true"
          />
          <span>保存文件</span>
        </Button>
        <Button
          variant="ghost"
          size="small"
          class="sr-only-action"
          :disabled="!current || busy || !!unresolved"
          @click="closeFile"
        >
          关闭文件
        </Button>
        <Button
          v-if="unresolved"
          size="small"
          :disabled="busy"
          @click="submit(unresolved)"
        >
          核对原保存
        </Button>
        <Button
          v-if="current && dirty(current)"
          variant="ghost"
          size="small"
          class="discard-btn"
          :disabled="busy || !!unresolved"
          @click="discard(current)"
        >
          丢弃当前草稿
        </Button>
      </div>
    </div>
    <p
      v-if="answeringClosed || props.exercise.state !== 'ACTIVE'"
      role="status"
      class="editor-banner"
    >
      作答已关闭。这里保留本地内容供查看或复制，不会自动保存，也不是最终冻结代码；备份内容可展开查看。
    </p>
    <p
      v-if="error"
      role="alert"
      class="editor-banner editor-banner-error"
    >
      {{ error }}
    </p>
    <div
      v-if="testPrompt"
      class="editor-prompt-card"
      role="group"
      aria-label="运行前保存确认"
    >
      <p>有未保存修改，测试不会使用这些草稿。保存所有已打开文件后运行，或返回编辑。</p>
      <div class="editor-actions">
        <Button
          variant="primary"
          size="small"
          :disabled="busy || !!unresolved || testBusy || !canWrite"
          @click="saveAndTest"
        >
          保存后运行
        </Button>
        <Button
          size="small"
          @click="testPrompt = false"
        >
          返回编辑
        </Button>
      </div>
    </div>
    <div
      v-for="draft in recoverable"
      :key="draft.id"
      class="draft-choice"
    >
      <span>可恢复草稿 · {{ new Date(draft.updatedAt).toLocaleString() }} · 页面 {{ draft.id }}</span>
      <details><summary>查看备份内容</summary><pre>{{ draft.content }}</pre></details>
      <Button
        size="small"
        :disabled="editorLocked || busy"
        @click="restore(draft)"
      >
        恢复此草稿
      </Button>
    </div>
    <div
      v-if="deleted.size"
      class="deleted-drafts-bar"
    >
      <Button
        v-for="path in deleted.keys()"
        :key="path"
        size="small"
        @click="emit('select', path)"
      >
        已删除文件的草稿：{{ path }}
      </Button>
    </div>
    <p
      v-if="current && deleted.has(current.path)"
      role="alert"
      class="editor-banner editor-banner-error"
    >
      正式文件已在修订 {{ deleted.get(current.path) }} 删除；本地草稿已保留，不能直接覆盖保存。请复制草稿后显式新建文件，或丢弃草稿。
    </p>
    <div
      class="editor-surface"
      :class="{ 'conflict-columns': !!current?.remote }"
    >
      <div class="editor-primary-column">
        <h3
          v-if="current?.remote"
          class="conflict-heading"
        >
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
        class="conflict-remote-card"
        aria-label="当前服务端内容"
      >
        <h3 class="conflict-heading">
          当前服务端 · 修订 {{ current.remote.revision }}
        </h3>
        <pre>{{ current.remote.content }}</pre>
        <details><summary>查看编辑基线</summary><pre>{{ current.baseContent }}</pre></details>
        <Button
          size="small"
          :disabled="busy || !!unresolved"
          @click="useLatestBaseline(current); scheduleBackup()"
        >
          保留本地内容，采用此基线
        </Button>
        <p class="conflict-note">
          此操作不提交代码；整理后仍需显式保存。服务器再次变化时会再次冲突。
        </p>
      </section>
    </div>
    <div class="file-actions">
      <div class="file-actions-row">
        <label
          for="file-destination"
          class="file-dest-label"
        >路径</label>
        <input
          id="file-destination"
          v-model="newPath"
          autocomplete="off"
          aria-label="新建或重命名的目标路径"
          placeholder="src/test/java/Example.java"
        >
        <Button
          variant="secondary"
          size="small"
          :disabled="!canWrite || busy || !!unresolved"
          @click="createFile"
        >
          创建文本文件
        </Button>
        <Button
          variant="secondary"
          size="small"
          :disabled="!canWrite || !current || dirty(current) || busy || !!unresolved"
          @click="removeOrRename(true)"
        >
          重命名文件
        </Button>
        <Button
          variant="danger"
          size="small"
          :disabled="!canWrite || !current || dirty(current) || busy || !!unresolved"
          @click="removeOrRename(false)"
        >
          删除文件
        </Button>
      </div>
      <div class="local-backups-row">
        <details class="local-backups-details">
          <summary>本地草稿备份与文件操作说明</summary>
          <p class="file-actions-tip">
            删除或重命名前，先保存或明确丢弃该文件的草稿。
          </p>
          <ClosedDrafts
            :owner="owner"
            :exercise-id="exercise"
            active
          />
        </details>
        <span
          role="status"
          class="backup-inline-status"
        >{{ backup }}</span>
      </div>
    </div>
    <div
      v-if="leaving"
      role="dialog"
      aria-modal="true"
      aria-label="处理未保存内容"
      class="leave-dialog"
    >
      <h3>离开前处理未保存内容</h3>
      <div class="editor-actions">
        <Button
          variant="primary"
          size="small"
          :disabled="busy || !!unresolved || !canWrite"
          @click="leave('save')"
        >
          保存后离开
        </Button>
        <Button
          variant="danger"
          size="small"
          :disabled="busy || !!unresolved"
          @click="leave('discard')"
        >
          丢弃并离开
        </Button>
        <Button
          size="small"
          @click="leave('cancel')"
        >
          取消离开
        </Button>
      </div>
    </div>
  </fieldset>
</template>

<style scoped>
.editor-panel {
  min-width: 0;
  min-inline-size: 0;
  width: 100%;
  flex: 1 1 0%;
  min-height: 0;
  border: 0;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  background: var(--oc-bg);
}

.editor-header-bar {
  flex-shrink: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 6px 10px;
  min-width: 0;
  min-height: 36px;
  padding: 0 10px 0 0;
  background: var(--oc-bg-subtle);
  border-bottom: 1px solid var(--oc-border);
}

.editor-tabs-strip {
  display: flex;
  align-items: stretch;
  min-width: 0;
  flex: 1 1 200px;
  overflow-x: auto;
  scrollbar-width: none;
}

.editor-tab-item {
  display: inline-flex;
  align-items: center;
  border-right: 1px solid var(--oc-border);
  border-top: 2px solid transparent;
  background: transparent;
  max-width: 200px;
}

.editor-tab-item.is-active {
  background: var(--oc-bg);
  border-top-color: var(--oc-accent);
}

.editor-tab-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 4px 8px 4px 12px;
  border: 0;
  background: transparent;
  color: var(--oc-fg-muted);
  font-size: 12px;
  cursor: pointer;
  min-width: 0;
}

.editor-tab-item.is-active .editor-tab-btn {
  color: var(--oc-fg);
  font-weight: 550;
}

.editor-tab-btn span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.editor-tab-close {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  margin-right: 4px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--oc-fg-dim);
  cursor: pointer;
}

.editor-tab-close:hover:not(:disabled) {
  background: var(--oc-surface-2);
  color: var(--oc-fg);
}

.editor-empty-tab {
  padding: 8px 12px;
  font-size: 12px;
  color: var(--oc-fg-dim);
}

.dirty-dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--oc-warning);
  flex-shrink: 0;
}

.editor-status-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11.5px;
  color: var(--oc-fg-muted);
  padding: 2px 6px;
}

.sr-only-action {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  padding: 0;
  min-height: 0;
}

.discard-btn {
  color: var(--oc-danger);
}

.editor-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}

.editor-banner {
  flex-shrink: 0;
  margin: 0;
  padding: 8px 12px;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-surface-2);
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.editor-banner-error {
  color: var(--oc-danger);
  background: var(--oc-danger-bg);
  border-color: var(--oc-danger-border);
}

.editor-prompt-card,
.draft-choice {
  flex-shrink: 0;
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--oc-border-strong);
  background: var(--oc-surface-2);
  font-size: 12.5px;
}

.editor-prompt-card p {
  margin: 0;
}

.deleted-drafts-bar {
  flex-shrink: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
}

.editor-surface {
  flex: 1 1 0%;
  min-height: 260px;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.editor-primary-column {
  flex: 1 1 0%;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.conflict-columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 1px;
  background: var(--oc-border);
}

.conflict-columns .editor-primary-column {
  background: var(--oc-bg);
}

.conflict-heading {
  margin: 0;
  padding: 8px 12px;
  font-size: 12.5px;
  font-weight: 600;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-surface);
}

.conflict-remote-card {
  padding: 12px;
  background: var(--oc-surface);
  display: grid;
  gap: 10px;
  align-content: start;
  overflow: auto;
}

.conflict-note {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.conflict-columns pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 40vh;
  overflow: auto;
  padding: 10px;
  border-radius: var(--radius-control);
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
  font-size: 12px;
}

.file-actions {
  flex-shrink: 0;
  display: grid;
  gap: 6px;
  padding: 7px 12px;
  background: var(--oc-bg-subtle);
  border-top: 1px solid var(--oc-border);
}

.file-actions-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.file-dest-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--oc-fg-muted);
  white-space: nowrap;
}

.file-actions input {
  flex: 1 1 200px;
  min-width: 0;
  padding: 5px 9px;
  font-family: var(--oc-font-mono);
  font-size: 12px;
  color: var(--oc-fg);
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border-input);
  border-radius: var(--radius-control);
}

.local-backups-row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 6px 12px;
}

.local-backups-details {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.backup-inline-status {
  font-size: 11.5px;
  color: var(--oc-fg-dim);
}

.local-backups-details summary {
  cursor: pointer;
}

.file-actions-tip {
  margin: 6px 0 8px;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.leave-dialog {
  flex-shrink: 0;
  padding: 14px 16px;
  background: var(--oc-surface-2);
  border-top: 1px solid var(--oc-border-strong);
  display: grid;
  gap: 10px;
}

.leave-dialog h3 {
  margin: 0;
  font-size: 14px;
}

@media (max-width: 760px) {
  .conflict-columns {
    grid-template-columns: 1fr;
  }
}
</style>

