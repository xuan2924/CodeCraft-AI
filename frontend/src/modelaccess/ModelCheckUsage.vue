<script setup lang="ts">
import { computed, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import NButton from '../components/ui/Button.vue';
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
  <section aria-label="连接检查用量与费用">
    <h4>本组检查用量与费用</h4>
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
    >
      {{ errorMessage(usage.error.value) }}
    </p>
    <UsageSummary
      v-else-if="usage.data.value"
      :data="usage.data.value"
    />
    <NButton
      :disabled="usage.isFetching.value"
      @click="usage.refetch()"
    >
      重新读取检查用量
    </NButton>
  </section>
</template>
