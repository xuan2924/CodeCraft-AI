<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { GitFork } from '@lucide/vue';
import { commandPolicy, errorMessage } from './errors';
import * as api from './delegationApi';

const props = defineProps<{ owner: string; exerciseId: string; parentTaskId: string }>();
const emit = defineEmits<{ unauthorized: [] }>();
const selected = ref('');
const rows = useQuery({
  queryKey: computed(() => ['copilot-delegations', props.owner, props.exerciseId, props.parentTaskId]),
  queryFn: ({ signal }) => api.list(props.owner, props.exerciseId, props.parentTaskId, signal),
  retry: false,
  gcTime: 0,
  refetchInterval: 2000,
});
const current = computed(() => rows.data.value?.find(row => row.toolId === selected.value) ?? rows.data.value?.at(-1));
watch(() => `${props.owner}/${props.exerciseId}/${props.parentTaskId}`, () => { selected.value = ''; }, { flush: 'sync' });
watch(rows.error, error => { if (commandPolicy.identityFailure(error)) emit('unauthorized'); });
const visible = computed(() => (rows.data.value?.length ?? 0) > 0 || rows.isError.value);
</script>

<template>
  <section
    v-if="visible"
    class="delegations"
    aria-label="父子任务与交接"
  >
    <div class="del-head">
      <GitFork
        :size="13"
        aria-hidden="true"
      />
      <h3>委派子任务</h3>
    </div>
    <p
      v-if="rows.isError.value"
      role="alert"
      class="del-error"
    >
      {{ errorMessage(rows.error.value) }}
    </p>
    <ul
      v-else-if="rows.data.value?.length"
      class="del-list"
    >
      <li
        v-for="row in rows.data.value"
        :key="row.toolId"
      >
        <button
          type="button"
          class="del-btn"
          :aria-pressed="current?.toolId === row.toolId"
          @click="selected = row.toolId"
        >
          <span>{{ row.goal }}</span>
          <small class="font-mono-num">{{ row.child.state }} · {{ row.state === 'RESOLVED' ? '已交接' : '等待中' }}</small>
        </button>
      </li>
    </ul>
    <template v-if="current && !rows.isError.value">
      <details
        v-if="current.resultPayload"
        class="del-result"
      >
        <summary>查看交接结果</summary>
        <pre>{{ current.resultPayload }}</pre>
      </details>
      <slot
        :conversation-id="current.conversationId"
        :child="current.child"
      />
    </template>
  </section>
</template>

<style scoped>
.delegations {
  border-inline-start: 2px solid var(--oc-accent);
  padding: 8px 10px;
  border-radius: 0 8px 8px 0;
  background: var(--oc-surface-2);
  display: grid;
  gap: 8px;
}

.del-head {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--oc-fg);
}

h3 {
  margin: 0;
  font-size: 12px;
  font-weight: 600;
}

.del-error {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-danger);
}

.del-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 4px;
}

.del-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  text-align: start;
  padding: 5px 8px;
  border-radius: 6px;
  border: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
  color: var(--oc-fg);
  font-size: 12px;
  cursor: pointer;
}

.del-btn[aria-pressed="true"] {
  background: var(--oc-selected);
  border-color: var(--oc-border-accent);
}

.del-btn small {
  color: var(--oc-fg-muted);
  font-size: 11px;
  flex-shrink: 0;
}

.del-result summary {
  cursor: pointer;
  font-size: 11.5px;
  color: var(--oc-fg-muted);
}

pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 14rem;
  overflow: auto;
  padding: 8px;
  border-radius: 6px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  font-size: 11.5px;
}
</style>
