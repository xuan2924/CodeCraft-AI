<script setup lang="ts">
import { computed, onScopeDispose } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import Tooltip from '../components/ui/Tooltip.vue';
import { ApiError } from '../api/request';
import { formatShortTimestamp } from '../copilot/feeConsent';
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
    <header class="storage-header">
      <div class="storage-title-wrap">
        <h2>数据容量</h2>
        <Tooltip text="停止、对账、结束和删除使用独立保留空间；容量不足拒绝写入时不会清除本地草稿。">
          <button
            type="button"
            class="rule-hint"
            aria-label="容量规则说明"
          >
            ?
          </button>
        </Tooltip>
      </div>
      <div class="storage-head-right">
        <span
          v-if="query.data.value"
          class="measured-at font-mono-num"
        >{{ formatShortTimestamp(query.data.value.measuredAt) }}</span>
        <Button
          variant="ghost"
          size="small"
          :loading="query.isFetching.value"
          @click="query.refetch()"
        >
          刷新占用
        </Button>
      </div>
    </header>
    <p
      v-if="query.isPending.value"
      role="status"
      class="storage-status"
    >
      正在查询数据占用…
    </p>
    <p
      v-else-if="query.isError.value"
      role="alert"
      class="storage-alert"
    >
      无法确认数据占用。{{ message(query.error.value) }}
    </p>
    <template v-else-if="query.data.value">
      <dl class="storage-metrics ui-divide-list">
        <div class="metric-row">
          <dt>已用 / 上限</dt>
          <dd class="font-mono-num">{{ format(used) }} / {{ format(query.data.value.limitBytes) }}</dd>
        </div>
        <div class="metric-row">
          <dt>在途预留</dt>
          <dd class="font-mono-num">{{ format(query.data.value.reservedBytes) }}</dd>
        </div>
        <div class="metric-row">
          <dt>收尾保留</dt>
          <dd class="font-mono-num">{{ format(query.data.value.recoveryBytes) }}</dd>
        </div>
      </dl>
      <p
        v-if="available === 0"
        role="status"
        class="storage-warn"
      >
        普通新增空间已满，请等待在途操作收敛后刷新。
      </p>
    </template>
  </section>
</template>

<style scoped>
.workspace-storage {
  padding: 12px 14px;
  display: grid;
  gap: 10px;
}

.storage-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.storage-title-wrap {
  display: flex;
  align-items: center;
  gap: 6px;
}

h2 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.rule-hint {
  width: 18px;
  height: 18px;
  border-radius: 999px;
  border: 1px solid var(--oc-border);
  background: transparent;
  color: var(--oc-fg-dim);
  font-size: 11px;
  cursor: help;
}

.storage-head-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.measured-at {
  font-size: 11.5px;
  color: var(--oc-fg-dim);
}

.storage-metrics {
  border-top: 1px solid var(--oc-border);
  border-bottom: 1px solid var(--oc-border);
  margin: 0;
}

.metric-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 0;
  font-size: 12.5px;
}

dt {
  color: var(--oc-fg-muted);
}

dd {
  margin-inline-start: 0;
  color: var(--oc-fg);
  font-weight: 550;
}

.storage-status {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.storage-warn {
  margin: 0;
  font-size: 12px;
  color: var(--oc-warning);
}

.storage-alert {
  margin: 0;
  font-size: 12px;
  color: var(--oc-danger);
}
</style>
