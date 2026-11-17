<script setup lang="ts">
import { computed, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { TestTube2 } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import * as api from './api';
import { errorMessage } from './errors';

const props = defineProps<{ owner: string; taskId: string; running: boolean }>();
const emit = defineEmits<{ inspect: [testId: string] }>();
const records = useQuery({
  queryKey: computed(() => ['copilot', props.owner, props.taskId, 'tests']),
  queryFn: ({ signal }) => api.tests(props.owner, props.taskId, signal),
  retry: false,
  gcTime: 0,
  refetchInterval: (query) => props.running || query.state.data?.some((test) => test.state === 'WAITING') ? 2000 : false,
});
watch(() => props.running, (running) => { if (!running) void records.refetch(); });
const labels = {
  PASSED: '通过',
  FAILED: '测试失败',
  TIMED_OUT: '执行超时',
  STOPPED: '已停止',
  ENVIRONMENT_ERROR: '环境错误',
  INTERRUPTED: '执行中断',
};
const visible = computed(() => (records.data.value?.length ?? 0) > 0 || records.isError.value);
</script>

<template>
  <section
    v-if="visible"
    class="copilot-tests"
    aria-label="Copilot 关联测试"
  >
    <div class="tests-head">
      <TestTube2
        :size="13"
        aria-hidden="true"
      />
      <h3>关联测试</h3>
    </div>
    <p
      v-if="records.isError.value"
      role="alert"
      class="tests-alert"
    >
      <span>{{ errorMessage(records.error.value) }}</span>
      <Button
        variant="ghost"
        size="small"
        @click="records.refetch()"
      >
        重试
      </Button>
    </p>
    <ul
      v-else-if="records.data.value?.length"
      class="tests-list"
    >
      <li
        v-for="test in records.data.value"
        :key="test.toolId"
      >
        <span class="test-label font-mono-num">
          r{{ test.revision }} · {{ test.resultState ? labels[test.resultState] : '执行中…' }}
        </span>
        <Button
          variant="ghost"
          size="small"
          @click="emit('inspect', test.testTaskId)"
        >
          查看日志
        </Button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.copilot-tests {
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 6px;
}

.tests-head {
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

.tests-alert {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-danger);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.tests-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 4px;
}

.tests-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 6px;
  background: var(--oc-bg-subtle);
  font-size: 11.5px;
}

.test-label {
  color: var(--oc-fg);
}
</style>
