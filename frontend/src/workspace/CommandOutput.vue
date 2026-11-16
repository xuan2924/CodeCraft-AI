<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import NButton from '../components/ui/Button.vue';
import { ApiError } from '../api/request';
import { message } from './api';
import * as api from './commandApi';
import { stateLabels, syncLabels, reasonLabels } from './commandPresentation';
import ReadOnlyTestLog from './ReadOnlyTestLog.vue';
import WorkspaceFileDiff from './WorkspaceFileDiff.vue';

const props = defineProps<{ owner: string; task: api.CommandTask }>();
const emit = defineEmits<{ unauthorized: [] }>();
const showChanges = ref(false);
const scope = computed(() => `${props.owner}/${props.task.exerciseId}/${props.task.workspaceInstanceId}/${props.task.id}`);
watch(scope, () => { showChanges.value = false; }, { flush: 'sync' });
const changes = useQuery({ queryKey: computed(() => ['workspace-commands', props.owner, props.task.exerciseId, props.task.workspaceInstanceId, 'changes', props.task.id]),
  enabled: computed(() => showChanges.value && props.task.quiescent && props.task.synchronization !== 'PENDING'), retry: false, gcTime: 0,
  queryFn: ({ signal }) => api.changes(props.owner, props.task.exerciseId, props.task.workspaceInstanceId, props.task, signal) });
watch(changes.error, failure => {
  if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'PASSWORD_CHANGE_REQUIRED')) emit('unauthorized');
});
</script>

<template>
  <section
    class="command-output"
    aria-label="命令执行证据"
  >
    <p>执行身份：<code>{{ task.executionInstanceId }}</code> · 基线修订 {{ task.revision }}</p>
    <pre>/project/{{ task.directory }} $ {{ task.command }}</pre>
    <p role="status">
      {{ task.stopRequested && !task.quiescent ? '正在停止，尚未确认进程收敛' : stateLabels[task.state] }}<span v-if="task.exitCode !== null"> · 退出码 {{ task.exitCode }}</span>
    </p>
    <p v-if="task.reason">
      {{ reasonLabels[task.reason] ?? task.reason }}
    </p>
    <p role="status">
      {{ syncLabels[task.synchronization] }}<span v-if="task.synchronizedRevision"> · 正式修订 {{ task.synchronizedRevision }}</span><span v-if="task.synchronizationReason"> · {{ task.synchronizationReason }}</span>
    </p>
    <p
      v-if="task.quiescent && (task.cleanup !== 'SUCCEEDED' || task.copyCleanup !== 'SUCCEEDED')"
      role="status"
    >
      执行已退出，清理或文件确认仍待收敛；后台会继续核对。
    </p>
    <slot name="controls" />
    <p
      v-if="task.droppedBytes !== '0'"
      role="status"
    >
      输出保留窗口已截断，丢弃 {{ task.droppedBytes }} 字节；这不代表命令失败。
    </p>
    <p
      v-if="task.logsIncomplete"
      role="alert"
    >
      部分输出记录缺失，不能据此推断命令没有执行。
    </p>
    <ReadOnlyTestLog
      :logs="task.logs"
      label="命令"
    />
    <NButton
      v-if="task.quiescent && task.synchronization !== 'PENDING'"
      @click="showChanges = !showChanges"
    >
      {{ showChanges ? '收起文件 Diff' : '查看命令文件 Diff' }}
    </NButton>
    <template v-if="showChanges">
      <p
        v-if="changes.isPending.value"
        role="status"
      >
        正在读取保留的文件变化…
      </p>
      <p
        v-if="changes.isError.value"
        role="alert"
      >
        {{ message(changes.error.value) }} <NButton @click="changes.refetch()">
          重新读取 Diff
        </NButton>
      </p>
      <WorkspaceFileDiff
        v-if="changes.data.value"
        :files="changes.data.value.changes"
        :scope="scope"
        label="选择 Bash Diff 文件"
      />
    </template>
  </section>
</template>

<style scoped>
.command-output { display: grid; gap: 8px; min-width: 0; }
p { margin: 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; }
code { overflow-wrap: anywhere; }
</style>
