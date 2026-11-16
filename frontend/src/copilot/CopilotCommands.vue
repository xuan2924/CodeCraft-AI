<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import * as execution from '../workspace/commandApi';
import { message as workspaceMessage } from '../workspace/api';
import CommandOutput from '../workspace/CommandOutput.vue';
import { commandPolicy, errorMessage } from './errors';
import type { Task } from './api';
import * as api from './commandApi';

const props = defineProps<{ owner: string; exerciseId: string; task: Task; credential: string; disabled: boolean }>();
const emit = defineEmits<{ unauthorized: []; changed: [] }>();
const client = useQueryClient();
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.task.id}`);
const root = computed(() => ['copilot-commands', props.owner, props.exerciseId, props.task.id]);
const selected = ref('');
const before = ref<string | null>(null);
const rejection = ref('');
let notifiedRevision = 0n;
watch(scope, () => { selected.value = ''; before.value = null; rejection.value = ''; notifiedRevision = 0n; }, { flush: 'sync' });
const head = useQuery({ queryKey: computed(() => [...root.value, 'history', null]), retry: false, gcTime: 0, refetchInterval: 2000,
  queryFn: ({ signal }) => api.page(props.owner, props.exerciseId, props.task.id, null, signal) });
const older = useQuery({ queryKey: computed(() => [...root.value, 'history', before.value]), enabled: computed(() => before.value !== null), retry: false, gcTime: 0,
  queryFn: ({ signal }) => api.page(props.owner, props.exerciseId, props.task.id, before.value, signal) });
const page = computed(() => before.value === null ? head.data.value : older.data.value);
const detailKey = (id: string) => [...root.value, 'detail', id];
const detail = useQuery({ queryKey: computed(() => detailKey(selected.value)), enabled: computed(() => !!selected.value), retry: false, gcTime: 0,
  queryFn: async ({ signal }) => {
    const { owner, exerciseId, task } = props; const id = selected.value; const key = detailKey(id);
    return api.latest(client.getQueryData<api.CommandProposal>(key), await api.detail(owner, exerciseId, task.id, id, signal));
  }, refetchInterval: query => query.state.data?.state === 'RESOLVED' ? false : 1000 });
watch(() => head.data.value?.commands, values => { if (!selected.value && values?.length) selected.value = values[0]!.id; });
watch(selected, () => { rejection.value = ''; });
const options = computed(() => {
  const values = new Map<string, api.CommandProposal>();
  for (const value of [...(head.data.value?.commands ?? []), ...(page.value?.commands ?? []), ...(detail.data.value ? [detail.data.value] : [])]) {
    values.set(value.id, api.latest(values.get(value.id), value));
  }
  return [...values.values()];
});
const current = computed(() => detail.data.value);
const executionKey = computed(() => ['workspace-commands', props.owner, props.exerciseId, current.value?.workspaceInstanceId ?? '', 'detail', current.value?.commandTaskId ?? '']);
const output = useQuery({ queryKey: executionKey, enabled: computed(() => !!current.value?.commandTaskId), retry: false, gcTime: 0,
  queryFn: async ({ signal }) => {
    const source = current.value!; const owner = props.owner; const key = executionKey.value;
    const value = await execution.detail(owner, source.exerciseId, source.workspaceInstanceId, source.commandTaskId!, signal);
    if (value.command !== source.command || value.directory !== source.directory || value.revision !== source.baseRevision) throw new ApiError(502, 'INVALID_RESPONSE');
    return execution.latest(client.getQueryData<execution.CommandTask>(key), value);
  }, refetchInterval: query => (query.state.error instanceof ApiError && query.state.error.status === 410)
    || (query.state.data?.quiescent && query.state.data.synchronization !== 'PENDING'
      && query.state.data.cleanup === 'SUCCEEDED' && query.state.data.copyCleanup === 'SUCCEEDED') ? false : 1000 });
const visibleOutput = computed(() => output.data.value ?? current.value?.result?.result ?? null);
watch([head.error, older.error, detail.error, output.error], failures => { if (failures.some(commandPolicy.identityFailure)) emit('unauthorized'); });
watch([head.data, detail.data, output.data], () => {
  const candidates = [...(head.data.value?.commands.map(value => value.result?.result) ?? []), current.value?.result?.result, output.data.value];
  for (const value of candidates) {
    if (!value?.changed || value.synchronizedRevision === null) continue;
    const revision = BigInt(value.synchronizedRevision);
    if (revision > notifiedRevision) { notifiedRevision = revision; emit('changed'); }
  }
});
const decision = useCommand<api.CommandProposal>(() => scope.value, value => {
  client.setQueryData<api.CommandProposal>(detailKey(value.id), current => api.latest(current, value));
  void client.invalidateQueries({ queryKey: root.value });
}, commandPolicy, () => emit('unauthorized'));
const canDecide = computed(() => !props.disabled && !!props.credential && !props.task.stopRequested
  && props.task.state === 'WAITING_APPROVAL' && current.value?.state === 'PENDING' && current.value.mode === 'ASK'
  && !decision.pending.value && !decision.uncertain.value);
const rejectionBytes = computed(() => new globalThis.TextEncoder().encode(rejection.value).length);
function decide(kind: 'APPROVE' | 'REJECT') {
  if (!canDecide.value || !current.value || (kind === 'REJECT' && rejectionBytes.value > 2048)) return;
  const { owner, exerciseId, task } = props; const id = current.value.id;
  const input: api.Decision = { decision: kind, reason: kind === 'REJECT' ? rejection.value : null };
  void decision.submit((key, signal) => api.decide(owner, exerciseId, task.id, id, input, key, props.credential, signal));
}
</script>

<template>
  <section
    class="copilot-commands"
    aria-label="Copilot Bash 命令"
    :style="{ borderColor: 'var(--border)' }"
  >
    <h3>Copilot 开发命令</h3>
    <p
      v-if="head.isPending.value"
      role="status"
    >
      正在读取命令提案…
    </p>
    <p
      v-if="head.isError.value"
      role="alert"
    >
      {{ errorMessage(head.error.value) }} <Button @click="head.refetch()">
        重试命令历史
      </Button>
    </p>
    <p v-if="head.data.value?.commands.length === 0">
      此任务尚未提出 Bash 命令。
    </p>
    <label v-if="options.length">选择命令<select
      v-model="selected"
      aria-label="选择 Copilot Bash 命令"
    >
      <option
        v-for="item in options"
        :key="item.id"
        :value="item.id"
      >#{{ item.sequence }} · {{ item.state }} · {{ item.command.slice(0, 80) }}</option>
    </select></label>
    <Button
      v-if="page?.nextBefore"
      :disabled="older.isFetching.value"
      @click="before = page!.nextBefore"
    >
      更早的命令提案
    </Button>
    <Button
      v-if="before"
      @click="before = null"
    >
      返回最近命令
    </Button>
    <p
      v-if="before && older.isPending.value"
      role="status"
    >
      正在读取更早的命令…
    </p>
    <p
      v-if="older.isError.value"
      role="alert"
    >
      {{ errorMessage(older.error.value) }} <Button @click="older.refetch()">
        重试历史页
      </Button>
    </p>
    <p
      v-if="selected && detail.isPending.value"
      role="status"
    >
      正在核对命令状态…
    </p>
    <p
      v-if="detail.isError.value"
      role="alert"
    >
      {{ errorMessage(detail.error.value) }} <Button @click="detail.refetch()">
        核对命令状态
      </Button>
    </p>
    <template v-if="current">
      <p>{{ current.mode }} · 登记模式版本 {{ current.modeVersion }} · 基线修订 {{ current.baseRevision }}</p>
      <pre>/project/{{ current.directory }} $ {{ current.command }}</pre>
      <p
        v-if="current.state === 'PENDING'"
        role="status"
      >
        {{ current.mode === 'AUTO' ? '正在按登记的 AUTO 模式核对准入。' : '等待批准具体命令；批准不代表预先批准未知文件 Diff。' }}
      </p>
      <template v-if="current.state === 'PENDING' && current.mode === 'ASK'">
        <label>拒绝理由（可选）<textarea
          v-model="rejection"
          aria-label="命令拒绝理由"
          :disabled="!canDecide"
          maxlength="2048"
        /></label>
        <small>{{ rejectionBytes }} / 2048 UTF-8 字节</small>
        <div class="actions">
          <Button
            :disabled="!canDecide"
            @click="decide('APPROVE')"
          >
            批准此命令
          </Button>
          <Button
            :disabled="!canDecide || rejectionBytes > 2048"
            @click="decide('REJECT')"
          >
            拒绝此命令
          </Button>
        </div>
      </template>
      <p
        v-if="current.state === 'WAITING'"
        role="status"
      >
        等待所属命令与文件变化收敛。上方“停止 Copilot”会联动此命令，不停止独立手动命令。
      </p>
      <p
        v-if="current.result"
        role="status"
      >
        {{ current.result.code }}<span v-if="current.result.reason"> · {{ current.result.reason }}</span>
      </p>
      <p
        v-if="current.commandTaskId && output.isPending.value"
        role="status"
      >
        正在读取实际命令输出…
      </p>
      <p
        v-if="output.isError.value"
        role="alert"
      >
        {{ workspaceMessage(output.error.value) }} <Button @click="output.refetch()">
          核对原执行记录
        </Button>
      </p>
      <p v-if="!output.data.value && current.result?.result">
        下方为父工具保留的输出节选，不是重新执行；完整执行记录可能已按历史策略清理。
      </p>
      <p v-if="!output.data.value && current.result?.logTruncated">
        模型节选省略 {{ current.result.omittedLogBytes }} 字节，与原始输出缺失分别记录。
      </p>
      <CommandOutput
        v-if="visibleOutput"
        :owner="owner"
        :task="visibleOutput"
        @unauthorized="emit('unauthorized')"
      />
    </template>
    <p
      v-if="decision.error.value"
      role="alert"
    >
      {{ decision.error.value }}
    </p>
    <Button
      v-if="decision.uncertain.value"
      :disabled="decision.pending.value"
      @click="decision.retry()"
    >
      核对原命令决定（不会批准其他历史项）
    </Button>
  </section>
</template>

<style scoped>
.copilot-commands {
  display: grid;
  gap: 10px;
  min-width: 0;
  border: 1px solid var(--oc-border) !important;
  border-radius: 10px;
  background: var(--oc-surface-2);
  padding: 14px;
}
h3 { margin: 0; font-size: 13.5px; font-weight: 600; }
p, small { margin: 0; font-size: 12.5px; color: var(--oc-fg-muted); line-height: 1.55; }
label { display: grid; gap: 6px; font-size: 12.5px; }
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin: 0;
  padding: 10px;
  border-radius: 8px;
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
  font-size: 12px;
}
textarea, select {
  box-sizing: border-box;
  width: 100%;
  padding: 8px 10px;
  font: inherit;
  color: var(--oc-fg);
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border-input);
  border-radius: 8px;
}
.actions { display: flex; flex-wrap: wrap; gap: 8px; }
</style>
