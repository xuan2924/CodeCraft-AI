<script setup lang="ts">
import { computed, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { ApiError } from '../api/request';
import { projectInstructions } from './projectInstructionsApi';

const props = defineProps<{ owner: string; exerciseId: string; taskId: string | null; running: boolean }>();
const emit = defineEmits<{ unauthorized: [] }>();
const snapshot = useQuery({
  queryKey: computed(() => ['project-instructions', props.owner, props.exerciseId, props.taskId]),
  enabled: computed(() => props.taskId !== null),
  retry: false,
  gcTime: 0,
  queryFn: ({ signal }) => projectInstructions(props.owner, props.exerciseId, props.taskId!, signal),
  refetchInterval: computed(() => props.running ? 2000 : false),
});
watch(() => props.running, running => { if (!running && props.taskId) void snapshot.refetch(); });
watch(snapshot.error, error => { if (error instanceof ApiError && error.status === 401) emit('unauthorized'); });
const labels = { LOADED: '已加载', MISSING: '未发现', LIMIT_EXCEEDED: '超出限额', READ_FAILED: '读取失败' };
</script>

<template>
  <div class="instructions-view">
    <p
      v-if="!taskId || snapshot.data.value === null"
      class="inst-note"
    >
      尚无已派发请求的指令快照；发送任务后自动记录已生效的 AGENTS.md。
    </p>
    <p
      v-else-if="snapshot.isPending.value"
      role="status"
      class="inst-note"
    >
      正在读取实际生效来源…
    </p>
    <div
      v-else-if="snapshot.isError.value"
      role="alert"
      class="inst-error"
    >
      <span>项目指令快照读取失败。</span>
      <Button
        variant="ghost"
        size="small"
        @click="snapshot.refetch()"
      >
        重试
      </Button>
    </div>
    <template v-else-if="snapshot.data.value">
      <p class="inst-meta font-mono-num">
        最近派发修订 r{{ snapshot.data.value.revision }}
      </p>
      <p
        v-if="snapshot.data.value.scopeTruncated"
        role="alert"
        class="inst-error"
      >
        目录来源超过限额，部分规则未加载。
      </p>
      <details
        v-for="source in snapshot.data.value.sources"
        :key="source.path"
        class="inst-source"
      >
        <summary class="font-mono-num">
          {{ source.path }} · {{ labels[source.status] }}
        </summary>
        <pre v-if="source.content !== null">{{ source.content }}</pre>
      </details>
    </template>
  </div>
</template>

<style scoped>
.instructions-view {
  display: grid;
  gap: 8px;
}

.inst-note,
.inst-meta {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
  line-height: 1.5;
}

.inst-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  color: var(--oc-danger);
}

.inst-source {
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  font-size: 12px;
}

summary {
  cursor: pointer;
  color: var(--oc-fg);
}

pre {
  margin: 8px 0 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 16rem;
  overflow: auto;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--oc-bg);
  border: 1px solid var(--oc-border);
  font-size: 11.5px;
}
</style>
