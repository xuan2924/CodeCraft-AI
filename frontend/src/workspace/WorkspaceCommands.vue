<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { CornerDownLeft, Folder, Square } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import Tooltip from '../components/ui/Tooltip.vue';
import { ApiError } from '../api/request';
import { useCommand, type CommandFailurePolicy } from '../api/useCommand';
import { formatShortTimestamp } from '../copilot/feeConsent';
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
const editingDirectory = ref(false);
const historyIndex = ref(-1);
const selected = ref('');
const cursor = ref<string | null>(null);
const preparing = ref(false);
const notice = ref('');
let disposed = false;
let notifiedRevision = 0n;
onScopeDispose(() => { disposed = true; });
watch(scope, () => {
  selected.value = ''; cursor.value = null; command.value = ''; directory.value = '.';
  editingDirectory.value = false; historyIndex.value = -1;
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
const recentCommandStrings = computed(() => options.value.map(item => item.command).filter(Boolean));
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
function cycleHistory(direction: -1 | 1) {
  const items = recentCommandStrings.value;
  if (!items.length || command.value.includes('\n')) return;
  const next = historyIndex.value + direction;
  if (next < 0) { historyIndex.value = -1; command.value = ''; return; }
  if (next < items.length) { historyIndex.value = next; command.value = items[next]!; }
}
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
    if (!disposed && scope.value === before && !start.error.value && !start.uncertain.value) {
      command.value = '';
      historyIndex.value = -1;
    }
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
    class="terminal-shell"
    aria-label="开发终端"
  >
    <div class="terminal-topbar">
      <div class="terminal-history-nav">
        <select
          v-if="options.length"
          v-model="selected"
          class="terminal-select font-mono-num"
          aria-label="选择 Bash 执行记录"
          :disabled="start.pending.value"
        >
          <option
            v-for="task in options"
            :key="task.id"
            :value="task.id"
          >{{ formatShortTimestamp(task.createdAt) }} · {{ stateLabels[task.state] }} · {{ task.command.slice(0, 28) }}</option>
        </select>
        <span
          v-else
          class="terminal-idle-label font-mono-num"
        >/project</span>
        <Button
          v-if="page?.nextCursor"
          variant="ghost"
          size="small"
          @click="cursor = page.nextCursor"
        >
          更早
        </Button>
        <Button
          v-if="cursor"
          variant="ghost"
          size="small"
          @click="cursor = null"
        >
          最新
        </Button>
      </div>
      <div class="terminal-top-actions">
        <Button
          v-if="detail.data.value && !detail.data.value.quiescent"
          variant="danger"
          size="small"
          :disabled="detail.data.value.stopRequested || stop.pending.value || stop.uncertain.value || (exercise.state === 'ACTIVE' && !credential)"
          @click="requestStop"
        >
          <Square
            :size="11"
            aria-hidden="true"
          />
          <span>停止实际命令</span>
        </Button>
        <Tooltip text="在 Docker 沙箱执行（非权威测试）；依赖缓存目录 /cache，单条命令上限 4 KiB。执行前自动保存当前文件。">
          <button
            type="button"
            class="terminal-help-btn"
            aria-label="终端环境说明"
          >
            ?
          </button>
        </Tooltip>
      </div>
    </div>

    <div class="terminal-stream">
      <p
        v-if="notice"
        role="status"
        class="term-status"
      >
        {{ notice }}
      </p>
      <p
        v-if="start.error.value"
        role="alert"
        class="term-alert"
      >
        {{ start.error.value }}
      </p>
      <div
        v-if="start.uncertain.value"
        class="term-alert-row"
      >
        <span class="term-alert">受理结果未确认</span>
        <Button
          size="small"
          :disabled="start.pending.value"
          @click="start.retry()"
        >
          使用原请求键核对，不重复执行
        </Button>
      </div>
      <p
        v-if="head.isPending.value"
        role="status"
        class="term-status"
      >
        正在连接终端历史…
      </p>
      <div
        v-else-if="head.isError.value"
        role="alert"
        class="term-alert-row"
      >
        <span class="term-alert">{{ message(head.error.value) }}</span>
        <Button
          variant="ghost"
          size="small"
          @click="head.refetch()"
        >
          重试
        </Button>
      </div>
      <div
        v-else-if="head.data.value?.commands.length === 0 && !detail.data.value"
        class="term-empty font-mono-num"
      >
        $ 输入命令并按 Enter 执行（支持 ↑ / ↓ 切换历史）
      </div>
      <p
        v-if="cursor && older.isPending.value"
        role="status"
        class="term-status"
      >
        正在加载更早记录…
      </p>
      <div
        v-if="cursor && older.isError.value"
        role="alert"
        class="term-alert-row"
      >
        <span class="term-alert">{{ message(older.error.value) }}</span>
        <Button
          variant="ghost"
          size="small"
          @click="older.refetch()"
        >
          重试
        </Button>
      </div>
      <p
        v-if="selected && detail.isPending.value"
        role="status"
        class="term-status"
      >
        正在读取命令输出…
      </p>
      <div
        v-if="detail.isError.value"
        role="alert"
        class="term-alert-row"
      >
        <span class="term-alert">{{ message(detail.error.value) }}</span>
        <Button
          variant="ghost"
          size="small"
          @click="detail.refetch()"
        >
          重新核对
        </Button>
      </div>
      <CommandOutput
        v-if="detail.data.value"
        :owner="owner"
        :task="detail.data.value"
        @unauthorized="emit('unauthorized')"
      >
        <template #controls>
          <p
            v-if="stop.error.value"
            role="alert"
            class="term-alert"
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
    </div>

    <form
      class="terminal-prompt-bar"
      @submit.prevent="execute"
    >
      <button
        type="button"
        class="cwd-chip font-mono-num"
        :title="editingDirectory ? '收起工作目录' : '修改工作目录（默认 /project）'"
        @click="editingDirectory = !editingDirectory"
      >
        <Folder
          :size="11"
          aria-hidden="true"
        />
        <span>{{ directory === '.' ? '/project' : `/project/${directory}` }}</span>
      </button>
      <input
        v-if="editingDirectory"
        v-model="directory"
        class="cwd-inline-input font-mono-num"
        aria-label="Bash 工作目录"
        maxlength="1024"
        placeholder="."
        :disabled="start.pending.value || start.uncertain.value || preparing"
      >
      <span
        class="prompt-glyph font-mono-num"
        aria-hidden="true"
      >$</span>
      <textarea
        v-model="command"
        class="terminal-input font-mono-num"
        aria-label="Bash 命令"
        rows="1"
        maxlength="4096"
        spellcheck="false"
        placeholder="输入命令，Enter 执行…"
        :disabled="start.pending.value || start.uncertain.value || preparing"
        @keydown.enter.exact.prevent="execute"
        @keydown.up.exact="cycleHistory(1)"
        @keydown.down.exact="cycleHistory(-1)"
      />
      <span
        v-if="commandBytes > 3072"
        class="byte-limit-badge font-mono-num"
        :class="{ 'is-over': commandBytes > 4096 }"
      >
        {{ commandBytes }} / 4096 B
      </span>
      <Button
        type="submit"
        variant="ghost"
        size="icon"
        class="run-cmd-btn"
        aria-label="执行命令"
        :disabled="!mayStart"
      >
        <CornerDownLeft
          :size="14"
          aria-hidden="true"
        />
      </Button>
    </form>
  </section>
</template>

<style scoped>
.terminal-shell {
  flex: 1;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--oc-bg-subtle);
}

.terminal-topbar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 32px;
  padding: 2px 10px;
  border-bottom: 1px solid var(--oc-border);
}

.terminal-history-nav {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex: 1;
}

.terminal-select {
  min-width: 0;
  max-width: 340px;
  min-height: 24px;
  padding: 2px 8px;
  font-size: 11.5px;
  border-radius: 5px;
}

.terminal-idle-label {
  font-size: 11.5px;
  color: var(--oc-fg-dim);
}

.terminal-top-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.terminal-help-btn {
  width: 20px;
  height: 20px;
  border-radius: 999px;
  border: 1px solid var(--oc-border);
  background: transparent;
  color: var(--oc-fg-dim);
  font-size: 11px;
  cursor: help;
}

.terminal-stream {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.term-empty {
  font-size: 12px;
  color: var(--oc-fg-dim);
  padding: 6px 0;
}

.term-status {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.term-alert {
  margin: 0;
  font-size: 12px;
  color: var(--oc-danger);
}

.term-alert-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.terminal-prompt-bar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-top: 1px solid var(--oc-border);
  background: var(--oc-bg);
}

.cwd-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 2px 6px;
  border-radius: 4px;
  border: 1px solid var(--oc-border);
  background: transparent;
  color: var(--oc-fg-dim);
  font-size: 11px;
  cursor: pointer;
  flex-shrink: 0;
}

.cwd-chip:hover {
  color: var(--oc-fg);
  border-color: var(--oc-border-strong);
}

.cwd-inline-input {
  width: 96px;
  min-height: 22px;
  padding: 1px 6px;
  font-size: 11px;
  flex-shrink: 0;
}

.prompt-glyph {
  color: var(--oc-accent);
  font-size: 12.5px;
  font-weight: 600;
  flex-shrink: 0;
}

.terminal-input {
  flex: 1;
  min-width: 0;
  min-height: 24px;
  max-height: 76px;
  padding: 3px 4px;
  border: 0 !important;
  border-radius: 0;
  background: transparent !important;
  color: var(--oc-fg);
  font-size: 12.5px;
  line-height: 1.45;
  resize: none;
  box-shadow: none !important;
}

.terminal-input:focus {
  outline: none;
}

.byte-limit-badge {
  font-size: 11px;
  color: var(--oc-warning);
  flex-shrink: 0;
}

.byte-limit-badge.is-over {
  color: var(--oc-danger);
}

.run-cmd-btn {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
}
</style>
