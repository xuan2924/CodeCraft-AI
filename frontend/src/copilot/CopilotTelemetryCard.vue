<script setup lang="ts">
import { computed } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { exerciseTelemetry } from './telemetryApi';
import { formatShortTimestamp } from './feeConsent';

const props = defineProps<{
  owner: string;
  exerciseId: string;
}>();

const query = useQuery({
  queryKey: computed(() => ['copilot-telemetry', props.owner, props.exerciseId]),
  queryFn: ({ signal }) => exerciseTelemetry(props.owner, props.exerciseId, signal),
  enabled: computed(() => !!props.owner && !!props.exerciseId),
  retry: false,
  gcTime: 0,
  refetchInterval: 10000,
});

function formatInt(value: number | null | undefined, suffix = ''): string {
  return value === null || value === undefined ? '-' : `${value}${suffix}`;
}

function formatUtilization(value: number | null | undefined): string {
  if (value === null || value === undefined) return '-';
  return `${Math.round(value * 100)}%`;
}
</script>

<template>
  <section
    class="telemetry-card"
    aria-label="Copilot 执行遥测"
  >
    <div class="telemetry-head">
      <h4>执行遥测（Loop 与并发）</h4>
      <Button
        v-if="query.isError.value"
        variant="ghost"
        size="small"
        :disabled="query.isFetching.value"
        @click="query.refetch()"
      >
        重试
      </Button>
    </div>
    <p
      v-if="query.isPending.value"
      role="status"
      class="telemetry-note"
    >
      正在读取执行遥测…
    </p>
    <p
      v-else-if="query.isError.value"
      class="telemetry-note"
    >
      暂无法读取执行遥测。
    </p>
    <template v-else-if="query.data.value">
      <p
        v-if="query.data.value.totals.taskCount === 0"
        class="telemetry-note"
      >
        暂无已记录的 Copilot 任务遥测。
      </p>
      <template v-else>
        <dl class="telemetry-grid">
          <dt>任务数 / 轮次</dt>
          <dd class="font-mono-num">
            {{ query.data.value.totals.taskCount }} / {{ query.data.value.totals.rounds }}
          </dd>
          <dt>工具调用 / 只读窗口</dt>
          <dd class="font-mono-num">
            {{ query.data.value.totals.tools }} / {{ query.data.value.totals.readWindows }}（槽位 {{ formatUtilization(query.data.value.totals.readWindowUtilization) }}）
          </dd>
          <dt>Explore 子任务</dt>
          <dd class="font-mono-num">
            {{ query.data.value.totals.exploreChildren }}
          </dd>
          <dt>压缩轮次 / 节省字节</dt>
          <dd class="font-mono-num">
            {{ query.data.value.totals.microCompactedRounds }} / {{ formatInt(query.data.value.totals.microSavedBytes, ' B') }}
          </dd>
          <dt>冲突重试恢复 / Steer</dt>
          <dd class="font-mono-num">
            {{ query.data.value.totals.recoveredRevisionConflicts }} / {{ query.data.value.totals.steerInterruptions }}
          </dd>
          <dt>聊天轮次 Token（仅 CHAT）</dt>
          <dd class="font-mono-num">
            入 {{ formatInt(query.data.value.totals.chatInputTokens) }} · 出 {{ formatInt(query.data.value.totals.chatOutputTokens) }}
          </dd>
        </dl>
        <p class="telemetry-note">
          Token 仅统计 CHAT 轮次，不含 SUMMARY 摘要；缺失样本显示 -。
        </p>
        <ul
          v-if="query.data.value.recentTasks.length"
          class="telemetry-tasks"
          aria-label="最近任务遥测明细"
        >
          <li
            v-for="task in query.data.value.recentTasks.slice(0, 5)"
            :key="task.taskId"
            class="telemetry-task-row font-mono-num"
          >
            <span>{{ task.state }}</span>
            <span>{{ task.rounds }} 轮 · {{ task.tools }} 工具</span>
            <span>{{ formatInt(task.modelElapsedMs, ' ms') }}</span>
            <span>{{ formatShortTimestamp(task.createdAt) }}</span>
          </li>
        </ul>
      </template>
    </template>
  </section>
</template>

<style scoped>
.telemetry-card {
  margin-top: 4px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 8px;
}

.telemetry-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

h4 {
  margin: 0;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--oc-fg);
}

.telemetry-grid {
  margin: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 6px 12px;
  font-size: 12px;
}

dt {
  color: var(--oc-fg-muted);
}

dd {
  margin: 0;
  color: var(--oc-fg);
  font-weight: 550;
  overflow-wrap: anywhere;
}

.telemetry-note {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--oc-fg-dim);
}

.telemetry-tasks {
  list-style: none;
  margin: 0;
  padding: 0;
  border-top: 1px solid var(--oc-border);
  padding-top: 6px;
  display: grid;
  gap: 4px;
}

.telemetry-task-row {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 6px 10px;
  font-size: 11px;
  color: var(--oc-fg-muted);
}
</style>
