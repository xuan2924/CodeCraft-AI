<script setup lang="ts">
import { computed, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import NButton from '../components/ui/Button.vue';
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
  <section aria-label="Copilot 关联测试">
    <h3>关联测试</h3>
    <p v-if="records.isPending.value">
      正在读取测试记录…
    </p>
    <p
      v-else-if="records.isError.value"
      role="alert"
    >
      {{ errorMessage(records.error.value) }}
      <NButton @click="records.refetch()">
        重试读取测试记录
      </NButton>
    </p>
    <p v-else-if="!records.data.value?.length">
      这个 Copilot 任务尚未启动测试。
    </p>
    <template v-else>
      <p>仅显示最近 20 条关联测试。结果只对应标明的保存版本，不包含当前草稿或后续保存。</p>
      <ul>
        <li
          v-for="test in records.data.value"
          :key="test.toolId"
        >
          <span>版本 {{ test.revision }}，基础测试：{{ test.resultState ? labels[test.resultState] : '等待确定结果，后续工具尚未执行' }}</span>
          <NButton @click="emit('inspect', test.testTaskId)">
            查看原测试记录与日志
          </NButton>
        </li>
      </ul>
    </template>
  </section>
</template>
