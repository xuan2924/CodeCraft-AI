<script setup lang="ts">
import { ref } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
import ExerciseRetentionEntry from './ExerciseRetentionEntry.vue';
import { history } from './retentionApi';
const props = defineProps<{ owner: string }>();
const emit = defineEmits<{ deleted: [id: string]; select: [id: string] }>();
const after = ref<string>();
const query = useQuery({ queryKey: ['workspace', props.owner, 'history', after],
  queryFn: ({ signal }) => history(signal, after.value), retry: false, refetchInterval: 5000 });
</script>
<template>
  <section aria-label="历史演练与删除">
    <h2>已结束演练</h2>
    <p>保留到期日不会因配置调整而提前。达到历史数量上限后，请先删除旧演练再新建，不会自动淘汰未到期记录。</p>
    <p
      v-if="query.isPending.value"
      role="status"
    >
      正在读取历史演练…
    </p>
    <p
      v-else-if="query.isError.value"
      role="alert"
    >
      无法读取历史演练，请重试；不会因此清除草稿。
    </p>
    <p v-else-if="!query.data.value?.length">
      没有保留的历史演练。
    </p>
    <ExerciseRetentionEntry
      v-for="entry in query.data.value"
      :key="`${owner}:${entry.exerciseId}`"
      :owner="owner"
      :entry="entry"
      @deleted="emit('deleted', $event)"
      @select="emit('select', $event)"
    />
    <NButton
      v-if="query.isError.value"
      @click="query.refetch()"
    >
      重试读取历史
    </NButton>
    <NButton
      v-if="after"
      @click="after = undefined"
    >
      返回第一页
    </NButton>
    <NButton
      v-if="query.data.value?.length === 50"
      @click="after = query.data.value.at(-1)?.exerciseId"
    >
      下一页
    </NButton>
  </section>
</template>
