<script setup lang="ts">
import { computed, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { checkUsage } from './usageApi';
import { errorMessage, identityFailure } from './errors';
import UsageSummary from './UsageSummary.vue';
const props = defineProps<{ owner: string; checkId: string }>();
const emit = defineEmits<{ unauthorized: [] }>();
const usage = useQuery({ queryKey: computed(() => ['model-access', props.owner, 'check-usage', props.checkId]),
  queryFn: ({ signal }) => checkUsage(props.owner, props.checkId, signal),
  enabled: computed(() => !!props.owner && !!props.checkId), retry: false, gcTime: 0, refetchInterval: 5000 });
watch(usage.error, error => { if (identityFailure(error)) emit('unauthorized'); });
</script>

<template>
  <section
    class="check-usage"
    aria-label="连接检查用量与费用"
  >
    <div class="check-usage-head">
      <h4>本组检查用量与费用</h4>
      <Button
        variant="ghost"
        size="small"
        :disabled="usage.isFetching.value"
        @click="usage.refetch()"
      >
        重新读取检查用量
      </Button>
    </div>
    <p>仅记录本组实际派发；复用的检查不重复计数，与演练费用分开。</p>
    <p
      v-if="usage.isPending.value"
      role="status"
    >
      正在读取用量…
    </p>
    <p
      v-else-if="usage.isError.value"
      role="alert"
      class="failure"
    >
      {{ errorMessage(usage.error.value) }}
    </p>
    <UsageSummary
      v-else-if="usage.data.value"
      :data="usage.data.value"
    />
  </section>
</template>

<style scoped>
.check-usage {
  padding: 12px;
  border-radius: var(--radius-control);
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 8px;
}

.check-usage-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

h4 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

p {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
  line-height: 1.5;
}

.failure {
  color: var(--oc-danger);
}
</style>
