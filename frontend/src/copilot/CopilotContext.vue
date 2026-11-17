<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import * as api from './api';
import { commandPolicy, errorMessage } from './errors';
import { formatShortTimestamp } from './feeConsent';

const props = defineProps<{ owner: string; exerciseId: string; conversationId?: string; credential: string; disabled: boolean }>();
const emit = defineEmits<{ unauthorized: []; busy: [value: boolean] }>();
const client = useQueryClient();
const fee = ref(false);
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.conversationId ?? props.exerciseId}`);
const key = computed(() => ['copilot-context', props.owner, props.exerciseId, props.conversationId ?? props.exerciseId]);
const history = useQuery({
  queryKey: key,
  queryFn: ({ signal }) => api.context(props.owner, props.exerciseId, signal, props.conversationId),
  retry: false,
  gcTime: 0,
  refetchInterval: 2000,
});
function refresh() {
  void client.invalidateQueries({ queryKey: key.value });
  void client.invalidateQueries({ queryKey: ['copilot', props.owner, props.exerciseId, 'task'] });
}
function received(receipt: api.Compaction) {
  client.setQueryData<api.Context>(key.value, (previous) => previous ? {
    ...previous,
    requests: [receipt, ...previous.requests.filter((item) => item.id !== receipt.id)]
      .sort((left, right) => BigInt(left.sequence) > BigInt(right.sequence) ? -1 : 1).slice(0, 20),
  } : previous);
  refresh();
}
const start = useCommand<api.Compaction>(() => scope.value, (receipt) => { fee.value = false; received(receipt); }, commandPolicy, () => emit('unauthorized'));
const stop = useCommand<api.Compaction>(() => scope.value, received, commandPolicy, () => emit('unauthorized'));
const active = computed(() => history.data.value?.requests.find((item) => item.state === 'WAITING' || item.state === 'RUNNING'));
const busy = computed(() => !history.data.value || history.isError.value || !!active.value || start.pending.value || start.uncertain.value);
watch(busy, (value) => emit('busy', value), { immediate: true, flush: 'sync' });
watch(scope, () => { fee.value = false; }, { flush: 'sync' });
watch(history.error, (failure) => { if (commandPolicy.identityFailure(failure)) emit('unauthorized'); });
const canStart = computed(() => !busy.value && !props.disabled && !!props.credential && fee.value && !stop.pending.value && !stop.uncertain.value);
const labels: Record<api.Compaction['state'], string> = {
  WAITING: '等待执行名额',
  RUNNING: '正在生成摘要',
  APPLIED: '已更新后续输入',
  FAILED: '压缩失败，原上下文保持',
  INTERRUPTED: '已中断，不自动重发',
  CANCELLED: '已取消',
  NOT_NEEDED: '当前无需压缩',
};
function compact() {
  if (!canStart.value) return;
  const { owner, exerciseId, credential, conversationId } = props;
  void start.submit((id, signal) => api.compact(owner, exerciseId, credential, id, signal, conversationId));
}
function cancel() {
  if (!active.value || stop.pending.value || stop.uncertain.value) return;
  const { owner, exerciseId } = props;
  const id = active.value.id;
  void stop.submit((requestKey, signal) => api.cancelCompaction(owner, exerciseId, id, requestKey, signal));
}
</script>

<template>
  <section
    class="context"
    aria-label="Copilot 上下文"
  >
    <div class="context-head">
      <h3>上下文摘要与压缩</h3>
      <Button
        v-if="history.isError.value"
        variant="ghost"
        size="small"
        :disabled="history.isFetching.value"
        @click="history.refetch()"
      >
        重试
      </Button>
    </div>
    <p class="context-note">
      生成摘要仅精简后续模型输入，不删除原始消息或工具证据。
    </p>
    <label
      :for="`context-fee-${conversationId ?? exerciseId}`"
      class="fee-label"
    >
      <input
        :id="`context-fee-${conversationId ?? exerciseId}`"
        v-model="fee"
        type="checkbox"
        :disabled="busy || disabled || !credential"
      >
      <span>我理解手动压缩可能产生模型费用</span>
    </label>
    <div class="actions">
      <Button
        v-if="start.uncertain.value"
        size="small"
        :disabled="start.pending.value"
        @click="start.retry"
      >
        核对原压缩请求
      </Button>
      <Button
        v-else
        variant="secondary"
        size="small"
        :disabled="!canStart"
        :loading="start.pending.value"
        @click="compact"
      >
        手动压缩上下文
      </Button>
      <Button
        v-if="stop.uncertain.value"
        size="small"
        :disabled="stop.pending.value"
        @click="stop.retry"
      >
        核对原取消请求
      </Button>
      <Button
        v-else-if="active"
        variant="danger"
        size="small"
        :disabled="active.stopRequested || stop.pending.value"
        @click="cancel"
      >
        取消压缩
      </Button>
    </div>
    <p
      v-if="start.error.value || stop.error.value"
      role="alert"
      class="context-error"
    >
      {{ start.error.value || stop.error.value }}
    </p>
    <p
      v-if="start.uncertain.value"
      role="status"
      class="context-warn"
    >
      压缩受理结果未知，请使用原请求核对。
    </p>
    <p
      v-if="active?.stopRequested"
      role="status"
    >
      正在取消，等待当前调用收敛。
    </p>
    <p
      v-if="history.isPending.value"
      role="status"
    >
      正在读取上下文状态…
    </p>
    <p
      v-else-if="history.isError.value"
      role="alert"
      class="context-error"
    >
      {{ errorMessage(history.error.value) }}
    </p>
    <template v-else-if="history.data.value">
      <p class="font-mono-num">
        输入视图 v{{ history.data.value.version }} · 覆盖至序号 {{ history.data.value.throughSequence }}
      </p>
      <details v-if="history.data.value.summary">
        <summary>查看当前非权威摘要</summary>
        <pre>{{ history.data.value.summary }}</pre>
      </details>
      <ol
        v-if="history.data.value.requests.length"
        class="records"
      >
        <li
          v-for="item in history.data.value.requests"
          :key="item.id"
        >
          <strong>{{ item.trigger === 'AUTO' ? '自动' : '手动' }} · {{ labels[item.state] }}</strong>
          <span class="font-mono-num"> · 派发 {{ item.attempts }} 次</span>
          <p v-if="item.reason">
            {{ errorMessage(new ApiError(409, item.reason)) }}
          </p>
          <time
            class="font-mono-num"
            :datetime="item.createdAt"
          >{{ formatShortTimestamp(item.createdAt) }}</time>
        </li>
      </ol>
    </template>
  </section>
</template>

<style scoped>
.context {
  padding-top: 14px;
  border-top: 1px solid var(--oc-border);
  display: grid;
  gap: 8px;
}

.context-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--oc-fg);
}

p, li {
  margin: 0;
  line-height: 1.5;
  overflow-wrap: anywhere;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.fee-label {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--oc-fg);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.context-error {
  color: var(--oc-danger);
}

.context-warn {
  color: var(--oc-warning);
}

.records {
  max-height: 16rem;
  overflow: auto;
  padding-left: 18px;
  margin: 0;
  display: grid;
  gap: 6px;
}

summary {
  cursor: pointer;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 20rem;
  overflow: auto;
  padding: 10px;
  border-radius: 8px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  font-size: 12px;
}
</style>
