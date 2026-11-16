<script setup lang="ts">
import { computed, onScopeDispose } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
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
      <Button
        size="small"
        :loading="query.isFetching.value"
        @click="query.refetch()"
      >
        刷新占用
      </Button>
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
.workspace-storage {
  padding: 16px;
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  box-shadow: var(--oc-hairline-top);
  display: grid;
  gap: 12px;
}
header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
h2 { margin: 0; font-size: 14.5px; font-weight: 600; }
dl {
  margin: 0;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px;
}
dl > div {
  padding: 12px;
  border-radius: 8px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
}
dt { color: var(--oc-fg-muted); font-size: 12px; margin-bottom: 4px; }
dd { margin-inline-start: 0; font-variant-numeric: tabular-nums; font-size: 14px; font-weight: 600; color: var(--oc-fg); }
p, small { margin: 0; color: var(--oc-fg-muted); font-size: 12.5px; line-height: 1.55; }
</style>
