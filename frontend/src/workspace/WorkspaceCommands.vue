<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { NButton, useThemeVars } from 'naive-ui';
import { ApiError } from '../api/request';
import { useCommand, type CommandFailurePolicy } from '../api/useCommand';
import { message, type Exercise } from './api';
import * as api from './commandApi';
import CommandOutput from './CommandOutput.vue';
import { stateLabels } from './commandPresentation';
const props = defineProps<{ owner: string; exercise: Exercise; credential: string; disabled: boolean; prepare: () => Promise<string | null> }>();
const emit = defineEmits<{ changed: []; unauthorized: []; locked: [value: boolean] }>();
const theme = useThemeVars();
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
    :style="{ borderColor: theme.borderColor }"
    aria-label="联网 Bash"
  >
    <h3>Bash · 联网开发命令</h3>
    <p>命令只在 Docker 内执行，不是平台权威测试。依赖请放在 <code>/cache</code>；<code>target</code>、<code>node_modules</code> 为可删除的临时构建目录，执行收敛后清理。</p>
    <p>命令受题目版本固定的超时、CPU、内存和进程数限制；可写副本巡检上限 128 MiB / 20,000 目录项。不是交互式终端，执行前会确认保存当前草稿。</p>
    <form @submit.prevent="execute">
      <label>项目内工作目录（项目根为 .）<input
        v-model="directory"
        aria-label="Bash 工作目录"
        maxlength="1024"
        :disabled="start.pending.value || start.uncertain.value || preparing"
      ></label>
      <label>要执行的 Bash 命令<textarea
        v-model="command"
        aria-label="Bash 命令"
        rows="4"
        maxlength="4096"
        spellcheck="false"
        :disabled="start.pending.value || start.uncertain.value || preparing"
      /></label>
      <small>{{ commandBytes }} / 4096 字节 · 将在 /project/{{ directory }} 执行</small>
      <NButton
        attr-type="submit"
        :disabled="!mayStart"
      >
        {{ preparing || start.pending.value ? '正在受理…' : '执行 Bash' }}
      </NButton>
    </form>
    <p
      v-if="notice"
      role="status"
    >
      {{ notice }}
    </p>
    <p
      v-if="start.error.value"
      role="alert"
    >
      {{ start.error.value }}
    </p>
    <NButton
      v-if="start.uncertain.value"
      :disabled="start.pending.value"
      @click="start.retry()"
    >
      使用原请求键核对，不重复执行
    </NButton>
    <p
      v-if="head.isPending.value"
      role="status"
    >
      正在读取命令记录…
    </p>
    <p
      v-if="head.isError.value"
      role="alert"
    >
      {{ message(head.error.value) }} <NButton @click="head.refetch()">
        重新读取记录
      </NButton>
    </p>
    <p v-if="head.data.value?.commands.length === 0">
      还没有开发命令。
    </p>
    <label v-if="options.length">执行记录
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
      <NButton
        v-if="page?.nextCursor"
        @click="cursor = page.nextCursor"
      >
        更早的记录
      </NButton>
      <NButton
        v-if="cursor"
        @click="cursor = null"
      >
        返回最近记录
      </NButton>
    </div>
    <p
      v-if="cursor && older.isPending.value"
      role="status"
    >
      正在读取更早的记录…
    </p>
    <p
      v-if="cursor && older.isError.value"
      role="alert"
    >
      {{ message(older.error.value) }} <NButton @click="older.refetch()">
        重试读取历史
      </NButton>
    </p>
    <p
      v-if="selected && detail.isPending.value"
      role="status"
    >
      正在读取命令输出…
    </p>
    <p
      v-if="detail.isError.value"
      role="alert"
    >
      {{ message(detail.error.value) }} <NButton @click="detail.refetch()">
        重新核对记录
      </NButton>
    </p>
    <CommandOutput
      v-if="detail.data.value"
      :owner="owner"
      :task="detail.data.value"
      @unauthorized="emit('unauthorized')"
    >
      <template #controls>
        <NButton
          v-if="!detail.data.value.quiescent"
          :disabled="detail.data.value.stopRequested || stop.pending.value || stop.uncertain.value || (exercise.state === 'ACTIVE' && !credential)"
          @click="requestStop"
        >
          停止实际命令
        </NButton>
        <p
          v-if="stop.error.value"
          role="alert"
        >
          {{ stop.error.value }}
        </p>
        <NButton
          v-if="stop.uncertain.value"
          :disabled="stop.pending.value"
          @click="stop.retry()"
        >
          核对原停止请求
        </NButton>
      </template>
    </CommandOutput>
    <small>最多保留最近 20 条可清理的已完成输出，容量不足时可提前精简；未知执行、同步冲突和被拒绝的候选变化不会被普通历史淘汰。刷新只读取原执行身份。</small>
  </section>
</template>

<style scoped>
.commands { display: grid; gap: 8px; min-width: 0; border: 1px solid; border-radius: 6px; padding: 12px; }
h3, p { margin: 0; }
form, label { display: grid; gap: 6px; }
input, textarea, select { box-sizing: border-box; width: 100%; padding: 6px; font: inherit; color: inherit; background: transparent; }
textarea, .command-preview { font-family: Consolas, 'Cascadia Mono', monospace; }
.command-preview { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; max-height: 12rem; overflow: auto; }
code { overflow-wrap: anywhere; }
.actions { display: flex; gap: 8px; }
</style>
