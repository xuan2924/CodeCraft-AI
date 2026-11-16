<script setup lang="ts">
import { computed, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import NButton from '../components/ui/Button.vue';
import { ApiError } from '../api/request';
import { projectInstructions } from './projectInstructionsApi';
const props = defineProps<{ owner: string; exerciseId: string; taskId: string | null; running: boolean }>();
const emit = defineEmits<{ unauthorized: [] }>();
const snapshot = useQuery({ queryKey: computed(() => ['project-instructions', props.owner, props.exerciseId, props.taskId]), enabled: computed(() => props.taskId !== null), retry: false, gcTime: 0,
  queryFn: ({ signal }) => projectInstructions(props.owner, props.exerciseId, props.taskId!, signal), refetchInterval: computed(() => props.running ? 2000 : false) });
watch(() => props.running, running => { if (!running && props.taskId) void snapshot.refetch(); });
watch(snapshot.error, error => { if (error instanceof ApiError && error.status === 401) emit('unauthorized'); });
const labels = { LOADED: '已加载', MISSING: '未发现', LIMIT_EXCEEDED: '超出限额，未加载', READ_FAILED: '读取失败，未加载' };
</script>
<template>
  <details>
    <summary>项目指令 · AGENTS.md</summary>
    <p>每次模型请求前更新：根规则及本任务最近读取文件的祖先目录规则。子目录规则仅在对应目录优先，不改变审批权限。单文件 8 KiB，总正文 32 KiB，最多 32 个来源。</p>
    <p v-if="!taskId || snapshot.data.value === null">
      尚无已派发请求的指令快照；New 不继承其他会话聊天或旁支。
    </p>
    <p
      v-if="taskId && snapshot.isPending.value"
      role="status"
    >
      正在读取实际生效来源…
    </p>
    <p
      v-if="snapshot.isError.value"
      role="alert"
    >
      项目指令快照读取失败；不代表没有规则。<NButton @click="snapshot.refetch()">
        重新读取
      </NButton>
    </p>
    <NButton
      v-if="taskId"
      @click="snapshot.refetch()"
    >
      核对最近请求来源
    </NButton>
    <template v-if="snapshot.data.value">
      <p>最近一次已派发请求 · 正式修订 r{{ snapshot.data.value.revision }}（不是当前文件内容）</p>
      <p
        v-if="snapshot.data.value.scopeTruncated"
        role="alert"
      >
        目录来源超过限额，部分规则未加载。
      </p>
      <details
        v-for="source in snapshot.data.value.sources"
        :key="source.path"
      >
        <summary>{{ source.path }} · {{ labels[source.status] }} · 作用域 {{ source.scope }}</summary>
        <pre v-if="source.content !== null">{{ source.content }}</pre>
      </details>
    </template>
  </details>
</template>
<style scoped>
p { margin: 8px 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 20rem; overflow: auto; }
</style>
