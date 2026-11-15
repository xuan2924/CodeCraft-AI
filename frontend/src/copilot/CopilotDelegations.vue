<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { commandPolicy, errorMessage } from './errors';
import * as api from './delegationApi';
const props = defineProps<{ owner: string; exerciseId: string; parentTaskId: string }>();
const emit = defineEmits<{ unauthorized: [] }>();
const selected = ref('');
const rows = useQuery({ queryKey: computed(() => ['copilot-delegations', props.owner, props.exerciseId, props.parentTaskId]),
  queryFn: ({ signal }) => api.list(props.owner, props.exerciseId, props.parentTaskId, signal), retry: false, gcTime: 0, refetchInterval: 2000 });
const current = computed(() => rows.data.value?.find(row => row.toolId === selected.value) ?? rows.data.value?.at(-1));
watch(() => `${props.owner}/${props.exerciseId}/${props.parentTaskId}`, () => { selected.value = ''; }, { flush: 'sync' });
watch(rows.error, error => { if (commandPolicy.identityFailure(error)) emit('unauthorized'); });
</script>
<template>
  <section
    class="delegations"
    aria-label="父子任务与交接"
  >
    <h3>委派子任务</h3>
    <p>同一父任务最多 8 次、一层且一次一个。父任务等待交接并释放 worker；子任务继续使用你的模型、费用和本场 ASK/AUTO 设置。不是独立的平台 Agent。</p>
    <p>停止父任务会连带停止子任务，并等待命令及文件同步收敛；停止子任务只向父任务交回停止结果。已写入的修改不会撤销。父任务 steer 在子任务收敛后生效，不立即改变子任务目标。</p>
    <p
      v-if="rows.isPending.value"
      role="status"
    >
      正在读取父子关系…
    </p>
    <p
      v-else-if="rows.isError.value"
      role="alert"
    >
      {{ errorMessage(rows.error.value) }}
    </p>
    <p v-else-if="!rows.data.value?.length">
      当前父任务尚未创建子任务。
    </p>
    <ul>
      <li
        v-for="row in rows.data.value"
        :key="row.toolId"
      >
        <button
          type="button"
          :aria-pressed="current?.toolId === row.toolId"
          @click="selected = row.toolId"
        >
          {{ row.goal }} · {{ row.child.state }} · {{ row.state === 'RESOLVED' ? '已持久交接' : '父任务等待交接' }}
        </button>
        <small>父 {{ row.parentTaskId }} → 子 {{ row.child.id }} · 工具 {{ row.toolId }}</small>
      </li>
    </ul>
    <template v-if="current && !rows.isError.value">
      <p>当前子目标：{{ current.goal }}</p>
      <details v-if="current.resultPayload">
        <summary>已交给父任务的有界结果（非全部子任务历史）</summary>
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
.delegations { border-inline-start: 3px solid #687a8c; padding-inline-start: 1rem; margin-block: 1rem; }
small { display: block; overflow-wrap: anywhere; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 20rem; overflow: auto; }
button { text-align: start; overflow-wrap: anywhere; }
</style>
