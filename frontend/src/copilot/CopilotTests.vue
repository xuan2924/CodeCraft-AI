<script setup lang="ts">
import { computed, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import * as api from './api';
import { errorMessage } from './errors';

const props = defineProps<{ owner: string; taskId: string; running: boolean }>();
const emit = defineEmits<{ inspect: [testId: string] }>();
const records = useQuery({
  queryKey: computed(() => ['copilot', props.owner, props.taskId, 'tests']),
  queryFn: ({ signal }) => api.tests(props.owner, props.taskId, signal),
  retry: false, gcTime: 0,
  refetchInterval: (query) => props.running || query.state.data?.some((test) => test.state === 'WAITING') ? 2000 : false,
});
watch(() => props.running, (running) => { if (!running) void records.refetch(); });
const labels = { PASSED: '通过', FAILED: '测试失败', TIMED_OUT: '执行超时', STOPPED: '已停止', ENVIRONMENT_ERROR: '环境错误', INTERRUPTED: '执行中断' };
</script>

<template>
  <section
    class="copilot-tests"
    aria-label="Copilot 关联测试"
  >
    <div class="tests-head">
      <h3>关联测试</h3>
      <span class="tests-hint">仅展示最近 20 条 · 结果对应标明版本，不包含当前草稿或后续保存</span>
    </div>
    <p
      v-if="records.isPending.value"
      class="tests-note"
    >
      正在读取测试记录…
    </p>
    <p
      v-else-if="records.isError.value"
      role="alert"
      class="tests-alert"
    >
      {{ errorMessage(records.error.value) }}
      <Button
        size="small"
        @click="records.refetch()"
      >
        重试读取测试记录
      </Button>
    </p>
    <p
      v-else-if="!records.data.value?.length"
      class="tests-note"
    >
      这个 Copilot 任务尚未启动测试。
    </p>
    <ul
      v-else
      class="tests-list"
    >
      <li
        v-for="test in records.data.value"
        :key="test.toolId"
      >
        <span class="test-label">版本 {{ test.revision }} · {{ test.resultState ? labels[test.resultState] : '等待确定结果，后续工具尚未执行' }}</span>
        <Button
          variant="secondary"
          size="small"
          @click="emit('inspect', test.testTaskId)"
        >
          查看原测试记录与日志
        </Button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.copilot-tests {
  padding: 10px 12px;
  border-radius: var(--radius-control);
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 8px;
}

.tests-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

h3 {
  margin: 0;
  font-size: 12.5px;
  font-weight: 600;
}

.tests-hint,
.tests-note {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-fg-muted);
}

.tests-alert {
  margin: 0;
  padding: 6px 10px;
  border-radius: var(--radius-control);
  background: var(--oc-danger-bg);
  border: 1px solid var(--oc-danger-border);
  color: var(--oc-danger);
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.tests-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 6px;
}

.tests-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  padding: 6px 8px;
  border-radius: var(--radius-control);
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  font-size: 12px;
}

.test-label {
  color: var(--oc-fg);
}
</style>
