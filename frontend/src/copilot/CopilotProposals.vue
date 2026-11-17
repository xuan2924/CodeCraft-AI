<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import * as api from './api';
import { commandPolicy, errorMessage } from './errors';

const CodeDiff = defineAsyncComponent(() => import('../components/CodeDiff.vue'));
const props = defineProps<{ owner: string; taskId: string; credential: string; disabled: boolean }>();
const emit = defineEmits<{ unauthorized: []; changed: [] }>();
const client = useQueryClient();
const before = ref('0'); const selected = ref(''); const file = ref(''); const reason = ref(''); const consent = ref(false); const notice = ref('');
const scope = computed(() => `${props.owner}/${props.taskId}/${props.credential}/${selected.value}`);
const prefix = computed(() => ['copilot-proposals', props.owner, props.taskId]);
const list = useQuery({ queryKey: computed(() => [...prefix.value, 'list', before.value]),
  queryFn: ({ signal }) => api.proposals(props.owner, props.taskId, before.value, signal), retry: false, gcTime: 0, refetchInterval: 2000 });
const detail = useQuery({ queryKey: computed(() => [...prefix.value, 'detail', selected.value]), enabled: computed(() => !!selected.value),
  queryFn: ({ signal }) => api.proposalDetail(props.owner, props.taskId, selected.value, signal), retry: false, gcTime: 0, staleTime: Infinity });
const status = useQuery({ queryKey: computed(() => [...prefix.value, 'status', selected.value]), enabled: computed(() => !!selected.value),
  queryFn: ({ signal }) => api.proposalStatus(props.owner, props.taskId, selected.value, signal), retry: false, gcTime: 0, refetchInterval: 2000 });
const proposal = computed(() => status.data.value);
const current = computed(() => detail.data.value?.files.find((item) => item.path === file.value));
watch(() => list.data.value, (value) => { if (!selected.value && value?.items.length) selected.value = value.items[0]!.id; });
watch(() => list.data.value?.items.filter((item) => item.state === 'APPLIED').map((item) => item.resultRevision).join(','), (value) => { if (value) emit('changed'); });
watch(() => detail.data.value, (value) => { if (value && !value.files.some((item) => item.path === file.value)) file.value = value.files[0]!.path; });
watch(() => proposal.value?.resultRevision, (value) => { if (value !== null && value !== undefined) emit('changed'); });
watch([list.error, detail.error, status.error], (errors) => { if (errors.some(commandPolicy.identityFailure)) emit('unauthorized'); });
watch(() => `${props.owner}/${props.taskId}`, () => { before.value = '0'; selected.value = ''; file.value = ''; }, { flush: 'sync' });
watch(scope, () => { reason.value = ''; consent.value = false; notice.value = ''; }, { flush: 'sync' });
const command = useCommand<api.Proposal>(() => scope.value, () => {
  consent.value = false; void client.invalidateQueries({ queryKey: [...prefix.value, 'list'] });
  void client.invalidateQueries({ queryKey: [...prefix.value, 'status'] });
  void client.invalidateQueries({ queryKey: ['copilot', props.owner] });
}, commandPolicy, () => emit('unauthorized'));
// A proposal can be decided only once. Non-PENDING proves decision admission/cancellation,
// NOT write completion, even if another page decided it. A workspace revision alone proves neither.
watch([status.data, command.uncertain], ([value, uncertain]) => {
  if (uncertain && value?.id === selected.value && value.state !== 'PENDING') command.confirmObserved(value);
});
const ready = computed(() => !props.disabled && !!props.credential && proposal.value?.state === 'PENDING' && proposal.value.mode === 'ASK'
  && !!detail.data.value && !detail.isError.value && !status.isError.value && !status.isPending.value && !command.pending.value && !command.uncertain.value);
function decide(decision: 'APPROVE' | 'REJECT') {
  if (!ready.value || decision === 'APPROVE' && !consent.value) return;
  if (new globalThis.TextEncoder().encode(reason.value).length > 2048) { notice.value = '拒绝理由最多 2 KiB。'; return; }
  const { owner, taskId, credential } = props; const id = selected.value; const explanation = decision === 'REJECT' ? reason.value : null;
  notice.value = '';
  void command.submit((key, signal) => api.decide(owner, taskId, id, credential, decision, explanation, key, signal));
}
function label(proposal: api.Proposal): string {
  if (proposal.mode === 'AUTO') {
    if (proposal.state === 'PENDING') return '自动应用待校验';
    if (proposal.state === 'APPLYING') return '自动批准，写入结果待核对';
    if (proposal.state === 'APPLIED') return '自动应用成功';
    if (proposal.state === 'FAILED') return '自动应用失败';
  }
  return labels[proposal.state];
}
const labels: Record<api.Proposal['state'], string> = { PENDING: '等待整批审批', APPLYING: '已批准，写入结果待核对', APPLIED: '已应用', REJECTED: '已拒绝', INVALIDATED: '原提案已失效，原批准不可复用', FAILED: '未应用', CANCELLED: '已取消' };
</script>
<template>
  <section
    aria-label="Copilot 修改提案"
    class="proposals"
    :data-pending="proposal?.state === 'PENDING' && proposal.mode === 'ASK' ? 'true' : undefined"
  >
    <div class="proposals-head">
      <h3>修改提案 · 整批审批</h3>
      <Button
        variant="ghost"
        size="small"
        @click="list.refetch(); selected && status.refetch(); detail.isError.value && detail.refetch()"
      >
        重新读取提案
      </Button>
    </div>
    <p class="proposals-note">
      来自 edit / write / propose_changes 的整批变更均在此核对；默认先审批再写入，批准不保存或覆盖本地草稿。
    </p>
    <p
      v-if="list.isPending.value"
      role="status"
    >
      正在读取提案…
    </p>
    <p
      v-else-if="list.isError.value"
      role="alert"
      class="proposals-alert"
    >
      {{ errorMessage(list.error.value) }}
    </p>
    <p v-else-if="!list.data.value?.items.length">
      本页没有修改提案。
    </p>
    <ul v-if="list.data.value?.items.length">
      <li
        v-for="item in list.data.value?.items"
        :key="item.id"
      >
        <Button
          size="small"
          :variant="selected === item.id ? 'primary' : 'secondary'"
          :disabled="command.pending.value || command.uncertain.value"
          @click="selected = item.id"
        >
          {{ label(item) }} · 基线 {{ item.baseRevision }} · {{ item.fileCount }} 个文件
        </Button>
      </li>
    </ul>
    <div class="actions">
      <Button
        variant="ghost"
        size="small"
        @click="before = '0'; list.refetch()"
      >
        最近提案
      </Button>
      <Button
        v-if="list.data.value?.nextCursor"
        variant="ghost"
        size="small"
        @click="before = list.data.value.nextCursor"
      >
        更早的提案
      </Button>
    </div>
    <p
      v-if="selected && detail.isPending.value"
      role="status"
    >
      正在读取完整 Diff…
    </p>
    <p
      v-if="detail.isError.value"
      role="alert"
      class="proposals-alert"
    >
      {{ errorMessage(detail.error.value) }}
    </p>
    <p
      v-if="status.isError.value"
      role="alert"
      class="proposals-alert"
    >
      {{ errorMessage(status.error.value) }}
    </p>
    <template v-if="proposal && detail.data.value">
      <h4>{{ label(proposal) }} · 提案 {{ proposal.id }}</h4>
      <p>原始模式 {{ proposal.mode }} · 登记模式版本 {{ proposal.modeVersion }} · 保存基线 {{ proposal.baseRevision }} · 整批 {{ proposal.fileCount }} 个文件</p>
      <p v-if="proposal.resultRevision !== null">
        已确认修订 {{ proposal.resultRevision }} · {{ proposal.changed ? '文件已变化' : '内容无变化' }}
      </p>
      <p v-if="proposal.decisionReason">
        拒绝理由：{{ proposal.decisionReason }}
      </p>
      <p
        v-if="proposal.failureCode"
        class="proposals-alert"
      >
        {{ errorMessage(new ApiError(409, proposal.failureCode)) }}
      </p>
      <label :for="`proposal-file-${proposal.id}`">逐文件查看完整修改</label>
      <select
        :id="`proposal-file-${proposal.id}`"
        v-model="file"
      >
        <option
          v-for="item in detail.data.value.files"
          :key="item.path"
          :value="item.path"
        >
          {{ item.kind }} · {{ item.path }}
        </option>
      </select>
      <template v-if="current">
        <CodeDiff
          :key="proposal.id + current.path"
          :path="current.path"
          :before="current.before ?? ''"
          :after="current.after ?? ''"
        />
        <details>
          <summary>查看完整纯文本（Diff 标记计算受限时仍可核对全部内容）</summary>
          <h5>修改前{{ current.before === null ? ' · 文件不存在' : '' }}</h5><pre>{{ current.before }}</pre>
          <h5>修改后{{ current.after === null ? ' · 删除文件' : '' }}</h5><pre>{{ current.after }}</pre>
        </details>
      </template>
      <template v-if="proposal.state === 'PENDING' && proposal.mode === 'ASK'">
        <label class="consent-row"><input
          v-model="consent"
          type="checkbox"
          :disabled="!ready"
        >我已核对整批 Diff，同意保留草稿并应用此提案；完成后原任务将继续，可能产生模型费用。</label>
        <label :for="`proposal-reason-${proposal.id}`">拒绝理由（可选）</label>
        <textarea
          :id="`proposal-reason-${proposal.id}`"
          v-model="reason"
          :disabled="!ready"
          rows="2"
        />
        <div class="actions">
          <Button
            variant="primary"
            size="small"
            :disabled="!ready || !consent"
            @click="decide('APPROVE')"
          >
            批准整批修改
          </Button>
          <Button
            variant="danger"
            size="small"
            :disabled="!ready"
            @click="decide('REJECT')"
          >
            拒绝整批并继续任务
          </Button>
        </div>
      </template>
    </template>
    <p
      v-if="command.error.value || notice"
      role="alert"
      class="proposals-alert"
    >
      {{ command.error.value || notice }}
    </p>
    <p v-if="command.uncertain.value">
      审批结果未知；仅核对原请求，不换键、不修改内容。
    </p>
    <Button
      v-if="command.uncertain.value"
      size="small"
      :disabled="command.pending.value || disabled || !credential"
      @click="command.retry"
    >
      核对原审批请求
    </Button>
  </section>
</template>
<style scoped>
.proposals {
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-control);
  background: var(--oc-surface-2);
  padding: 10px 12px;
  display: grid;
  gap: 8px;
}

.proposals[data-pending="true"] {
  border-color: var(--oc-warning-border);
  border-left: 3px solid var(--oc-warning);
}

.proposals-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

h3, h4, h5 {
  margin: 0;
  font-size: 12.5px;
  font-weight: 600;
}

p {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
  line-height: 1.5;
}

.proposals-alert {
  color: var(--oc-danger);
}

ul {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 6px;
}

label {
  display: grid;
  gap: 5px;
  font-size: 12px;
}

.consent-row {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  color: var(--oc-fg);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

textarea, select {
  max-width: 100%;
  width: 100%;
  padding: 6px 10px;
  font-size: 12.5px;
}

pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 360px;
  overflow: auto;
  padding: 10px;
  border-radius: var(--radius-control);
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
  font-size: 12px;
}
</style>
