<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
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
    <h3>审批模式</h3>
    <p
      v-if="current.isPending.value"
      role="status"
    >
      正在读取服务端审批模式…
    </p>
    <p
      v-if="current.isError.value"
      role="alert"
    >
      {{ errorMessage(current.error.value) }}
    </p>
    <p
      v-if="current.data.value"
      role="status"
    >
      {{ current.isError.value ? '上次确认' : '已生效' }}：{{ labels[current.data.value.mode] }} · 模式版本 {{ current.data.value.version }}
    </p>
    <p>默认逐次审批。auto 只免除修改的逐次批准，不扩大文件、测试或网络权限，不保存或覆盖本地草稿。已登记提案保持原模式；切到 auto 不批准旧待审批提案。</p>
    <label :for="`approval-mode-${exerciseId}`">期望审批模式（确认前不生效）</label>
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
    <label v-if="desired === 'AUTO'"><input
      v-model="consent"
      type="checkbox"
      :disabled="!editable"
    >我允许本场后续登记的合法修改自动应用，仍可查看 Diff 与结果。</label>
    <p
      v-if="command.error.value"
      role="alert"
    >
      {{ command.error.value }}
    </p>
    <p
      v-if="command.uncertain.value"
      role="status"
    >
      切换结果未确认，只能核对原键与原内容；当前模式读取不代表原请求已收敛。
    </p>
    <div class="actions">
      <NButton
        v-if="command.uncertain.value"
        :disabled="command.pending.value || disabled"
        @click="command.retry"
      >
        核对原模式切换
      </NButton>
      <NButton
        v-else
        :disabled="!editable || desired === 'AUTO' && !consent"
        :loading="command.pending.value"
        @click="change"
      >
        确认审批模式
      </NButton>
      <NButton
        :disabled="current.isFetching.value"
        @click="current.refetch()"
      >
        重新读取模式
      </NButton>
    </div>
    <template v-if="current.data.value">
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
    </template>
  </section>
</template>
<style scoped>
.approval-mode { padding: 14px; margin: 16px 0; border: 1px solid var(--line); }
p, li { overflow-wrap: anywhere; line-height: 1.6; }
label, select { display: block; margin: 10px 0; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
