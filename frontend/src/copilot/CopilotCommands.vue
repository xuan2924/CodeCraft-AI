<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { Terminal } from '@lucide/vue';
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
const head = useQuery({
  queryKey: computed(() => [...root.value, 'history', null]),
  retry: false,
  gcTime: 0,
  refetchInterval: 2000,
  queryFn: ({ signal }) => api.page(props.owner, props.exerciseId, props.task.id, null, signal),
});
const older = useQuery({
  queryKey: computed(() => [...root.value, 'history', before.value]),
  enabled: computed(() => before.value !== null),
  retry: false,
  gcTime: 0,
  queryFn: ({ signal }) => api.page(props.owner, props.exerciseId, props.task.id, before.value, signal),
});
const page = computed(() => before.value === null ? head.data.value : older.data.value);
const detailKey = (id: string) => [...root.value, 'detail', id];
const detail = useQuery({
  queryKey: computed(() => detailKey(selected.value)),
  enabled: computed(() => !!selected.value),
  retry: false,
  gcTime: 0,
  queryFn: async ({ signal }) => {
    const { owner, exerciseId, task } = props;
    const id = selected.value;
    const key = detailKey(id);
    return api.latest(client.getQueryData<api.CommandProposal>(key), await api.detail(owner, exerciseId, task.id, id, signal));
  },
  refetchInterval: query => query.state.data?.state === 'RESOLVED' ? false : 1000,
});
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
const output = useQuery({
  queryKey: executionKey,
  enabled: computed(() => !!current.value?.commandTaskId),
  retry: false,
  gcTime: 0,
  queryFn: async ({ signal }) => {
    const source = current.value!;
    const owner = props.owner;
    const key = executionKey.value;
    const value = await execution.detail(owner, source.exerciseId, source.workspaceInstanceId, source.commandTaskId!, signal);
    if (value.command !== source.command || value.directory !== source.directory || value.revision !== source.baseRevision) throw new ApiError(502, 'INVALID_RESPONSE');
    return execution.latest(client.getQueryData<execution.CommandTask>(key), value);
  },
  refetchInterval: query => (query.state.error instanceof ApiError && query.state.error.status === 410)
    || (query.state.data?.quiescent && query.state.data.synchronization !== 'PENDING'
      && query.state.data.cleanup === 'SUCCEEDED' && query.state.data.copyCleanup === 'SUCCEEDED') ? false : 1000,
});
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
  const { owner, exerciseId, task } = props;
  const id = current.value.id;
  const input: api.Decision = { decision: kind, reason: kind === 'REJECT' ? rejection.value : null };
  void decision.submit((key, signal) => api.decide(owner, exerciseId, task.id, id, input, key, props.credential, signal));
}
const visible = computed(() => options.value.length > 0 || head.isError.value || older.isError.value
  || detail.isError.value || !!decision.error.value || decision.uncertain.value);
</script>

<template>
  <section
    v-if="visible"
    class="copilot-commands"
    aria-label="Copilot Bash 命令"
    :data-pending="current?.state === 'PENDING' && current.mode === 'ASK' ? 'true' : undefined"
  >
    <div class="cmd-head">
      <Terminal
        :size="13"
        aria-hidden="true"
      />
      <h3>Copilot 命令</h3>
    </div>

    <p
      v-if="head.isError.value"
      role="alert"
      class="cmd-err"
    >
      {{ errorMessage(head.error.value) }}
      <Button
        variant="ghost"
        size="small"
        @click="head.refetch()"
      >
        重试
      </Button>
    </p>

    <select
      v-if="options.length > 1 || before || page?.nextBefore"
      v-model="selected"
      aria-label="选择 Copilot Bash 命令"
      class="cmd-select font-mono-num"
    >
      <option
        v-for="item in options"
        :key="item.id"
        :value="item.id"
      >
        #{{ item.sequence }} · {{ item.state }} · {{ item.command.slice(0, 80) }}
      </option>
    </select>

    <div
      v-if="page?.nextBefore || before"
      class="actions"
    >
      <Button
        v-if="page?.nextBefore"
        variant="ghost"
        size="small"
        :disabled="older.isFetching.value"
        @click="before = page!.nextBefore"
      >
        更早命令
      </Button>
      <Button
        v-if="before"
        variant="ghost"
        size="small"
        @click="before = null"
      >
        最近命令
      </Button>
    </div>

    <p
      v-if="selected && detail.isPending.value"
      role="status"
    >
      正在核对命令状态…
    </p>
    <p
      v-if="detail.isError.value"
      role="alert"
      class="cmd-err"
    >
      {{ errorMessage(detail.error.value) }}
      <Button
        variant="ghost"
        size="small"
        @click="detail.refetch()"
      >
        重试
      </Button>
    </p>

    <template v-if="current">
      <pre class="cmd-line">/project/{{ current.directory }} $ {{ current.command }}</pre>
      <p
        v-if="current.state === 'PENDING'"
        role="status"
      >
        {{ current.mode === 'AUTO' ? '按 AUTO 模式核对准入中' : '等待批准命令' }} · 基线 r{{ current.baseRevision }}
      </p>
      <template v-if="current.state === 'PENDING' && current.mode === 'ASK'">
        <textarea
          v-model="rejection"
          aria-label="命令拒绝理由"
          placeholder="拒绝理由（可选）…"
          :disabled="!canDecide"
          maxlength="2048"
          rows="2"
        />
        <div class="actions">
          <Button
            variant="primary"
            size="small"
            :disabled="!canDecide"
            @click="decide('APPROVE')"
          >
            批准此命令
          </Button>
          <Button
            variant="danger"
            size="small"
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
        等待命令与文件同步收敛…
      </p>
      <p
        v-if="current.result"
        role="status"
      >
        {{ current.result.code }}<span v-if="current.result.reason"> · {{ current.result.reason }}</span>
      </p>
      <p
        v-if="output.isError.value"
        role="alert"
        class="cmd-err"
      >
        {{ workspaceMessage(output.error.value) }}
        <Button
          variant="ghost"
          size="small"
          @click="output.refetch()"
        >
          重试
        </Button>
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
      class="cmd-err"
    >
      {{ decision.error.value }}
    </p>
    <Button
      v-if="decision.uncertain.value"
      size="small"
      :disabled="decision.pending.value"
      @click="decision.retry()"
    >
      核对原命令决定
    </Button>
  </section>
</template>

<style scoped>
.copilot-commands {
  display: grid;
  gap: 8px;
  min-width: 0;
  border: 1px solid var(--oc-border-strong);
  border-radius: 8px;
  background: var(--oc-surface-2);
  padding: 10px 12px;
}

.copilot-commands[data-pending="true"] {
  border-color: var(--oc-warning-border);
  border-left: 3px solid var(--oc-warning);
}

.cmd-head {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--oc-fg);
}

h3 {
  margin: 0;
  font-size: 12px;
  font-weight: 600;
}

p {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-fg-muted);
  line-height: 1.45;
}

.cmd-err {
  color: var(--oc-danger);
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.cmd-line {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin: 0;
  padding: 7px 9px;
  border-radius: 6px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  font-size: 11.5px;
  color: var(--oc-fg);
}

textarea, .cmd-select {
  width: 100%;
  padding: 5px 8px;
  font-size: 12px;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
