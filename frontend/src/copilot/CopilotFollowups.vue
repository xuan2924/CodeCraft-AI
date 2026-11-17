<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { ChevronDown, ChevronUp, ListOrdered, X } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import { useCommand } from '../api/useCommand';
import { commandPolicy, errorMessage } from './errors';
import * as api from './followupApi';

const props = withDefaults(defineProps<{
  owner: string;
  exerciseId: string;
  conversationId: string;
  credential: string;
  disabled: boolean;
  running: boolean;
  slim?: boolean;
}>(), {
  slim: false,
});
const emit = defineEmits<{ unauthorized: []; changed: [] }>();
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.conversationId}/${props.credential}`);
const text = ref('');
const fee = ref(false);
const validation = ref('');
const expanded = ref(false);
const query = useQuery({
  queryKey: computed(() => ['copilot', props.owner, props.exerciseId, 'followups', props.conversationId]),
  queryFn: ({ signal }) => api.recent(props.owner, props.exerciseId, props.conversationId, signal),
  retry: false,
  gcTime: 0,
  refetchInterval: 2000,
});
watch(query.error, error => { if (commandPolicy.identityFailure(error)) emit('unauthorized'); });
watch(scope, () => { text.value = ''; fee.value = false; validation.value = ''; expanded.value = false; }, { flush: 'sync' });
function changed() { void query.refetch(); emit('changed'); }
const enqueue = useCommand<api.Followup>(() => scope.value, () => { text.value = ''; fee.value = false; changed(); }, commandPolicy, () => emit('unauthorized'));
const cancel = useCommand<api.Followup>(() => scope.value, changed, commandPolicy, () => emit('unauthorized'));
const resume = useCommand<{ conversationId: string; requestKey: string }>(() => scope.value, () => { fee.value = false; changed(); }, commandPolicy, () => emit('unauthorized'));
const busy = computed(() => [enqueue, cancel, resume].some(command => command.pending.value || command.uncertain.value));
const canWrite = computed(() => !props.disabled && !!props.credential && !query.isPending.value && !query.isError.value && !busy.value);
const paused = computed(() => query.data.value?.some(item => item.state === 'PAUSED') ?? false);
const activeItems = computed(() => query.data.value?.filter(item => item.state === 'WAITING' || item.state === 'PAUSED') ?? []);
const pending = computed(() => activeItems.value.length);

function append() {
  if (!canWrite.value || !props.running || !fee.value || !text.value.trim() || pending.value >= 32) return;
  if (new globalThis.TextEncoder().encode(text.value).length > 4096) { validation.value = '后续请求最多 4 KiB。'; return; }
  validation.value = '';
  const { owner, exerciseId, conversationId, credential } = props;
  const prompt = text.value;
  void enqueue.submit((key, signal) => api.enqueue(owner, exerciseId, conversationId, credential, prompt, key, signal));
}

function enqueueDirect(prompt: string): boolean {
  if (!canWrite.value || !props.running || !prompt.trim() || pending.value >= 32) return false;
  if (new globalThis.TextEncoder().encode(prompt).length > 4096) { validation.value = '后续请求最多 4 KiB。'; return false; }
  validation.value = '';
  const { owner, exerciseId, conversationId, credential } = props;
  void enqueue.submit((key, signal) => api.enqueue(owner, exerciseId, conversationId, credential, prompt, key, signal));
  return true;
}

function remove(id: string) {
  if (busy.value) return;
  const { owner, exerciseId, conversationId } = props;
  void cancel.submit((key, signal) => api.cancel(owner, exerciseId, conversationId, id, key, signal));
}
function continueQueue() {
  if (!canWrite.value || (!props.slim && !fee.value) || !paused.value) return;
  const { owner, exerciseId, conversationId, credential } = props;
  void resume.submit((key, signal) => api.resume(owner, exerciseId, conversationId, credential, key, signal));
}
const labels = { WAITING: '等待', PAUSED: '暂停', DISPATCHED: '已派发', CANCELLED: '已取消' };
const slimVisible = computed(() => pending.value > 0 || paused.value || !!validation.value
  || [enqueue, cancel, resume].some(cmd => cmd.pending.value || cmd.uncertain.value || !!cmd.error.value));

defineExpose({ enqueueDirect, pending, paused, busy });
</script>

<template>
  <div
    v-if="slim ? slimVisible : true"
    class="followups"
    :class="{ 'is-slim': slim }"
    aria-label="后续任务队列"
  >
    <template v-if="slim">
      <div class="slim-bar">
        <button
          type="button"
          class="slim-toggle"
          :aria-expanded="expanded"
          @click="expanded = !expanded"
        >
          <ListOrdered
            :size="12"
            aria-hidden="true"
          />
          <span>{{ paused ? `后续队列已暂停 (${pending})` : `后续队列 · ${pending} 条待执行` }}</span>
          <component
            :is="expanded ? ChevronDown : ChevronUp"
            :size="12"
            aria-hidden="true"
          />
        </button>
        <div class="slim-actions">
          <Button
            v-if="paused"
            variant="secondary"
            size="small"
            :disabled="!canWrite"
            @click="continueQueue"
          >
            重新授权并继续队列
          </Button>
        </div>
      </div>

      <div
        v-if="expanded"
        class="slim-list"
      >
        <div
          v-for="item in activeItems"
          :key="item.id"
          class="slim-row"
        >
          <span class="slim-text">{{ item.text }}</span>
          <button
            type="button"
            class="slim-cancel"
            :disabled="busy"
            aria-label="取消排队"
            @click="remove(item.id)"
          >
            <X
              :size="12"
              aria-hidden="true"
            />
          </button>
        </div>
      </div>
    </template>

    <template v-else>
      <h3>后续独立任务 · {{ pending }}/32</h3>
      <p
        v-if="query.isPending.value"
        role="status"
      >
        正在读取队列…
      </p>
      <p
        v-if="query.error.value"
        role="alert"
      >
        {{ errorMessage(query.error.value) }}
        <Button
          variant="ghost"
          size="small"
          @click="query.refetch()"
        >
          重试
        </Button>
      </p>
      <ol>
        <li
          v-for="item in query.data.value"
          :key="item.id"
        >
          <p>{{ labels[item.state] }}<span v-if="item.reason"> · {{ item.reason }}</span></p>
          <pre>{{ item.text }}</pre>
          <Button
            v-if="item.state === 'WAITING' || item.state === 'PAUSED'"
            variant="ghost"
            size="small"
            :disabled="busy"
            @click="remove(item.id)"
          >
            取消此排队项
          </Button>
        </li>
      </ol>
      <label v-if="running">后续请求<textarea
        v-model="text"
        :disabled="busy || disabled"
      /></label>
      <label v-if="running || paused"><input
        v-model="fee"
        type="checkbox"
        :disabled="busy || disabled"
      >确认后续独立任务可能继续收费</label>
      <button
        v-if="running"
        type="button"
        :disabled="!canWrite || !fee || !text.trim() || pending >= 32"
        @click="append"
      >
        加入后续队列（不 steer）
      </button>
      <button
        v-if="paused"
        type="button"
        :disabled="!canWrite || !fee"
        @click="continueQueue"
      >
        重新授权并继续队列
      </button>
    </template>

    <p
      v-if="validation"
      role="alert"
      class="fq-err"
    >
      {{ validation }}
    </p>
    <template
      v-for="(command, index) in [enqueue, cancel, resume]"
      :key="index"
    >
      <p
        v-if="command.error.value"
        role="alert"
        class="fq-err"
      >
        {{ command.error.value }}
      </p>
      <button
        v-if="command.uncertain.value"
        type="button"
        class="fq-retry"
        :disabled="command.pending.value"
        @click="command.retry()"
      >
        按原意图核对队列操作
      </button>
    </template>
  </div>
</template>

<style scoped>
.followups.is-slim {
  margin: 0 10px 6px;
  padding: 6px 10px;
  border-radius: 8px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 6px;
}

.slim-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.slim-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--oc-fg-muted);
  font-size: 11.5px;
  cursor: pointer;
}

.slim-toggle:hover {
  color: var(--oc-fg);
}

.slim-list {
  display: grid;
  gap: 4px;
  padding-top: 4px;
  border-top: 1px solid var(--oc-border);
}

.slim-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 11.5px;
  color: var(--oc-fg);
}

.slim-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.slim-cancel {
  border: 0;
  padding: 2px;
  border-radius: 4px;
  background: transparent;
  color: var(--oc-fg-dim);
  cursor: pointer;
  flex-shrink: 0;
}

.slim-cancel:hover {
  color: var(--oc-danger);
}

.fq-err {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-danger);
}

.fq-retry {
  padding: 3px 8px;
  border-radius: 6px;
  border: 1px solid var(--oc-border-strong);
  background: var(--oc-bg-subtle);
  color: var(--oc-fg);
  font-size: 11.5px;
  cursor: pointer;
  width: fit-content;
}

h3 { margin: 0; font-size: 12.5px; font-weight: 600; }
p { margin: 0; font-size: 12px; color: var(--oc-fg-muted); line-height: 1.5; }
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
  font-size: 12px;
}
textarea {
  display: block;
  width: 100%;
  min-height: 4rem;
  padding: 8px 10px;
}
label { display: grid; gap: 6px; font-size: 12.5px; }
ol { margin: 0; padding-left: 18px; display: grid; gap: 8px; }
</style>
