<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { CornerDownRight } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import * as api from './api';
import { commandPolicy, errorMessage } from './errors';
import { formatShortTimestamp } from './feeConsent';

const props = withDefaults(defineProps<{
  owner: string;
  exerciseId: string;
  taskId: string;
  state: api.Task['state'];
  credential: string;
  disabled: boolean;
  slim?: boolean;
}>(), {
  slim: false,
});
const emit = defineEmits<{ unauthorized: [] }>();
const client = useQueryClient();
const before = ref('0');
const text = ref('');
const feedback = ref('');
const expanded = ref(false);
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.taskId}/${props.credential}`);
const baseKey = computed(() => ['copilot-steers', props.owner, props.exerciseId, props.taskId]);
const accepting = computed(() => ['QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'WAITING_WRITE', 'WAITING_TEST', 'WAITING_COMMAND', 'WAITING_CHILD'].includes(props.state));
const history = useQuery({
  queryKey: computed(() => [...baseKey.value, before.value, props.state]),
  queryFn: ({ signal }) => api.steers(props.owner, props.exerciseId, props.taskId, before.value, signal),
  retry: false,
  gcTime: 0,
  refetchInterval: computed(() => accepting.value || props.state === 'STOPPING' || props.state === 'PAUSED' ? 2000 : false),
});
const command = useCommand<api.Steer>(() => scope.value, (receipt) => {
  text.value = '';
  before.value = '0';
  feedback.value = receipt.state === 'NOT_APPLIED' ? '原指令未生效，不会另起任务。' : '方向调整已接收。';
  void client.invalidateQueries({ queryKey: baseKey.value });
  void client.invalidateQueries({ queryKey: ['copilot', props.owner, props.exerciseId, 'task'] });
}, commandPolicy, () => emit('unauthorized'));

watch(scope, () => { text.value = ''; before.value = '0'; feedback.value = ''; expanded.value = false; }, { flush: 'sync' });
watch(history.error, (failure) => { if (commandPolicy.identityFailure(failure)) emit('unauthorized'); });

const canSend = computed(() => accepting.value && !props.disabled && !!props.credential && !!text.value.trim()
  && !command.pending.value && !command.uncertain.value);
const labels: Record<api.Steer['state'], string> = {
  RECEIVED: '已接收，等待安全点',
  APPLIED: '已生效',
  NOT_APPLIED: '未生效',
};

async function send() {
  if (!canSend.value) return;
  if (new globalThis.TextEncoder().encode(text.value).length > 4096) { feedback.value = '方向调整最多 4 KiB，请缩短后发送。'; return; }
  const { owner, exerciseId, taskId, credential } = props;
  const content = text.value;
  feedback.value = '';
  await command.submit((key, signal) => api.sendSteer(owner, exerciseId, taskId, credential, content, key, signal));
}

async function sendDirect(content: string): Promise<boolean> {
  if (!accepting.value || props.disabled || !props.credential || !content.trim() || command.pending.value || command.uncertain.value) return false;
  if (new globalThis.TextEncoder().encode(content).length > 4096) { feedback.value = '方向调整最多 4 KiB，请缩短后发送。'; return false; }
  const { owner, exerciseId, taskId, credential } = props;
  feedback.value = '';
  await command.submit((key, signal) => api.sendSteer(owner, exerciseId, taskId, credential, content, key, signal));
  return !command.error.value && !command.uncertain.value;
}

const slimVisible = computed(() => (history.data.value?.items.length ?? 0) > 0 || !!feedback.value || !!command.error.value || command.uncertain.value);

defineExpose({ sendDirect, accepting, pending: command.pending, uncertain: command.uncertain });
</script>

<template>
  <section
    v-if="slim ? slimVisible : true"
    aria-label="Copilot 方向调整"
    class="steers"
    :class="{ 'is-slim': slim }"
  >
    <template v-if="slim">
      <div class="slim-steer-bar">
        <button
          v-if="history.data.value?.items.length"
          type="button"
          class="slim-steer-toggle"
          @click="expanded = !expanded"
        >
          <CornerDownRight
            :size="12"
            aria-hidden="true"
          />
          <span>方向调整 ({{ history.data.value.items.length }})</span>
        </button>
        <span
          v-if="feedback"
          role="status"
          class="steer-status"
        >{{ feedback }}</span>
      </div>
      <ol
        v-if="expanded && history.data.value?.items.length"
        class="slim-steer-list"
      >
        <li
          v-for="item in history.data.value.items"
          :key="item.id"
        >
          <span class="steer-row-head font-mono-num">
            #{{ item.sequence }} · {{ labels[item.state] }} · {{ formatShortTimestamp(item.createdAt) }}
          </span>
          <p>{{ item.text }}</p>
        </li>
      </ol>
      <p
        v-if="command.error.value"
        role="alert"
        class="steer-alert"
      >
        {{ command.error.value }}
      </p>
      <div
        v-if="command.uncertain.value"
        class="actions"
      >
        <Button
          size="small"
          :disabled="command.pending.value"
          @click="command.retry"
        >
          核对原方向调整
        </Button>
      </div>
    </template>

    <template v-else>
      <div class="steers-head">
        <h3>方向调整</h3>
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
      <p
        v-if="!accepting"
        role="status"
      >
        当前任务不能接收新方向调整。
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
        方向调整的受理结果未知，请核对原请求。
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
          正在读取记录…
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
            暂无记录。
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
              <time :datetime="item.createdAt">{{ formatShortTimestamp(item.createdAt) }}</time>
            </li>
          </ol>
        </template>
      </details>
    </template>
  </section>
</template>

<style scoped>
.steers.is-slim {
  margin: 0 10px 6px;
  padding: 5px 10px;
  border-radius: 8px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 5px;
}

.slim-steer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.slim-steer-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--oc-fg-muted);
  font-size: 11.5px;
  cursor: pointer;
}

.slim-steer-toggle:hover {
  color: var(--oc-fg);
}

.slim-steer-list {
  margin: 0;
  padding: 6px 0 0 16px;
  border-top: 1px solid var(--oc-border);
  display: grid;
  gap: 6px;
}

.steer-row-head {
  font-size: 11px;
  color: var(--oc-fg-dim);
}

.steers:not(.is-slim) {
  padding: 10px 12px;
  border: 1px solid var(--oc-border);
  border-radius: 8px;
  background: var(--oc-surface-2);
  display: grid;
  gap: 8px;
}

.steers-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
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
  font-size: 11.5px;
  color: var(--oc-accent);
}

.steer-alert {
  font-size: 11.5px;
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
