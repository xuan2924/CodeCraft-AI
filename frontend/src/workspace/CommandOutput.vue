<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
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
    <div class="cmd-echo-line font-mono-num">
      <span class="cmd-echo-cmd">$ {{ task.directory === '.' ? '' : `cd ${task.directory} && ` }}{{ task.command }}</span>
      <span class="cmd-echo-meta">
        <span>{{ task.stopRequested && !task.quiescent ? '正在停止' : stateLabels[task.state] }}</span>
        <span v-if="task.exitCode !== null">退出码 {{ task.exitCode }}</span>
        <span>{{ syncLabels[task.synchronization] }}<template v-if="task.synchronizedRevision"> 正式修订 {{ task.synchronizedRevision }}</template></span>
        <Button
          v-if="task.quiescent && task.synchronization !== 'PENDING'"
          variant="ghost"
          size="small"
          class="diff-toggle-btn"
          @click="showChanges = !showChanges"
        >
          {{ showChanges ? '收起文件 Diff' : '查看命令文件 Diff' }}
        </Button>
      </span>
    </div>
    <p
      v-if="task.reason"
      class="cmd-note"
    >
      {{ reasonLabels[task.reason] ?? task.reason }}
    </p>
    <p
      v-if="task.synchronizationReason"
      class="cmd-note"
    >
      {{ task.synchronizationReason }}
    </p>
    <p
      v-if="task.quiescent && (task.cleanup !== 'SUCCEEDED' || task.copyCleanup !== 'SUCCEEDED')"
      role="status"
      class="cmd-note"
    >
      资源清理待后台收敛。
    </p>
    <slot name="controls" />
    <p
      v-if="task.droppedBytes !== '0'"
      role="status"
      class="cmd-warn"
    >
      输出保留窗口已截断，丢弃 {{ task.droppedBytes }} 字节。
    </p>
    <p
      v-if="task.logsIncomplete"
      role="alert"
      class="cmd-alert"
    >
      部分输出记录缺失，不能据此推断命令没有执行。
    </p>
    <ReadOnlyTestLog
      :logs="task.logs"
      label="命令"
    />
    <template v-if="showChanges">
      <p
        v-if="changes.isPending.value"
        role="status"
        class="cmd-note"
      >
        正在读取文件变更…
      </p>
      <p
        v-if="changes.isError.value"
        role="alert"
        class="cmd-alert"
      >
        {{ message(changes.error.value) }}
        <Button
          variant="ghost"
          size="small"
          @click="changes.refetch()"
        >
          重试
        </Button>
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
.command-output {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.cmd-echo-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
  padding-bottom: 4px;
  border-bottom: 1px solid var(--oc-border);
}

.cmd-echo-cmd {
  color: var(--oc-fg);
  font-weight: 550;
  min-width: 0;
  overflow-wrap: break-word;
}

.cmd-echo-meta {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 11px;
  color: var(--oc-fg-muted);
}

.diff-toggle-btn {
  min-height: 22px;
  padding: 1px 6px;
  font-size: 11px;
}

.cmd-note,
.cmd-warn,
.cmd-alert {
  margin: 0;
  font-size: 11.5px;
}

.cmd-note {
  color: var(--oc-fg-muted);
}

.cmd-warn {
  color: var(--oc-warning);
}

.cmd-alert {
  color: var(--oc-danger);
}
</style>
