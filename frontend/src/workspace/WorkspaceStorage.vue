<script setup lang="ts">
import { computed, onScopeDispose } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
import { ApiError } from '../api/request';
import { message } from './api';
import * as api from './storageApi';

const props = defineProps<{ owner: string; exerciseId: string }>();
const emit = defineEmits<{ deleted: [id: string, owner: string] }>();
let disposed = false;
onScopeDispose(() => { disposed = true; });
const scope = computed(() => ['workspace', props.owner, 'storage', props.exerciseId] as const);
const query = useQuery({ queryKey: scope, retry: false, gcTime: 0, refetchInterval: 10000,
  queryFn: async ({ queryKey, signal }) => {
    const owner = queryKey[1]; const exercise = queryKey[3];
    try { return await api.usage(exercise, signal); }
    catch (failure) {
      if (!disposed && !signal.aborted && props.owner === owner && props.exerciseId === exercise
        && failure instanceof ApiError && failure.status === 404 && failure.code === 'EXERCISE_DELETED') emit('deleted', exercise, owner);
      throw failure;
    }
  } });
const used = computed(() => query.data.value ? query.data.value.goBytes + query.data.value.databaseBytes : 0);
const available = computed(() => query.data.value ? Math.max(0, query.data.value.limitBytes - query.data.value.recoveryBytes - used.value - query.data.value.reservedBytes) : undefined);
function format(bytes: number) { return bytes > 0 && bytes < 104857.6 ? '< 0.1 MiB' : `${(bytes / 1048576).toFixed(1)} MiB`; }
</script>

<template>
  <section
    class="workspace-storage"
    aria-label="演练数据容量"
  >
    <header>
      <h2>数据容量</h2>
      <NButton
        size="small"
        :loading="query.isFetching.value"
        @click="query.refetch()"
      >
        刷新占用
      </NButton>
    </header>
    <p
      v-if="query.isPending.value"
      role="status"
    >
      正在查询数据占用…
    </p>
    <p
      v-else-if="query.isError.value"
      role="alert"
    >
      无法确认数据占用。{{ message(query.error.value) }} 不会按零占用处理。
    </p>
    <template v-else-if="query.data.value">
      <dl>
        <div><dt>实际占用 / 单场上限</dt><dd>{{ format(used) }} / {{ format(query.data.value.limitBytes) }}</dd></div>
        <div><dt>在途预留</dt><dd>{{ format(query.data.value.reservedBytes) }}</dd></div>
        <div><dt>收尾保留</dt><dd>{{ format(query.data.value.recoveryBytes) }}</dd></div>
      </dl>
      <p
        v-if="available === 0"
        role="status"
      >
        普通新增空间不足。可等待在途操作收敛后刷新；不会提前删除仍需保留的数据。
      </p>
      <p>停止、对账、结束和删除使用保留空间；容量拒绝不会清除草稿。</p>
      <small>计量于 {{ new Date(query.data.value.measuredAt).toLocaleString() }}。在途预留不等于已写入的数据。</small>
    </template>
  </section>
</template>

<style scoped>
.workspace-storage { border-block-start: 1px solid var(--line); padding-block: 0.75rem; }
header { display: flex; align-items: baseline; justify-content: space-between; gap: 1rem; }
h2 { margin: 0; font-size: 1rem; }
dl { display: flex; flex-wrap: wrap; gap: 0.6rem 1.5rem; }
dt, small { color: var(--muted); }
dd { margin-inline-start: 0; font-variant-numeric: tabular-nums; }
</style>
