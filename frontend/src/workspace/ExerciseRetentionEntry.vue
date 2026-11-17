<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { ApiError } from '../api/request';
import * as api from './retentionApi';
import { message } from './api';

const props = defineProps<{ owner: string; entry: api.HistoryEntry }>();
const emit = defineEmits<{ deleted: [id: string, owner: string]; select: [id: string] }>();
const client = useQueryClient();
const confirmed = ref(false);
const revoked = ref(props.entry.deletionState !== null);
const busy = ref(false);
const error = ref('');
let unresolved: { kind: 'delete' | 'retry'; key: string } | undefined;
let disposed = false;
const controller = new globalThis.AbortController();
const progress = useQuery({
  queryKey: ['workspace', props.owner, 'deletion', props.entry.exerciseId],
  queryFn: ({ signal }) => api.progress(props.entry.exerciseId, signal),
  enabled: revoked,
  retry: false,
  refetchInterval: (query) => query.state.data?.state === 'SUCCEEDED' ? false : 3000,
});
watch(() => props.entry.deletionState, (state) => {
  if (state !== null) { revoked.value = true; emit('deleted', props.entry.exerciseId, props.owner); }
}, { immediate: true });
onScopeDispose(() => { disposed = true; controller.abort(); });
async function perform(kind: 'delete' | 'retry') {
  if (busy.value || disposed) return;
  if (unresolved && unresolved.kind !== kind) return;
  unresolved ??= { kind, key: globalThis.crypto.randomUUID() };
  const owner = props.owner; const exercise = props.entry.exerciseId;
  const current = () => !disposed && props.owner === owner && props.entry.exerciseId === exercise;
  busy.value = true;
  error.value = '';
  try {
    const value = await (kind === 'delete' ? api.deleteExercise : api.retry)(exercise, unresolved.key, controller.signal);
    if (!current()) return;
    unresolved = undefined;
    revoked.value = true;
    confirmed.value = false;
    emit('deleted', exercise, owner);
    client.setQueryData(['workspace', owner, 'deletion', exercise], value);
    await client.invalidateQueries({ queryKey: ['workspace', owner, 'history'] });
  } catch (failure) {
    if (!current()) return;
    error.value = message(failure);
    if (failure instanceof ApiError && failure.status === 404 && failure.code === 'EXERCISE_DELETED') {
      revoked.value = true; confirmed.value = false; emit('deleted', exercise, owner);
    }
    if (failure instanceof ApiError && [400, 401, 403, 404, 409, 429].includes(failure.status)) unresolved = undefined;
  } finally { if (!disposed) busy.value = false; }
}
</script>

<template>
  <article
    class="retention-entry"
    :aria-label="`历史演练 ${entry.exerciseId}`"
  >
    <div class="entry-head">
      <Button
        v-if="!revoked"
        variant="ghost"
        size="small"
        @click="emit('select', entry.exerciseId)"
      >
        <code>{{ entry.exerciseId }}</code>
      </Button>
      <code v-else>{{ entry.exerciseId }}</code>
    </div>
    <p
      v-if="!revoked"
      class="entry-meta"
    >
      保留至 {{ new Date(entry.retainUntil).toLocaleString() }}，到期整场删除。
    </p>
    <Button
      v-if="!revoked && !confirmed"
      variant="danger"
      size="small"
      @click="confirmed = true"
    >
      删除演练
    </Button>
    <section
      v-if="confirmed && !revoked"
      class="delete-confirm"
      role="dialog"
      aria-label="删除演练确认"
      aria-modal="true"
    >
      <p>删除将立即撤销访问，并清除服务端数据及当前浏览器该演练的草稿；无法撤销。</p>
      <div class="entry-actions">
        <Button
          variant="danger"
          size="small"
          :disabled="busy"
          @click="perform('delete')"
        >
          确认删除
        </Button>
        <Button
          size="small"
          :disabled="busy"
          @click="confirmed = false"
        >
          取消删除
        </Button>
      </div>
    </section>
    <p
      v-if="revoked"
      role="status"
      class="entry-status"
    >
      {{ progress.isError.value ? '访问已撤销，暂不能读取清理进度' : progress.data.value?.state === 'SUCCEEDED' ? '删除完成' : progress.data.value?.state === 'FAILED' ? '删除失败，访问仍已撤销' : '正在删除，访问已撤销' }}
    </p>
    <p
      v-if="progress.data.value?.failureCode"
      class="entry-meta"
    >
      {{ progress.data.value.failureCode === 'RECONCILIATION_REQUIRED' ? '等待在途操作或执行清理完成核对。' : '存储清理失败，后台将继续重试。' }}
    </p>
    <p
      v-if="progress.data.value?.state === 'FAILED'"
      class="entry-meta"
    >
      下次重试：{{ new Date(progress.data.value.nextAttemptAt).toLocaleString() }}
    </p>
    <p
      v-if="progress.data.value?.interventionRequired"
      role="alert"
      class="entry-alert"
    >
      持续失败已超过 24 小时，请联系维护者人工处理；后台仍会重试。
    </p>
    <p
      v-if="progress.isError.value"
      role="alert"
      class="entry-alert"
    >
      无法读取删除进度；完成后的进度只保留 7 天。这不表示演练可恢复。
    </p>
    <Button
      v-if="progress.data.value?.state === 'FAILED'"
      size="small"
      :disabled="busy"
      @click="perform('retry')"
    >
      重试清理
    </Button>
    <Button
      v-if="error && unresolved"
      size="small"
      :disabled="busy"
      @click="perform(unresolved.kind)"
    >
      核对并重试原删除操作
    </Button>
    <p
      v-if="error"
      role="alert"
      class="entry-alert"
    >
      {{ error }}
    </p>
  </article>
</template>

<style scoped>
.retention-entry {
  margin-top: 8px;
  padding: 12px;
  border-radius: 8px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 8px;
  justify-items: start;
}

.entry-head code {
  font-size: 12px;
  color: var(--oc-fg);
}

.entry-meta,
.entry-status {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
  line-height: 1.5;
}

.entry-alert {
  margin: 0;
  font-size: 12px;
  color: var(--oc-danger);
}

.delete-confirm {
  width: 100%;
  padding: 10px;
  border-radius: var(--radius-control);
  background: var(--oc-danger-bg);
  border: 1px solid var(--oc-danger-border);
  display: grid;
  gap: 8px;
}

.delete-confirm p {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg);
  line-height: 1.5;
}

.entry-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
