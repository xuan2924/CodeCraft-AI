<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { Check, ChevronDown, ShieldAlert, ShieldCheck } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import Dialog from '../components/ui/Dialog.vue';
import { useCommand } from '../api/useCommand';
import * as api from './api';
import { commandPolicy, errorMessage } from './errors';
import { formatShortTimestamp } from './feeConsent';

const props = withDefaults(defineProps<{
  owner: string;
  exerciseId: string;
  credential: string;
  disabled: boolean;
  compact?: boolean;
}>(), {
  compact: false,
});
const emit = defineEmits<{ unauthorized: [] }>();
const client = useQueryClient();
const key = computed(() => ['copilot-approval-mode', props.owner, props.exerciseId]);
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.credential}`);
const current = useQuery({
  queryKey: key,
  queryFn: ({ signal }) => api.approvalMode(props.owner, props.exerciseId, signal),
  retry: false,
  gcTime: 0,
  refetchInterval: 5000,
});
const open = ref(false);
const auditOpen = ref(false);
const host = ref<globalThis.HTMLElement>();
const labels: Record<api.ApprovalMode, string> = { ASK: '逐次审批', AUTO: '自动审批' };
const command = useCommand<api.ApprovalChange>(() => scope.value, () => {
  void client.invalidateQueries({ queryKey: key.value, exact: true });
}, commandPolicy, () => emit('unauthorized'));

watch(scope, () => { open.value = false; }, { flush: 'sync' });
watch(current.error, (failure) => { if (commandPolicy.identityFailure(failure)) emit('unauthorized'); });

const editable = computed(() => !props.disabled && !!props.credential && !!current.data.value && !current.isError.value
  && !command.pending.value && !command.uncertain.value);

async function selectMode(mode: api.ApprovalMode) {
  open.value = false;
  if (!editable.value || !current.data.value || current.data.value.mode === mode) return;
  const { owner, exerciseId, credential } = props;
  const version = current.data.value.version;
  await command.submit((requestKey, signal) => api.changeApprovalMode(owner, exerciseId, credential, mode, version, requestKey, signal));
}

function handleDocumentClick(event: globalThis.MouseEvent) {
  if (!open.value) return;
  if (host.value && event.target instanceof globalThis.Node && !host.value.contains(event.target)) {
    open.value = false;
  }
}

onMounted(() => globalThis.document.addEventListener('click', handleDocumentClick));
onBeforeUnmount(() => globalThis.document.removeEventListener('click', handleDocumentClick));

defineExpose({ openAudit: () => { auditOpen.value = true; } });
</script>

<template>
  <div
    ref="host"
    class="approval-wrap"
    aria-label="Copilot 审批模式"
  >
    <button
      type="button"
      class="mode-pill-trigger"
      :class="{ 'is-auto': current.data.value?.mode === 'AUTO', 'is-error': current.isError.value }"
      :aria-expanded="open"
      aria-haspopup="menu"
      :disabled="current.isPending.value"
      @click="open = !open"
    >
      <component
        :is="current.data.value?.mode === 'AUTO' ? ShieldAlert : ShieldCheck"
        :size="12"
        aria-hidden="true"
      />
      <span>{{ current.isPending.value ? '模式读取中' : current.isError.value ? '模式异常' : labels[current.data.value?.mode ?? 'ASK'] }}</span>
      <ChevronDown
        :size="11"
        aria-hidden="true"
      />
    </button>

    <div
      v-if="open"
      class="mode-popover"
      role="menu"
      aria-label="切换审批模式"
    >
      <div
        v-if="current.isError.value"
        role="alert"
        class="mode-popover-alert"
      >
        <span>{{ errorMessage(current.error.value) }}</span>
        <Button
          variant="ghost"
          size="small"
          @click="current.refetch()"
        >
          重试
        </Button>
      </div>
      <template v-else>
        <button
          type="button"
          role="menuitemradio"
          class="mode-option"
          :aria-checked="current.data.value?.mode === 'ASK'"
          :disabled="!editable"
          @click="selectMode('ASK')"
        >
          <div class="mode-option-copy">
            <strong>逐次审批</strong>
            <small>每次修改前先核对 Diff</small>
          </div>
          <Check
            v-if="current.data.value?.mode === 'ASK'"
            :size="13"
            class="mode-check"
            aria-hidden="true"
          />
        </button>
        <button
          type="button"
          role="menuitemradio"
          class="mode-option"
          :aria-checked="current.data.value?.mode === 'AUTO'"
          :disabled="!editable"
          @click="selectMode('AUTO')"
        >
          <div class="mode-option-copy">
            <strong>自动审批</strong>
            <small>合法修改自动应用，保留 Diff 记录</small>
          </div>
          <Check
            v-if="current.data.value?.mode === 'AUTO'"
            :size="13"
            class="mode-check"
            aria-hidden="true"
          />
        </button>
      </template>
      <div class="mode-popover-foot">
        <span
          v-if="current.data.value"
          class="font-mono-num"
        >v{{ current.data.value.version }}</span>
        <button
          type="button"
          class="audit-link"
          @click="open = false; auditOpen = true"
        >
          变更记录 ({{ current.data.value?.history.length ?? 0 }})
        </button>
      </div>
    </div>

    <div
      v-if="command.error.value || command.uncertain.value"
      class="mode-inline-notice"
    >
      <span
        v-if="command.error.value"
        role="alert"
        class="mode-notice-err"
      >{{ command.error.value }}</span>
      <Button
        v-if="command.uncertain.value"
        variant="secondary"
        size="small"
        :disabled="command.pending.value || disabled"
        @click="command.retry"
      >
        核对原模式切换
      </Button>
    </div>

    <Dialog
      v-model:open="auditOpen"
      title="审批模式变更记录"
      description="仅免除逐次人工批准，不扩大文件、测试或命令权限。已登记提案保持原模式。"
    >
      <div
        v-if="current.data.value"
        class="audit-dialog-body"
      >
        <p class="audit-summary font-mono-num">
          当前生效：{{ labels[current.data.value.mode] }} · 版本 {{ current.data.value.version }}
        </p>
        <p
          v-if="!current.data.value.history.length"
          class="audit-empty"
        >
          尚无模式变更记录，当前使用默认逐次审批。
        </p>
        <ol
          v-else
          class="audit-list"
        >
          <li
            v-for="item in current.data.value.history"
            :key="item.id"
          >
            <span class="font-mono-num">v{{ item.version }}</span>
            <span>{{ labels[item.previousMode] }} → {{ labels[item.mode] }}</span>
            <time
              class="font-mono-num"
              :datetime="item.createdAt"
            >{{ formatShortTimestamp(item.createdAt) }}</time>
          </li>
        </ol>
      </div>
    </Dialog>
  </div>
</template>

<style scoped>
.approval-wrap {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.mode-pill-trigger {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 8px;
  border-radius: 6px;
  border: 1px solid transparent;
  background: rgba(255, 255, 255, 0.05);
  color: var(--oc-fg-muted);
  font-size: 11.5px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: background-color 120ms var(--oc-ease), color 120ms var(--oc-ease);
}

.mode-pill-trigger:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.09);
  color: var(--oc-fg);
}

.mode-pill-trigger.is-auto {
  color: var(--oc-warning);
  background: var(--oc-warning-bg);
}

.mode-pill-trigger.is-error {
  color: var(--oc-danger);
  background: var(--oc-danger-bg);
}

.mode-pill-trigger:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.mode-popover {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 0;
  z-index: 45;
  width: 248px;
  padding: 6px;
  border-radius: 10px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border-strong);
  box-shadow: var(--oc-shadow-md);
  display: grid;
  gap: 3px;
}

.mode-option {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 9px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--oc-fg);
  text-align: left;
  cursor: pointer;
}

.mode-option:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.06);
}

.mode-option:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.mode-option-copy {
  display: grid;
  gap: 1px;
}

.mode-option-copy strong {
  font-size: 12.5px;
  font-weight: 550;
}

.mode-option-copy small {
  font-size: 11px;
  color: var(--oc-fg-muted);
}

.mode-check {
  color: var(--oc-accent);
  flex-shrink: 0;
}

.mode-popover-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 2px;
  padding: 6px 8px 2px;
  border-top: 1px solid var(--oc-border);
  font-size: 11px;
  color: var(--oc-fg-dim);
}

.audit-link {
  border: 0;
  background: transparent;
  color: var(--oc-fg-muted);
  font-size: 11.5px;
  cursor: pointer;
  padding: 0;
}

.audit-link:hover {
  color: var(--oc-fg);
}

.mode-popover-alert {
  padding: 6px 8px;
  font-size: 12px;
  color: var(--oc-danger);
  display: grid;
  gap: 4px;
}

.mode-inline-notice {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.mode-notice-err {
  font-size: 11px;
  color: var(--oc-danger);
}

.audit-dialog-body {
  display: grid;
  gap: 12px;
}

.audit-summary {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg);
}

.audit-empty {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.audit-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  border-top: 1px solid var(--oc-border);
}

.audit-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid var(--oc-border);
  font-size: 12px;
  color: var(--oc-fg-muted);
}
</style>
