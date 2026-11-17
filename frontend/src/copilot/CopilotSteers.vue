<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import * as api from './api';
import { commandPolicy, errorMessage } from './errors';

const props = defineProps<{ owner: string; exerciseId: string; taskId: string; state: api.Task['state']; credential: string; disabled: boolean }>();
const emit = defineEmits<{ unauthorized: [] }>();
const client = useQueryClient();
const before = ref('0'); const text = ref(''); const feedback = ref('');
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.taskId}/${props.credential}`);
const baseKey = computed(() => ['copilot-steers', props.owner, props.exerciseId, props.taskId]);
const accepting = computed(() => ['QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'WAITING_WRITE', 'WAITING_TEST', 'WAITING_COMMAND', 'WAITING_CHILD'].includes(props.state));
const history = useQuery({ queryKey: computed(() => [...baseKey.value, before.value, props.state]),
  queryFn: ({ signal }) => api.steers(props.owner, props.exerciseId, props.taskId, before.value, signal), retry: false, gcTime: 0,
  refetchInterval: computed(() => accepting.value || props.state === 'STOPPING' || props.state === 'PAUSED' ? 2000 : false) });
const command = useCommand<api.Steer>(() => scope.value, (receipt) => {
  text.value = ''; before.value = '0';
  feedback.value = receipt.state === 'NOT_APPLIED' ? '原指令未生效，不会另起任务。' : '服务端已接收；是否已纳入模型请求，以记录状态为准。';
  void client.invalidateQueries({ queryKey: baseKey.value });
  void client.invalidateQueries({ queryKey: ['copilot', props.owner, props.exerciseId, 'task'] });
}, commandPolicy, () => emit('unauthorized'));
watch(scope, () => { text.value = ''; before.value = '0'; feedback.value = ''; }, { flush: 'sync' });
watch(history.error, (failure) => { if (commandPolicy.identityFailure(failure)) emit('unauthorized'); });
const canSend = computed(() => accepting.value && !props.disabled && !!props.credential && !!text.value.trim()
  && !command.pending.value && !command.uncertain.value);
const labels: Record<api.Steer['state'], string> = { RECEIVED: '已接收，等待安全点与下一次请求', APPLIED: '已生效：已纳入模型请求', NOT_APPLIED: '未生效' };
async function send() {
  if (!canSend.value) return;
  if (new globalThis.TextEncoder().encode(text.value).length > 4096) { feedback.value = '方向调整最多 4 KiB，请缩短后发送。'; return; }
  const { owner, exerciseId, taskId, credential } = props; const content = text.value;
  feedback.value = '';
  await command.submit((key, signal) => api.sendSteer(owner, exerciseId, taskId, credential, content, key, signal));
}
</script>

<template>
  <section
    aria-label="Copilot 方向调整"
    class="steers"
  >
    <div class="steers-head">
      <h3>调整当前任务方向 · steer</h3>
      <Button
        variant="ghost"
        size="small"
        :disabled="history.isFetching.value"
        @click="history.refetch()"
      >
        重新读取方向记录
      </Button>
    </div>
    <p
      v-if="!accepting"
      role="status"
    >
      当前任务已结束、暂停或正在停止，不能接收新方向调整；不会自动创建新任务。
    </p>
    <label
      :for="`steer-${taskId}`"
      class="sr-only"
    >给当前任务的方向调整</label>
    <textarea
      :id="`steer-${taskId}`"
      v-model="text"
      rows="2"
      placeholder="输入方向调整（仅调整当前任务，不回滚代码）…"
      :disabled="!accepting || disabled || !credential || command.pending.value || command.uncertain.value"
    />
    <p
      v-if="feedback"
      role="status"
      class="steer-status"
    >
      {{ feedback }}
    </p>
    <p
      v-if="command.error.value"
      role="alert"
      class="steer-alert"
    >
      {{ command.error.value }}
    </p>
    <p
      v-if="command.uncertain.value"
      role="status"
    >
      方向调整的受理结果未知，只能核对原键与原内容；读取任务状态不等于确认原请求。
    </p>
    <div class="actions">
      <Button
        v-if="command.uncertain.value"
        size="small"
        :disabled="command.pending.value"
        @click="command.retry"
      >
        核对原方向调整
      </Button>
      <Button
        v-else
        variant="secondary"
        size="small"
        :disabled="!canSend"
        :loading="command.pending.value"
        @click="send"
      >
        发送方向调整
      </Button>
    </div>
    <details
      class="steers-drawer"
      :open="!!history.data.value?.items.length"
    >
      <summary>方向调整记录（{{ history.data.value?.items.length ?? 0 }}）</summary>
      <p
        v-if="history.isPending.value"
        role="status"
      >
        正在读取方向调整记录…
      </p>
      <p
        v-else-if="history.isError.value"
        role="alert"
        class="steer-alert"
      >
        {{ errorMessage(history.error.value) }}
      </p>
      <template v-else-if="history.data.value">
        <p v-if="!history.data.value.items.length">
          当前页没有方向调整记录。
        </p>
        <ol v-else>
          <li
            v-for="item in history.data.value.items"
            :key="item.id"
          >
            <strong>#{{ item.sequence }} · {{ labels[item.state] }}</strong>
            <p>{{ item.text }}</p>
            <p v-if="item.reason">
              {{ errorMessage(new ApiError(409, item.reason)) }}
            </p>
            <time :datetime="item.createdAt">{{ new Date(item.createdAt).toLocaleString() }}</time>
          </li>
        </ol>
        <div class="actions">
          <Button
            v-if="history.data.value.nextCursor"
            size="small"
            @click="before = history.data.value.nextCursor"
          >
            更早的方向调整
          </Button>
          <Button
            v-if="before !== '0'"
            size="small"
            @click="before = '0'"
          >
            最近方向调整
          </Button>
        </div>
      </template>
    </details>
  </section>
</template>
<style scoped>
.steers {
  padding: 10px 12px;
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-control);
  background: var(--oc-surface-2);
  display: grid;
  gap: 8px;
}

.steers-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

h3 {
  margin: 0;
  font-size: 12.5px;
  font-weight: 600;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
}

textarea {
  width: 100%;
  padding: 6px 10px;
  font-size: 12.5px;
  resize: vertical;
}

p, li {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.5;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.steer-status {
  color: var(--oc-accent);
}

.steer-alert {
  color: var(--oc-danger);
}

.steers-drawer {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.steers-drawer summary {
  cursor: pointer;
}

.steers-drawer ol {
  margin: 6px 0;
  padding-left: 18px;
  display: grid;
  gap: 6px;
}

.actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
</style>
