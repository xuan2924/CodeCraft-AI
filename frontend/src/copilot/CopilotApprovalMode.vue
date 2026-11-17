<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { useCommand } from '../api/useCommand';
import * as api from './api';
import { commandPolicy, errorMessage } from './errors';

const props = defineProps<{ owner: string; exerciseId: string; credential: string; disabled: boolean }>();
const emit = defineEmits<{ unauthorized: [] }>();
const client = useQueryClient();
const key = computed(() => ['copilot-approval-mode', props.owner, props.exerciseId]);
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.credential}`);
const current = useQuery({ queryKey: key, queryFn: ({ signal }) => api.approvalMode(props.owner, props.exerciseId, signal), retry: false, gcTime: 0, refetchInterval: 5000 });
const desired = ref<api.ApprovalMode>('ASK');
const consent = ref(false);
const labels: Record<api.ApprovalMode, string> = { ASK: '逐次审批', AUTO: '自动审批' };
const command = useCommand<api.ApprovalChange>(() => scope.value, () => {
  consent.value = false;
  // A command receipt may be historical after another tab changed mode. Read current state, never paint its receipt as current.
  void client.invalidateQueries({ queryKey: key.value, exact: true });
}, commandPolicy, () => emit('unauthorized'));
watch(() => current.data.value?.version, () => {
  if (current.data.value && !command.pending.value && !command.uncertain.value) desired.value = current.data.value.mode;
});
watch(scope, () => { desired.value = 'ASK'; consent.value = false; }, { flush: 'sync' });
watch(desired, () => { consent.value = false; });
watch(current.error, (failure) => { if (commandPolicy.identityFailure(failure)) emit('unauthorized'); });
const editable = computed(() => !props.disabled && !!props.credential && !!current.data.value && !current.isError.value
  && !command.pending.value && !command.uncertain.value);
async function change() {
  if (!editable.value || !current.data.value || desired.value === 'AUTO' && !consent.value) return;
  const { owner, exerciseId, credential } = props;
  const mode = desired.value; const version = current.data.value.version;
  await command.submit((requestKey, signal) => api.changeApprovalMode(owner, exerciseId, credential, mode, version, requestKey, signal));
}
</script>

<template>
  <section
    aria-label="Copilot 审批模式"
    class="approval-mode"
  >
    <div class="approval-head">
      <div class="approval-title">
        <h3>审批模式</h3>
        <span
          v-if="current.data.value"
          role="status"
          class="ui-pill"
          :data-tone="current.data.value.mode === 'AUTO' ? 'warning' : 'accent'"
        >
          {{ current.isError.value ? '上次确认' : '已生效' }}：{{ labels[current.data.value.mode] }} · 模式版本 {{ current.data.value.version }}
        </span>
      </div>
      <Button
        variant="ghost"
        size="small"
        :disabled="current.isFetching.value"
        @click="current.refetch()"
      >
        重新读取模式
      </Button>
    </div>
    <p
      v-if="current.isPending.value"
      role="status"
    >
      正在读取服务端审批模式…
    </p>
    <p
      v-if="current.isError.value"
      role="alert"
      class="approval-alert"
    >
      {{ errorMessage(current.error.value) }}
    </p>
    <div class="approval-controls">
      <label
        :for="`approval-mode-${exerciseId}`"
        class="sr-only"
      >期望审批模式（确认前不生效）</label>
      <select
        :id="`approval-mode-${exerciseId}`"
        v-model="desired"
        :disabled="!editable"
      >
        <option value="ASK">
          逐次审批（ask for approve）
        </option>
        <option value="AUTO">
          自动审批（auto）
        </option>
      </select>
      <Button
        v-if="command.uncertain.value"
        size="small"
        :disabled="command.pending.value || disabled"
        @click="command.retry"
      >
        核对原模式切换
      </Button>
      <Button
        v-else
        variant="secondary"
        size="small"
        :disabled="!editable || desired === 'AUTO' && !consent"
        :loading="command.pending.value"
        @click="change"
      >
        确认审批模式
      </Button>
    </div>
    <label
      v-if="desired === 'AUTO'"
      class="approval-consent"
    ><input
      v-model="consent"
      type="checkbox"
      :disabled="!editable"
    >我允许本场后续登记的合法修改自动应用，仍可查看 Diff 与结果。</label>
    <p
      v-if="command.error.value"
      role="alert"
      class="approval-alert"
    >
      {{ command.error.value }}
    </p>
    <p
      v-if="command.uncertain.value"
      role="status"
    >
      切换结果未确认，只能核对原键与原内容；当前模式读取不代表原请求已收敛。
    </p>
    <details
      v-if="current.data.value"
      class="approval-details"
    >
      <summary>模式规则与最近 20 次变更记录（{{ current.data.value.history.length }}）</summary>
      <p>默认逐次审批。auto 只免除修改的逐次批准，不扩大文件、测试或网络权限，不保存或覆盖本地草稿。已登记提案保持原模式。</p>
      <h4>最近保留的 20 次模式确认</h4>
      <p v-if="!current.data.value.history.length">
        尚无模式确认记录，使用默认逐次审批。
      </p>
      <ol v-else>
        <li
          v-for="item in current.data.value.history"
          :key="item.id"
        >
          版本 {{ item.version }} · 本人：{{ labels[item.previousMode] }} → {{ labels[item.mode] }} ·
          <time :datetime="item.createdAt">{{ new Date(item.createdAt).toLocaleString() }}</time>
        </li>
      </ol>
    </details>
  </section>
</template>
<style scoped>
.approval-mode {
  padding: 10px 12px;
  border-radius: var(--radius-control);
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 8px;
}

.approval-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.approval-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

h3, h4 {
  margin: 0;
  font-size: 12.5px;
  font-weight: 600;
}

.approval-controls {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.approval-controls select {
  flex: 1 1 170px;
  min-width: 0;
  padding: 5px 9px;
  font-size: 12.5px;
}

.approval-consent {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.approval-details {
  font-size: 11.5px;
  color: var(--oc-fg-muted);
}

.approval-details summary {
  cursor: pointer;
}

.approval-details p,
.approval-details h4 {
  margin-top: 6px;
}

.approval-alert {
  color: var(--oc-danger);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
}

p, li {
  margin: 0;
  overflow-wrap: anywhere;
  line-height: 1.5;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

ol {
  margin: 6px 0 0;
  padding-left: 18px;
  display: grid;
  gap: 4px;
}
</style>
