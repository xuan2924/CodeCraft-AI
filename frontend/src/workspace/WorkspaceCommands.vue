<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { ApiError } from '../api/request';
import { useCommand, type CommandFailurePolicy } from '../api/useCommand';
import { message, type Exercise } from './api';
import * as api from './commandApi';
import CommandOutput from './CommandOutput.vue';
import { stateLabels } from './commandPresentation';
const props = defineProps<{ owner: string; exercise: Exercise; credential: string; disabled: boolean; prepare: () => Promise<string | null> }>();
const emit = defineEmits<{ changed: []; unauthorized: []; locked: [value: boolean] }>();
const client = useQueryClient();
const scope = computed(() => `${props.owner}/${props.exercise.exerciseId}/${props.exercise.workspaceInstanceId}`);
const root = computed(() => ['workspace-commands', props.owner, props.exercise.exerciseId, props.exercise.workspaceInstanceId]);
const command = ref('');
const directory = ref('.');
const selected = ref('');
const cursor = ref<string | null>(null);
const preparing = ref(false);
const notice = ref('');
let disposed = false;
let notifiedRevision = 0n;
onScopeDispose(() => { disposed = true; });
watch(scope, () => {
  selected.value = ''; cursor.value = null; command.value = ''; directory.value = '.';
  preparing.value = false; notice.value = ''; notifiedRevision = 0n;
}, { flush: 'sync' });
const policy: CommandFailurePolicy = {
  definitiveRejection: failure => failure instanceof ApiError && [400, 401, 403, 404, 409, 410, 429].includes(failure.status),
  identityFailure: failure => failure instanceof ApiError && (failure.status === 401 || ['PASSWORD_CHANGE_REQUIRED', 'STALE_CONTROL'].includes(failure.code)),
  errorMessage: message,
};
const head = useQuery({ queryKey: computed(() => [...root.value, 'history', null]), retry: false, gcTime: 0, refetchInterval: 2000,
  queryFn: ({ signal }) => api.history(props.owner, props.exercise.exerciseId, props.exercise.workspaceInstanceId, null, signal) });
const older = useQuery({ queryKey: computed(() => [...root.value, 'history', cursor.value]), enabled: computed(() => cursor.value !== null), retry: false, gcTime: 0,
  queryFn: ({ signal }) => api.history(props.owner, props.exercise.exerciseId, props.exercise.workspaceInstanceId, cursor.value, signal) });
const page = computed(() => cursor.value === null ? head.data.value : older.data.value);
const detailKey = (id: string) => [...root.value, 'detail', id];
const detail = useQuery({ queryKey: computed(() => detailKey(selected.value)), enabled: computed(() => !!selected.value), retry: false, gcTime: 0,
  queryFn: async ({ signal }) => {
    const { owner, exercise } = props;
    const id = selected.value;
    const key = detailKey(id);
    const result = await api.detail(owner, exercise.exerciseId, exercise.workspaceInstanceId, id, signal);
    return api.latest(client.getQueryData<api.CommandTask>(key), result);
  },
  refetchInterval: query => query.state.data?.quiescent && query.state.data.synchronization !== 'PENDING'
    && query.state.data.cleanup === 'SUCCEEDED' && query.state.data.copyCleanup === 'SUCCEEDED' ? false : 1000,
});
watch(() => head.data.value?.commands, tasks => { if (!selected.value && tasks?.length) selected.value = tasks[0]!.id; });
const options = computed(() => {
  const tasks = [...(head.data.value?.commands ?? []), ...(page.value?.commands ?? []), ...(detail.data.value ? [detail.data.value] : [])];
  return [...new Map(tasks.map(task => [task.id, task])).values()];
});
watch([head.error, older.error, detail.error], failures => { if (failures.some(policy.identityFailure)) emit('unauthorized'); });
watch([head.data, detail.data], () => {
  const candidates = [...(head.data.value?.commands ?? []), ...(detail.data.value ? [detail.data.value] : [])];
  const revision = candidates.reduce((latest, task) => task.changed && task.synchronizedRevision !== null && BigInt(task.synchronizedRevision) > latest
    ? BigInt(task.synchronizedRevision) : latest, 0n);
  if (revision > notifiedRevision && revision > BigInt(props.exercise.revision ?? '0')) {
    notifiedRevision = revision; emit('changed');
  }
});
function observed(result: api.CommandOutcome, choose: boolean) {
  if ('retired' in result) { notice.value = '原命令记录已清理，已核对原意图；没有重新执行命令。'; return; }
  client.setQueryData<api.CommandTask>(detailKey(result.id), current => api.latest(current, result));
  if (choose) { selected.value = result.id; cursor.value = null; }
  void head.refetch();
}
const start = useCommand<api.CommandOutcome>(() => scope.value, result => observed(result, true), policy, () => emit('unauthorized'));
const stop = useCommand<api.CommandOutcome>(() => scope.value, result => observed(result, false), policy, () => emit('unauthorized'));
const busy = computed(() => preparing.value || start.pending.value || start.uncertain.value || head.isPending.value || head.isError.value
  || options.value.some(task => !task.quiescent || task.synchronization === 'PENDING' || task.cleanup !== 'SUCCEEDED' || task.copyCleanup !== 'SUCCEEDED'));
watch(busy, value => emit('locked', value), { immediate: true });
const commandBytes = computed(() => new globalThis.TextEncoder().encode(command.value).length);
const mayStart = computed(() => !props.disabled && !!props.credential && props.exercise.state === 'ACTIVE' && !busy.value
  && command.value.trim().length > 0 && commandBytes.value <= 4096 && directory.value.length > 0);
async function execute() {
  if (!mayStart.value) return;
  const before = scope.value;
  const text = command.value;
  const cwd = directory.value;
  const { owner, exercise } = props;
  preparing.value = true; notice.value = '';
  try {
    const revision = await props.prepare();
    if (disposed || scope.value !== before || revision === null || props.disabled || !props.credential) return;
    const input = { command: text, directory: cwd, expectedRevision: revision };
    await start.submit((key, signal) => api.start(owner, exercise.exerciseId, exercise.workspaceInstanceId, input, key, props.credential, signal));
  } catch (failure) {
    if (!disposed && scope.value === before) notice.value = message(failure);
  } finally { if (!disposed && scope.value === before) preparing.value = false; }
}
function requestStop() {
  const task = detail.data.value;
  if (!task || task.quiescent || task.stopRequested || stop.pending.value || stop.uncertain.value || (props.exercise.state === 'ACTIVE' && !props.credential)) return;
  const { owner, exercise } = props;
  void stop.submit((key, signal) => api.stop(owner, exercise.exerciseId, exercise.workspaceInstanceId, task.id, key, props.credential, signal));
}
</script>

<template>
  <section
    class="commands"
    aria-label="联网 Bash"
  >
    <header class="cmd-header">
      <div class="cmd-title">
        <h3>Bash · 联网开发命令</h3>
        <details class="cmd-rules">
          <summary>执行限制说明</summary>
          <p>只在 Docker 内执行，非平台权威测试。依赖请放 <code>/cache</code>；<code>target</code>、<code>node_modules</code> 执行后清理。受版本超时、CPU、内存与进程数限制；可写副本上限 128 MiB / 20,000 目录项；执行前先保存当前草稿。</p>
        </details>
      </div>
      <span class="cmd-bytes">{{ commandBytes }} / 4096 B</span>
    </header>
    <form
      class="cmd-form"
      @submit.prevent="execute"
    >
      <div class="cmd-cwd-row">
        <span class="cmd-cwd-prefix">/project/</span>
        <input
          v-model="directory"
          aria-label="Bash 工作目录"
          maxlength="1024"
          placeholder="."
          :disabled="start.pending.value || start.uncertain.value || preparing"
        >
        <Button
          type="submit"
          variant="primary"
          size="small"
          :disabled="!mayStart"
        >
          {{ preparing || start.pending.value ? '正在受理…' : '执行 Bash' }}
        </Button>
      </div>
      <textarea
        v-model="command"
        aria-label="Bash 命令"
        rows="3"
        maxlength="4096"
        spellcheck="false"
        placeholder="输入要执行的 Bash 命令…"
        :disabled="start.pending.value || start.uncertain.value || preparing"
      />
    </form>
    <p
      v-if="notice"
      role="status"
      class="cmd-status"
    >
      {{ notice }}
    </p>
    <p
      v-if="start.error.value"
      role="alert"
      class="cmd-alert"
    >
      {{ start.error.value }}
    </p>
    <Button
      v-if="start.uncertain.value"
      size="small"
      :disabled="start.pending.value"
      @click="start.retry()"
    >
      使用原请求键核对，不重复执行
    </Button>
    <p
      v-if="head.isPending.value"
      role="status"
      class="cmd-status"
    >
      正在读取命令记录…
    </p>
    <p
      v-if="head.isError.value"
      role="alert"
      class="cmd-alert"
    >
      {{ message(head.error.value) }}
      <Button
        size="small"
        @click="head.refetch()"
      >
        重新读取记录
      </Button>
    </p>
    <p
      v-if="head.data.value?.commands.length === 0"
      class="cmd-empty"
    >
      还没有开发命令。
    </p>
    <div
      v-if="options.length"
      class="cmd-history-bar"
    >
      <label class="cmd-select-label">
        <span>执行记录</span>
        <select
          v-model="selected"
          aria-label="选择 Bash 执行记录"
          :disabled="start.pending.value"
        >
          <option
            v-for="task in options"
            :key="task.id"
            :value="task.id"
          >{{ new Date(task.createdAt).toLocaleTimeString() }} · {{ stateLabels[task.state] }} · {{ task.id.slice(0, 8) }}</option>
        </select>
      </label>
      <div class="actions">
        <Button
          v-if="page?.nextCursor"
          size="small"
          @click="cursor = page.nextCursor"
        >
          更早的记录
        </Button>
        <Button
          v-if="cursor"
          size="small"
          @click="cursor = null"
        >
          返回最近记录
        </Button>
      </div>
    </div>
    <p
      v-if="cursor && older.isPending.value"
      role="status"
      class="cmd-status"
    >
      正在读取更早的记录…
    </p>
    <p
      v-if="cursor && older.isError.value"
      role="alert"
      class="cmd-alert"
    >
      {{ message(older.error.value) }}
      <Button
        size="small"
        @click="older.refetch()"
      >
        重试读取历史
      </Button>
    </p>
    <p
      v-if="selected && detail.isPending.value"
      role="status"
      class="cmd-status"
    >
      正在读取命令输出…
    </p>
    <p
      v-if="detail.isError.value"
      role="alert"
      class="cmd-alert"
    >
      {{ message(detail.error.value) }}
      <Button
        size="small"
        @click="detail.refetch()"
      >
        重新核对记录
      </Button>
    </p>
    <CommandOutput
      v-if="detail.data.value"
      :owner="owner"
      :task="detail.data.value"
      @unauthorized="emit('unauthorized')"
    >
      <template #controls>
        <Button
          v-if="!detail.data.value.quiescent"
          variant="danger"
          size="small"
          :disabled="detail.data.value.stopRequested || stop.pending.value || stop.uncertain.value || (exercise.state === 'ACTIVE' && !credential)"
          @click="requestStop"
        >
          停止实际命令
        </Button>
        <p
          v-if="stop.error.value"
          role="alert"
          class="cmd-alert"
        >
          {{ stop.error.value }}
        </p>
        <Button
          v-if="stop.uncertain.value"
          size="small"
          :disabled="stop.pending.value"
          @click="stop.retry()"
        >
          核对原停止请求
        </Button>
      </template>
    </CommandOutput>
    <small class="cmd-footnote">保留最近 20 条可清理已完成输出；刷新仅读取原执行身份。</small>
  </section>
</template>

<style scoped>
.commands {
  display: grid;
  gap: 10px;
  min-width: 0;
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-panel);
  padding: 14px;
  background: var(--oc-surface);
}

.cmd-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.cmd-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}

h3 {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
}

.cmd-rules {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.cmd-rules summary {
  cursor: pointer;
}

.cmd-rules p {
  margin: 6px 0 0;
  line-height: 1.5;
}

.cmd-bytes {
  font-family: var(--oc-font-mono);
  font-size: 11.5px;
  color: var(--oc-fg-muted);
  font-variant-numeric: tabular-nums;
}

.cmd-form {
  display: grid;
  gap: 8px;
}

.cmd-cwd-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.cmd-cwd-prefix {
  font-family: var(--oc-font-mono);
  font-size: 12px;
  color: var(--oc-accent);
  padding: 6px 8px;
  border-radius: var(--radius-control);
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
}

.cmd-cwd-row input {
  flex: 1 1 140px;
  min-width: 0;
  padding: 6px 10px;
  font-family: var(--oc-font-mono);
  font-size: 12.5px;
}

textarea {
  width: 100%;
  font-family: var(--oc-font-mono);
  font-size: 12.5px;
  resize: vertical;
}

.cmd-history-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.cmd-select-label {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1 1 220px;
  min-width: 0;
  font-size: 12.5px;
  font-weight: 550;
}

.cmd-select-label span {
  white-space: nowrap;
  color: var(--oc-fg-muted);
}

.cmd-select-label select {
  flex: 1 1 0%;
  min-width: 0;
  padding: 6px 10px;
  font-size: 12.5px;
}

.cmd-status,
.cmd-empty,
.cmd-footnote {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
  line-height: 1.5;
}

.cmd-alert {
  margin: 0;
  padding: 8px 10px;
  border-radius: var(--radius-control);
  background: var(--oc-danger-bg);
  border: 1px solid var(--oc-danger-border);
  color: var(--oc-danger);
  font-size: 12.5px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

code {
  overflow-wrap: anywhere;
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--oc-surface-2);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
