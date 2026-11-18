<script setup lang="ts">
import { computed, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import Tooltip from '../components/ui/Tooltip.vue';
import { usage } from './usageApi';
import { commandPolicy, errorMessage } from './errors';
import UsageSummary from '../modelaccess/UsageSummary.vue';
import CopilotTelemetryCard from './CopilotTelemetryCard.vue';

const props = withDefaults(defineProps<{
  owner: string;
  exerciseId: string;
  conversationId?: string;
  compact?: boolean;
}>(), {
  conversationId: undefined,
  compact: false,
});
const emit = defineEmits<{ unauthorized: []; openDetail: [] }>();
const result = useQuery({
  queryKey: computed(() => ['copilot-usage', props.owner, props.exerciseId, props.conversationId ?? props.exerciseId]),
  queryFn: ({ signal }) => usage(props.owner, props.exerciseId, signal, props.conversationId),
  enabled: computed(() => !!props.owner && !!props.exerciseId),
  retry: false,
  gcTime: 0,
  refetchInterval: 5000,
});
watch(result.error, (error) => { if (commandPolicy.identityFailure(error)) emit('unauthorized'); });

const percent = computed(() => {
  const ctx = result.data.value?.context;
  if (!ctx || ctx.state !== 'REPORTED' || !ctx.contextWindow || ctx.inputTokens === null) return null;
  const pct = Math.round((Number(ctx.inputTokens) / Number(ctx.contextWindow)) * 100);
  return Math.max(0, Math.min(100, pct));
});

const tooltipText = computed(() => {
  if (result.isPending.value) return '正在读取上下文占用…';
  if (result.isError.value) return errorMessage(result.error.value);
  const ctx = result.data.value?.context;
  if (!ctx) return '暂无上下文占用';
  if (ctx.state === 'WAITING_UPDATE') return '上下文已压缩，等待下次请求更新占用';
  if (ctx.state === 'REPORTED') {
    const model = ctx.modelId ? ` · 模型 ${ctx.modelId}` : '';
    return `输入 ${ctx.inputTokens} / ${ctx.contextWindow ?? '未知'} Token${model}（点击查看用量与压缩）`;
  }
  return '尚无可用输入用量（点击查看用量与压缩）';
});

const ringDash = computed(() => {
  const r = 5.5;
  const c = 2 * Math.PI * r;
  const p = percent.value ?? 0;
  return { circumference: c, offset: c * (1 - p / 100) };
});
</script>

<template>
  <Tooltip
    v-if="compact"
    :text="tooltipText"
  >
    <button
      type="button"
      class="usage-ring-btn font-mono-num"
      aria-label="上下文占用与用量详情"
      @click="emit('openDetail')"
    >
      <svg
        width="14"
        height="14"
        viewBox="0 0 14 14"
        aria-hidden="true"
      >
        <circle
          cx="7"
          cy="7"
          r="5.5"
          fill="none"
          stroke="rgba(255,255,255,0.14)"
          stroke-width="1.5"
        />
        <circle
          v-if="percent !== null"
          cx="7"
          cy="7"
          r="5.5"
          fill="none"
          :stroke="percent > 85 ? 'var(--oc-warning)' : 'var(--oc-accent)'"
          stroke-width="1.5"
          stroke-linecap="round"
          :stroke-dasharray="ringDash.circumference"
          :stroke-dashoffset="ringDash.offset"
          transform="rotate(-90 7 7)"
        />
      </svg>
      <span>{{ percent !== null ? `${percent}%` : '--' }}</span>
    </button>
  </Tooltip>

  <section
    v-else
    class="copilot-usage"
    aria-label="Copilot 用量与费用"
  >
    <div class="usage-head">
      <h3>上下文占用与累计用量</h3>
      <Button
        v-if="result.isError.value"
        variant="ghost"
        size="small"
        :disabled="result.isFetching.value"
        @click="result.refetch()"
      >
        重试
      </Button>
    </div>
    <p
      v-if="result.isPending.value"
      role="status"
    >
      正在读取用量…
    </p>
    <p
      v-else-if="result.isError.value"
      role="alert"
      class="usage-error"
    >
      {{ errorMessage(result.error.value) }}
    </p>
    <template v-else-if="result.data.value">
      <p v-if="result.data.value.context.state === 'WAITING_UPDATE'">
        已压缩，等待下次请求更新占用。
      </p>
      <p v-else-if="result.data.value.context.state === 'REPORTED'">
        最近输入 / 窗口：{{ result.data.value.context.inputTokens }} / {{ result.data.value.context.contextWindow ?? '未知' }} Token
      </p>
      <p v-else>
        占用未知：最近请求尚无可用的输入用量。
      </p>
      <p v-if="result.data.value.context.windowSource === 'USER_CONFIGURATION'">
        窗口来源：用户配置（{{ result.data.value.context.contextWindow ?? '未知' }} Token）。
      </p>
      <p v-if="result.data.value.context.modelId">
        派发模型：{{ result.data.value.context.modelId }}
      </p>
      <UsageSummary :data="result.data.value" />
      <CopilotTelemetryCard
        :owner="owner"
        :exercise-id="exerciseId"
      />
    </template>
  </section>
</template>

<style scoped>
.usage-ring-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 6px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--oc-fg-muted);
  font-size: 11px;
  cursor: pointer;
}

.usage-ring-btn:hover {
  background: rgba(255, 255, 255, 0.06);
  color: var(--oc-fg);
}

.copilot-usage {
  display: grid;
  gap: 8px;
}

.usage-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--oc-fg);
}

p {
  margin: 0;
  line-height: 1.5;
  overflow-wrap: anywhere;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.usage-error {
  color: var(--oc-danger);
}
</style>
