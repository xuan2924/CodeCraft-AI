<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import NButton from '../components/ui/Button.vue';
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
const progress = useQuery({ queryKey: ['workspace', props.owner, 'deletion', props.entry.exerciseId],
  queryFn: ({ signal }) => api.progress(props.entry.exerciseId, signal), enabled: revoked, retry: false,
  refetchInterval: (query) => query.state.data?.state === 'SUCCEEDED' ? false : 3000 });
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
  <article :aria-label="`历史演练 ${entry.exerciseId}`">
    <NButton
      v-if="!revoked"
      @click="emit('select', entry.exerciseId)"
    >
      {{ entry.exerciseId }}
    </NButton>
    <span v-else>{{ entry.exerciseId }}</span>
    <p v-if="!revoked">
      保留至 {{ new Date(entry.retainUntil).toLocaleString() }}，到期整场删除。
    </p>
    <NButton
      v-if="!revoked && !confirmed"
      @click="confirmed = true"
    >
      删除演练
    </NButton>
    <section
      v-if="confirmed && !revoked"
      role="dialog"
      aria-label="删除演练确认"
      aria-modal="true"
    >
      <p>删除将立即撤销访问，并清除服务端数据及当前浏览器该演练的草稿；无法撤销。</p>
      <NButton
        :disabled="busy"
        @click="perform('delete')"
      >
        确认删除
      </NButton>
      <NButton
        :disabled="busy"
        @click="confirmed = false"
      >
        取消删除
      </NButton>
    </section>
    <p
      v-if="revoked"
      role="status"
    >
      {{ progress.isError.value ? '访问已撤销，暂不能读取清理进度' : progress.data.value?.state === 'SUCCEEDED' ? '删除完成' : progress.data.value?.state === 'FAILED' ? '删除失败，访问仍已撤销' : '正在删除，访问已撤销' }}
    </p>
    <p v-if="progress.data.value?.failureCode">
      {{ progress.data.value.failureCode === 'RECONCILIATION_REQUIRED' ? '等待在途操作或执行清理完成核对。' : '存储清理失败，后台将继续重试。' }}
    </p>
    <p v-if="progress.data.value?.state === 'FAILED'">
      下次重试：{{ new Date(progress.data.value.nextAttemptAt).toLocaleString() }}
    </p>
    <p
      v-if="progress.data.value?.interventionRequired"
      role="alert"
    >
      持续失败已超过 24 小时，请联系维护者人工处理；后台仍会重试。
    </p>
    <p
      v-if="progress.isError.value"
      role="alert"
    >
      无法读取删除进度；完成后的进度只保留 7 天。这不表示演练可恢复。
    </p>
    <NButton
      v-if="progress.data.value?.state === 'FAILED'"
      :disabled="busy"
      @click="perform('retry')"
    >
      重试清理
    </NButton>
    <NButton
      v-if="error && unresolved"
      :disabled="busy"
      @click="perform(unresolved.kind)"
    >
      核对并重试原删除操作
    </NButton>
    <p
      v-if="error"
      role="alert"
    >
      {{ error }}
    </p>
  </article>
</template>
