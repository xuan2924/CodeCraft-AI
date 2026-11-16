<script setup lang="ts">
import { computed, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import NButton from '../components/ui/Button.vue';
import { usage } from './usageApi';
import { commandPolicy, errorMessage } from './errors';
import UsageSummary from '../modelaccess/UsageSummary.vue';
const props = defineProps<{ owner: string; exerciseId: string; conversationId?: string }>();
const emit = defineEmits<{ unauthorized: [] }>();
const result = useQuery({ queryKey: computed(() => ['copilot-usage', props.owner, props.exerciseId, props.conversationId ?? props.exerciseId]),
  queryFn: ({ signal }) => usage(props.owner, props.exerciseId, signal, props.conversationId),
  enabled: computed(() => !!props.owner && !!props.exerciseId), retry: false, gcTime: 0, refetchInterval: 5000 });
watch(result.error, error => { if (commandPolicy.identityFailure(error)) emit('unauthorized'); });
</script>

<template>
  <section
    class="copilot-usage"
    aria-label="Copilot 用量与费用"
  >
    <h3>上下文占用与累计用量</h3>
    <p
      v-if="result.isPending.value"
      role="status"
    >
      正在读取用量…
    </p>
    <p
      v-else-if="result.isError.value"
      role="alert"
    >
      {{ errorMessage(result.error.value) }}
    </p>
    <template v-else-if="result.data.value">
      <p v-if="result.data.value.context.state === 'WAITING_UPDATE'">
        已压缩，等待下次请求更新占用。
      </p>
      <p v-else-if="result.data.value.context.state === 'REPORTED'">
        最近请求的供应商输入 / 窗口：{{ result.data.value.context.inputTokens }} / {{ result.data.value.context.contextWindow ?? '未知' }} Token
      </p>
      <p v-else>
        占用未知：最近请求尚无可用的供应商输入用量。
      </p>
      <p v-if="result.data.value.context.windowSource === 'USER_CONFIGURATION'">
        窗口来源：用户配置（{{ result.data.value.context.contextWindow ?? '未知' }} Token），不是供应商实测容量；填大可能请求超限。
      </p>
      <p v-if="result.data.value.context.modelId">
        对应派发模型：{{ result.data.value.context.modelId }}。缓存输入仍占窗口；不使用调用前估算冒充实测。
      </p>
      <UsageSummary :data="result.data.value" />
    </template>
    <NButton
      :disabled="result.isFetching.value"
      @click="result.refetch()"
    >
      重新读取演练用量
    </NButton>
  </section>
</template>

<style scoped>
.copilot-usage { margin-block: 16px; }
p { line-height: 1.6; overflow-wrap: anywhere; }
</style>
