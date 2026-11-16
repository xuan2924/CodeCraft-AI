<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import * as api from './api';
import { commandPolicy, errorMessage } from './errors';

const props = defineProps<{ owner: string; exerciseId: string; conversationId?: string; credential: string; disabled: boolean }>();
const emit = defineEmits<{ unauthorized: []; busy: [value: boolean] }>();
const client = useQueryClient();
const fee = ref(false);
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.conversationId ?? props.exerciseId}`);
const key = computed(() => ['copilot-context', props.owner, props.exerciseId, props.conversationId ?? props.exerciseId]);
const history = useQuery({ queryKey: key, queryFn: ({ signal }) => api.context(props.owner, props.exerciseId, signal, props.conversationId), retry: false, gcTime: 0, refetchInterval: 2000 });
function refresh() {
  void client.invalidateQueries({ queryKey: key.value });
  void client.invalidateQueries({ queryKey: ['copilot', props.owner, props.exerciseId, 'task'] });
}
function received(receipt: api.Compaction) {
  client.setQueryData<api.Context>(key.value, (previous) => previous ? { ...previous,
    requests: [receipt, ...previous.requests.filter((item) => item.id !== receipt.id)]
      .sort((left, right) => BigInt(left.sequence) > BigInt(right.sequence) ? -1 : 1).slice(0, 20) } : previous);
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
const labels: Record<api.Compaction['state'], string> = { WAITING: '等待安全点或执行名额', RUNNING: '正在生成摘要', APPLIED: '已更新后续输入', FAILED: '压缩失败，原上下文保持', INTERRUPTED: '已中断，不自动重发', CANCELLED: '已取消', NOT_NEEDED: '当前无需压缩，输入视图未改动' };
function compact() {
  if (!canStart.value) return;
  const { owner, exerciseId, credential, conversationId } = props;
  void start.submit((id, signal) => api.compact(owner, exerciseId, credential, id, signal, conversationId));
}
function cancel() {
  if (!active.value || stop.pending.value || stop.uncertain.value) return;
  const { owner, exerciseId } = props; const id = active.value.id;
  void stop.submit((requestKey, signal) => api.cancelCompaction(owner, exerciseId, id, requestKey, signal));
}
</script>

<template>
  <section
    class="context"
    aria-label="Copilot 上下文"
  >
    <h3>上下文与摘要</h3>
    <p>接近模型窗口时先缩短可重读结果，再尝试摘要。原始消息、工具回执与代码不会删除；摘要只用于后续输入，不是审批或测试结论。</p>
    <p>手动请求在完整批次后执行；审批、写入或测试先收敛。摘要可能收费，格式不合格最多修复一次。失败保留原上下文；无法安全继续时暂停任务。</p>
    <label :for="`context-fee-${conversationId ?? exerciseId}`"><input
      :id="`context-fee-${conversationId ?? exerciseId}`"
      v-model="fee"
      type="checkbox"
      :disabled="busy || disabled || !credential"
    >我理解手动压缩可能产生模型费用</label>
    <div class="actions">
      <Button
        v-if="start.uncertain.value"
        :disabled="start.pending.value"
        @click="start.retry"
      >
        核对原压缩请求
      </Button>
      <Button
        v-else
        :disabled="!canStart"
        :loading="start.pending.value"
        @click="compact"
      >
        手动压缩上下文
      </Button>
      <Button
        v-if="stop.uncertain.value"
        :disabled="stop.pending.value"
        @click="stop.retry"
      >
        核对原取消请求
      </Button>
      <Button
        v-else-if="active"
        :disabled="active.stopRequested || stop.pending.value"
        @click="cancel"
      >
        取消压缩
      </Button>
      <Button
        :disabled="history.isFetching.value"
        @click="history.refetch()"
      >
        重新读取上下文状态
      </Button>
    </div>
    <p
      v-if="start.error.value || stop.error.value"
      role="alert"
    >
      {{ start.error.value || stop.error.value }}
    </p>
    <p
      v-if="start.uncertain.value"
      role="status"
    >
      压缩受理结果未知，只能使用原键与原内容核对；刷新状态不会重新发起压缩。
    </p>
    <p
      v-if="active?.stopRequested"
      role="status"
    >
      正在取消。已派发的请求可能计费，等待其收敛；需要停止整个任务时，请使用“停止 Copilot 任务”。
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
    >
      {{ errorMessage(history.error.value) }}
    </p>
    <template v-else-if="history.data.value">
      <p>输入视图版本 {{ history.data.value.version }}；摘要覆盖到原消息序号 {{ history.data.value.throughSequence }}。</p>
      <details v-if="history.data.value.summary">
        <summary>查看非权威摘要（原始记录仍保留）</summary>
        <pre>{{ history.data.value.summary }}</pre>
      </details>
      <p v-if="!history.data.value.requests.length">
        暂无压缩记录。
      </p>
      <ol
        v-else
        class="records"
      >
        <li
          v-for="item in history.data.value.requests"
          :key="item.id"
        >
          <strong>{{ item.trigger === 'AUTO' ? '自动' : '手动' }}：{{ labels[item.state] }}</strong>
          <span> · 已登记 {{ item.attempts }} 次模型派发（不代表供应商确认收到）</span>
          <p v-if="item.reason">
            {{ errorMessage(new ApiError(409, item.reason)) }}
          </p>
          <time :datetime="item.createdAt">{{ new Date(item.createdAt).toLocaleString() }}</time>
        </li>
      </ol>
      <p v-if="history.data.value.requests.length">
        显示最近 20 条压缩记录；重新读取与重连不会重发收费请求。
      </p>
    </template>
  </section>
</template>

<style scoped>
.context {
  padding-top: 12px;
  border-top: 1px solid var(--oc-border);
  display: grid;
  gap: 8px;
}
h3 { margin: 0; font-size: 13px; font-weight: 600; }
p, li { margin: 0; line-height: 1.55; overflow-wrap: anywhere; font-size: 12px; color: var(--oc-fg-muted); }
.actions { display: flex; flex-wrap: wrap; gap: 8px; }
.records { max-height: 20rem; overflow: auto; padding-left: 20px; margin: 0; display: grid; gap: 6px; }
summary { cursor: pointer; font-size: 12.5px; }
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 24rem;
  overflow: auto;
  padding: 10px;
  border-radius: 8px;
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
  font-size: 12px;
}
</style>
