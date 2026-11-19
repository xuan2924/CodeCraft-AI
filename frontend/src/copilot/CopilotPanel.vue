<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import {
  ArrowUp,
  AtSign,
  FileCode2,
  GitBranch,
  History,
  ListOrdered,
  MoreHorizontal,
  Slash,
  Sparkles,
  Square,
  X,
} from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import Dialog from '../components/ui/Dialog.vue';
import Tooltip from '../components/ui/Tooltip.vue';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import * as api from './api';
import { clearOwnerComposerDrafts, draftKey, loadComposerDraft, saveComposerDraft } from './composerDrafts';
import { commandPolicy, errorMessage } from './errors';
import { grantCopilotFeeConsent, hasCopilotFeeConsent } from './feeConsent';
import CopilotProposals from './CopilotProposals.vue';
import CopilotTests from './CopilotTests.vue';
import CopilotCommands from './CopilotCommands.vue';
import CopilotApprovalMode from './CopilotApprovalMode.vue';
import CopilotSteers from './CopilotSteers.vue';
import CopilotFollowups from './CopilotFollowups.vue';
import CopilotBtw from './CopilotBtw.vue';
import CopilotDelegations from './CopilotDelegations.vue';
import CopilotContext from './CopilotContext.vue';
import CopilotUsage from './CopilotUsage.vue';
import CopilotSkills from './CopilotSkills.vue';
import CopilotReferences from './CopilotReferences.vue';
import { selection, type ReferencePage } from './referenceApi';
import CopilotProjectInstructions from './CopilotProjectInstructions.vue';
import CopilotWebResult from './CopilotWebResult.vue';
import CopilotToolResult from './CopilotToolResult.vue';
import CopilotMessageBody from './CopilotMessageBody.vue';
import { isStructuredToolReceipt, splitAssistantToolCalls } from './toolPresentation';

const props = withDefaults(defineProps<{
  owner: string;
  exerciseId: string;
  conversationId?: string;
  delegated?: boolean;
  paths?: string[];
  credential: string;
  disabled: boolean;
  prepare: () => Promise<boolean>;
  subview?: 'chat' | 'btw';
  moreTarget?: globalThis.HTMLElement | null;
}>(), {
  conversationId: undefined,
  delegated: false,
  paths: undefined,
  subview: 'chat',
  moreTarget: null,
});

const emit = defineEmits<{
  unauthorized: [];
  changed: [];
  'inspect-test': [testId: string];
  fork: [messageId: string];
  'switch-chat': [];
  'switch-btw': [];
}>();

const client = useQueryClient();
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.conversationId ?? props.exerciseId}/${props.credential}`);
const currentDraftKey = computed(() => draftKey(props.owner, props.exerciseId, props.conversationId));
const initialDraft = loadComposerDraft(currentDraftKey.value);
const text = ref(initialDraft.text);
const selectedSkills = ref<string[]>(initialDraft.selectedSkills);
const references = ref<ReferencePage[]>(initialDraft.references);
const childContextBusy = ref(false);
const saving = ref(false);
const saveError = ref('');
const handoffNotice = ref('');
const after = ref('-1');
const connected = ref(false);
const transientPreview = ref<api.PreviewEvent | null>(null);

const slashOpen = ref(false);
const atOpen = ref(false);
const moreOpen = ref(false);
const usageModalOpen = ref(false);
const instructionsModalOpen = ref(false);
const feeConsentOpen = ref(false);
const pendingAction = ref<null | 'send' | 'followup'>(null);

const composerHost = ref<globalThis.HTMLElement | null>(null);
const moreHost = ref<globalThis.HTMLElement | null>(null);
const textareaRef = ref<globalThis.HTMLTextAreaElement | null>(null);
const approvalModeRef = ref<{ openAudit: () => void } | null>(null);
const followupRef = ref<{ enqueueDirect: (prompt: string) => boolean; pending: number; paused: boolean; busy: boolean } | null>(null);
const steerRef = ref<{ sendDirect: (content: string) => Promise<boolean>; accepting: boolean; pending: boolean; uncertain: boolean } | null>(null);

const taskKey = computed(() => ['copilot', props.owner, props.exerciseId, 'task', props.conversationId ?? props.exerciseId]);
const historyKey = computed(() => ['copilot', props.owner, props.exerciseId, 'messages', props.conversationId ?? props.exerciseId, after.value]);
const contextKey = computed(() => ['copilot-context', props.owner, props.exerciseId, props.conversationId ?? props.exerciseId]);

const latest = useQuery({
  queryKey: taskKey,
  queryFn: ({ signal }) => api.latest(props.owner, props.exerciseId, signal, props.conversationId),
  retry: false,
  gcTime: 0,
  refetchInterval: query => (api.active(query.state.data?.task) && !connected.value ? 2000 : false),
});
const history = useQuery({
  queryKey: historyKey,
  queryFn: ({ signal }) => api.messages(props.owner, props.exerciseId, after.value, signal, props.conversationId),
  retry: false,
  gcTime: 0,
});
const contextQuery = useQuery({
  queryKey: contextKey,
  queryFn: ({ signal }) => api.context(props.owner, props.exerciseId, signal, props.conversationId),
  enabled: computed(() => !props.delegated && !!props.owner && !!props.exerciseId),
  retry: false,
  gcTime: 0,
  refetchInterval: query => (query.state.data?.requests.some(item => item.state === 'WAITING' || item.state === 'RUNNING') ? 2000 : false),
});

const contextBusy = computed(() => {
  if (props.delegated) return false;
  if (childContextBusy.value) return true;
  if (!contextQuery.data.value || contextQuery.isError.value) return true;
  return contextQuery.data.value.requests.some(item => item.state === 'WAITING' || item.state === 'RUNNING');
});

const task = computed(() => latest.data.value?.task);
const running = computed(() => api.active(task.value));
const preview = computed(() => api.resolveDisplayPreview(transientPreview.value, latest.data.value, running.value));

watch([latest.error, history.error, contextQuery.error], (errors) => {
  if (errors.some(commandPolicy.identityFailure)) emit('unauthorized');
});
watch(() => latest.data.value?.lastSequence, () => {
  transientPreview.value = null;
  void client.invalidateQueries({ queryKey: ['copilot', props.owner, props.exerciseId, 'messages'] });
});
watch([text, selectedSkills, references], () => {
  if (props.delegated) return;
  saveComposerDraft(currentDraftKey.value, {
    text: text.value,
    selectedSkills: selectedSkills.value,
    references: references.value,
  });
}, { deep: true });

watch(currentDraftKey, (nextKey, prevKey) => {
  const prevOwner = prevKey?.split('/')[0];
  if (prevOwner && prevOwner !== props.owner) {
    clearOwnerComposerDrafts(prevOwner);
  }
  const restored = props.delegated ? { text: '', selectedSkills: [], references: [] } : loadComposerDraft(nextKey);
  text.value = restored.text;
  selectedSkills.value = restored.selectedSkills;
  references.value = restored.references;
  transientPreview.value = null;
  slashOpen.value = false;
  atOpen.value = false;
  moreOpen.value = false;
  feeConsentOpen.value = false;
}, { flush: 'sync' });

watch(scope, () => {
  saveError.value = '';
  handoffNotice.value = '';
  saving.value = false;
  pendingAction.value = null;
}, { flush: 'sync' });

const start = useCommand<api.Task>(() => scope.value, () => {
  text.value = '';
  selectedSkills.value = [];
  references.value = [];
  after.value = '-1';
  slashOpen.value = false;
  atOpen.value = false;
  void latest.refetch();
  void history.refetch();
}, commandPolicy, () => emit('unauthorized'));

const stop = useCommand<api.Task>(() => `${props.owner}/${props.exerciseId}/${task.value?.id}`, () => {
  void latest.refetch();
}, commandPolicy, () => emit('unauthorized'));

const handoff = useCommand<api.Steer>(() => `${scope.value}/${task.value?.id}`, receipt => {
  handoffNotice.value = receipt.state === 'NOT_APPLIED' ? '旁支带回未生效。' : '已作为方向调整发送，是否生效以记录为准。';
  void client.invalidateQueries({ queryKey: ['copilot-steers', props.owner, props.exerciseId, receipt.taskId] });
}, commandPolicy, () => emit('unauthorized'));

const canSteer = computed(() => !!task.value && ['QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'WAITING_WRITE', 'WAITING_TEST', 'WAITING_COMMAND', 'WAITING_CHILD'].includes(task.value.state));

function bringBack(conclusion: string) {
  if (props.disabled || saving.value || start.pending.value || start.uncertain.value || handoff.pending.value || handoff.uncertain.value) return;
  if (new globalThis.TextEncoder().encode(conclusion).length > 4096) {
    handoffNotice.value = '带回内容过长，请缩短片段。';
    return;
  }
  if (running.value) {
    if (!task.value || !canSteer.value) {
      handoffNotice.value = '主任务收敛中，当前不可调整方向。';
      return;
    }
    const { owner, exerciseId, credential } = props;
    const id = task.value.id;
    void handoff.submit((key, signal) => api.sendSteer(owner, exerciseId, id, credential, conclusion, key, signal));
  } else {
    const draft = text.value ? `${text.value}\n\n${conclusion}` : conclusion;
    if (new globalThis.TextEncoder().encode(draft).length > 4096) {
      handoffNotice.value = '合并后超过 4 KiB，未覆盖现有草稿。';
      return;
    }
    text.value = draft;
    handoffNotice.value = '已填入草稿，尚未发送。';
    emit('switch-chat');
  }
}

const canSendIdle = computed(() => !props.delegated && !props.disabled && !!props.credential && !running.value && !contextBusy.value
  && !latest.isPending.value && !latest.isError.value && !start.pending.value && !start.uncertain.value && !saving.value && !!text.value.trim());

const canSteerRunning = computed(() => !props.delegated && !props.disabled && !!props.credential && running.value
  && canSteer.value && !!text.value.trim() && !steerRef.value?.pending && !steerRef.value?.uncertain);

const canQueueRunning = computed(() => !props.delegated && !props.disabled && !!props.credential && running.value
  && !!text.value.trim() && !(followupRef.value?.busy) && (followupRef.value?.pending ?? 0) < 32);

const canSubmitPrimary = computed(() => running.value ? canSteerRunning.value : canSendIdle.value);

async function executeSend() {
  if (!canSendIdle.value) return;
  if (new globalThis.TextEncoder().encode(text.value).length > 4096) {
    saveError.value = '请求内容最多 4 KiB，请缩短后发送。';
    return;
  }
  const before = scope.value;
  const prompt = text.value;
  const skillIds = [...selectedSkills.value];
  const referenceSelections = references.value.map(selection);
  const { owner, exerciseId, credential, conversationId } = props;
  saving.value = true;
  saveError.value = '';
  try {
    if (!await props.prepare()) {
      if (scope.value === before) saveError.value = '草稿保存未完成，未创建 Copilot 请求。';
      return;
    }
    if (scope.value !== before || props.disabled) return;
    await start.submit((key, signal) => api.start(owner, exerciseId, credential, prompt, key, signal, skillIds, conversationId, referenceSelections));
  } catch {
    if (scope.value === before) saveError.value = '请求结果待核对，请检查后使用原请求重试。';
  } finally {
    if (scope.value === before) saving.value = false;
  }
}

async function handlePrimarySubmit() {
  if (running.value) {
    if (!canSteerRunning.value || !steerRef.value) return;
    const submitted = text.value;
    const sent = await steerRef.value.sendDirect(submitted);
    if (sent && text.value === submitted) text.value = '';
    return;
  }
  if (!canSendIdle.value) return;
  if (!hasCopilotFeeConsent(props.owner)) {
    pendingAction.value = 'send';
    feeConsentOpen.value = true;
    return;
  }
  await executeSend();
}

function onComposerKeydown(event: globalThis.KeyboardEvent) {
  if (event.key === 'Escape') {
    slashOpen.value = false;
    atOpen.value = false;
    return;
  }
  if (event.key === 'Enter' && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey) {
    if (event.isComposing || event.keyCode === 229) return;
    event.preventDefault();
    void handlePrimarySubmit();
  }
}

function handleFollowupQueue() {
  if (!canQueueRunning.value || !followupRef.value) return;
  if (!hasCopilotFeeConsent(props.owner)) {
    pendingAction.value = 'followup';
    feeConsentOpen.value = true;
    return;
  }
  if (followupRef.value.enqueueDirect(text.value)) text.value = '';
}

async function confirmFeeConsent() {
  grantCopilotFeeConsent(props.owner);
  const action = pendingAction.value;
  feeConsentOpen.value = false;
  pendingAction.value = null;
  if (action === 'followup') {
    if (followupRef.value?.enqueueDirect(text.value)) text.value = '';
  } else if (action === 'send') {
    await executeSend();
  }
}

function stopTask() {
  if (!task.value || !running.value) return;
  const { owner, exerciseId } = props;
  const id = task.value.id;
  void stop.submit((key, signal) => api.stop(owner, exerciseId, id, key, signal));
}

function removeReference(index: number) {
  references.value.splice(index, 1);
}

function removeSkill(skillId: string) {
  selectedSkills.value = selectedSkills.value.filter(id => id !== skillId);
}

function onTextInput() {
  const val = text.value;
  if (val === '/' || val.endsWith('\n/')) {
    slashOpen.value = true;
    atOpen.value = false;
  } else if (val.endsWith('@')) {
    atOpen.value = true;
    slashOpen.value = false;
  } else if (!val) {
    slashOpen.value = false;
    atOpen.value = false;
  }
}

function runSlashCommand(cmd: 'btw' | 'compact' | 'agents') {
  slashOpen.value = false;
  if (text.value.trim() === '/') text.value = '';
  if (cmd === 'btw') emit('switch-btw');
  else if (cmd === 'compact') usageModalOpen.value = true;
  else if (cmd === 'agents') instructionsModalOpen.value = true;
}

function applyQuickPrompt(prompt: string) {
  text.value = prompt;
  void nextTick(() => textareaRef.value?.focus());
}

function referenceLabel(item: ReferencePage): string {
  const tail = item.source.split('/').pop() || item.source;
  if (item.kind === 'CODE' && item.limit > 0) return `${tail} (${item.offset + 1}-${item.offset + item.limit})`;
  return tail;
}

function onWindowPointerDown(event: globalThis.PointerEvent) {
  const target = event.target as globalThis.Node | null;
  if (!target) return;
  if ((slashOpen.value || atOpen.value) && !composerHost.value?.contains(target)) {
    slashOpen.value = false;
    atOpen.value = false;
  }
  if (moreOpen.value && !moreHost.value?.contains(target)) {
    moreOpen.value = false;
  }
}

onMounted(() => globalThis.addEventListener('pointerdown', onWindowPointerDown));
onBeforeUnmount(() => globalThis.removeEventListener('pointerdown', onWindowPointerDown));

watch([() => props.owner, () => props.exerciseId, () => task.value?.id, running], ([owner, exercise, id, active], _old, cleanup) => {
  connected.value = false;
  transientPreview.value = null;
  if (!id || !active) return;
  const stop = api.reconnectTaskStream(
    api.watchTask,
    exercise,
    id,
    () => {
      connected.value = true;
      transientPreview.value = null;
      void client.invalidateQueries({ queryKey: ['copilot', owner, exercise, 'task'] });
    },
    () => {
      connected.value = false;
      transientPreview.value = null;
    },
    (incoming) => {
      transientPreview.value = api.nextTransientPreview(transientPreview.value, incoming, id, running.value);
    },
  );
  cleanup(() => {
    transientPreview.value = null;
    stop();
  });
}, { immediate: true });

const labels: Record<api.Task['state'], string> = {
  QUEUED: '排队中',
  RUNNING: '思考与执行中',
  PAUSED: '已暂停',
  WAITING_APPROVAL: '等待审批',
  WAITING_WRITE: '等待写入确认',
  WAITING_TEST: '运行验证测试中',
  WAITING_COMMAND: '等待命令执行',
  WAITING_CHILD: '等待子任务交接',
  STOPPING: '正在停止',
  STOPPED: '已停止',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  INTERRUPTED: '已中断，不会自动重发',
};

const showTaskBanner = computed(() =>
  latest.isPending.value
  || latest.isError.value
  || running.value
  || !!task.value?.reason
  || task.value?.state === 'PAUSED'
  || task.value?.state === 'INTERRUPTED'
  || !!stop.error.value
  || !!latest.data.value?.textHistory
);
</script>

<template>
  <section
    class="copilot-panel"
    :class="{ 'is-delegated': delegated }"
    :aria-label="delegated ? '委派子任务对话' : 'Copilot 对话'"
  >
    <Teleport
      v-if="!delegated"
      :to="moreTarget"
      :disabled="!moreTarget"
    >
      <div
        ref="moreHost"
        class="more-menu-wrap"
      >
        <Button
          variant="ghost"
          size="icon"
          class="more-trigger-btn"
          aria-label="更多 Copilot 选项"
          :aria-expanded="moreOpen"
          @click="moreOpen = !moreOpen"
        >
          <MoreHorizontal
            :size="15"
            aria-hidden="true"
          />
        </Button>
        <div
          v-if="moreOpen"
          class="more-popover ui-popover"
          role="menu"
          aria-label="Copilot 工具菜单"
        >
          <button
            type="button"
            role="menuitem"
            class="more-item"
            @click="moreOpen = false; approvalModeRef?.openAudit()"
          >
            <History
              :size="13"
              aria-hidden="true"
            />
            <span>审批模式变更记录</span>
          </button>
          <button
            type="button"
            role="menuitem"
            class="more-item"
            @click="moreOpen = false; usageModalOpen = true"
          >
            <Sparkles
              :size="13"
              aria-hidden="true"
            />
            <span>上下文占用与压缩</span>
          </button>
          <button
            type="button"
            role="menuitem"
            class="more-item"
            @click="moreOpen = false; instructionsModalOpen = true"
          >
            <FileCode2
              :size="13"
              aria-hidden="true"
            />
            <span>项目指令 (AGENTS.md)</span>
          </button>
        </div>
      </div>
    </Teleport>

    <div
      v-show="delegated || subview !== 'btw'"
      class="main-chat-shell"
    >
      <div
        v-if="showTaskBanner"
        class="task-status-bar"
      >
        <span
          v-if="latest.isPending.value"
          role="status"
          class="status-muted"
        >同步状态…</span>
        <span
          v-else-if="latest.isError.value"
          role="alert"
          class="status-danger"
        >{{ errorMessage(latest.error.value) }}</span>
        <template v-else-if="task">
          <div class="status-pill-row">
            <span
              class="ui-status-dot"
              :data-tone="running ? 'accent' : task.state === 'FAILED' || task.state === 'INTERRUPTED' ? 'danger' : 'warning'"
            />
            <span
              role="status"
              class="status-label"
            >{{ labels[task.state] }}</span>
            <span
              v-if="running"
              class="status-conn"
            >{{ connected ? '实时' : '轮询中' }}</span>
            <Button
              v-if="running"
              variant="ghost"
              size="small"
              class="stop-task-btn"
              :disabled="stop.pending.value"
              @click="stop.uncertain.value ? stop.retry() : stopTask()"
            >
              <Square
                :size="11"
                aria-hidden="true"
              />
              <span>{{ stop.uncertain.value ? '核对原停止请求' : delegated ? '停止此子任务并交回结果' : '停止任务' }}</span>
            </Button>
          </div>
          <p
            v-if="task.reason"
            class="status-sub-warn"
          >
            {{ errorMessage(new ApiError(409, task.reason)) }}
          </p>
          <p
            v-if="stop.error.value"
            role="alert"
            class="status-sub-danger"
          >
            {{ stop.error.value }}
          </p>
          <p
            v-if="task.state === 'PAUSED'"
            class="status-sub-warn"
          >
            {{ delegated ? '子任务已暂停，可停止交回父任务。' : '请检查模型配置后停止重发。' }}
          </p>
          <p
            v-if="latest.data.value?.textHistory"
            class="status-sub-muted"
          >
            模型切换已将历史作为只读文本证据传入。
          </p>
        </template>
      </div>

      <div
        class="messages-viewport"
        aria-live="polite"
      >
        <div
          v-if="after !== '-1' || history.data.value?.nextCursor"
          class="history-pager"
        >
          <button
            v-if="after === '-1'"
            type="button"
            class="quiet-pager-btn"
            @click="after = '0'"
          >
            查看最早消息
          </button>
          <button
            v-else
            type="button"
            class="quiet-pager-btn"
            @click="after = '-1'; history.refetch()"
          >
            回到最新消息
          </button>
          <button
            v-if="after !== '-1' && history.data.value?.nextCursor"
            type="button"
            class="quiet-pager-btn"
            @click="after = history.data.value.nextCursor"
          >
            加载更多
          </button>
        </div>

        <p
          v-if="history.isPending.value"
          class="stream-note"
        >
          加载对话…
        </p>
        <p
          v-else-if="history.isError.value"
          role="alert"
          class="stream-error"
        >
          {{ errorMessage(history.error.value) }}
        </p>
        <div
          v-else-if="!history.data.value?.messages.length && !running && !preview"
          class="empty-welcome"
        >
          <div class="welcome-icon">
            <Sparkles
              :size="18"
              aria-hidden="true"
            />
          </div>
          <p class="welcome-title">
            需要重构、排查或加测试？
          </p>
          <div
            v-if="!delegated"
            class="welcome-chips"
          >
            <button
              type="button"
              class="welcome-chip"
              @click="applyQuickPrompt('梳理当前代码结构与潜在边界问题')"
            >
              梳理当前代码结构
            </button>
            <button
              type="button"
              class="welcome-chip"
              @click="applyQuickPrompt('补充关键边界单元测试并运行验证')"
            >
              补充关键边界测试
            </button>
          </div>
        </div>

        <article
          v-for="message in history.data.value?.messages"
          :key="message.id"
          class="chat-msg"
          :data-kind="message.kind"
        >
          <header class="chat-msg-head">
            <span class="chat-role">{{ ({ USER: '你', ASSISTANT: 'Copilot', TOOL: '工具' })[message.kind] }}</span>
            <button
              v-if="!delegated"
              type="button"
              class="fork-inline-btn"
              title="从这条消息分支"
              :disabled="disabled || !credential"
              @click="emit('fork', message.id)"
            >
              <GitBranch
                :size="11"
                aria-hidden="true"
              />
              <span>分支</span>
            </button>
          </header>
          <CopilotWebResult
            v-if="message.kind === 'TOOL' && /^web_(search|read) \[/.test(message.text)"
            :text="message.text"
            :scope="`${scope}/${message.id}`"
          />
          <CopilotToolResult
            v-else-if="message.kind === 'TOOL' && isStructuredToolReceipt(message.text)"
            :text="message.text"
          />
          <template v-else-if="message.kind === 'ASSISTANT'">
            <CopilotMessageBody
              v-if="splitAssistantToolCalls(message.text).body || message.thinking"
              :text="splitAssistantToolCalls(message.text).body"
              :thinking="message.thinking"
            />
            <div
              v-if="splitAssistantToolCalls(message.text).calls.length"
              class="assistant-tool-calls"
              aria-label="发起的工具调用"
            >
              <span
                v-for="call in splitAssistantToolCalls(message.text).calls"
                :key="call.callId"
                class="tool-call-chip"
              >
                <code>{{ call.name }}</code>
                <span>{{ call.label }}</span>
              </span>
            </div>
          </template>
          <CopilotMessageBody
            v-else
            :text="message.text"
            :thinking="message.thinking"
          />
        </article>

        <aside
          v-if="preview"
          class="partial-preview"
          aria-label="未确认回复预览"
        >
          <span class="preview-badge">生成中（未确认）</span>
          <p
            v-if="preview.toolName"
            class="preview-tool-progress font-mono-num"
            role="status"
          >
            正在准备工具 <code>{{ preview.toolName === 'unknown' ? '待识别工具' : preview.toolName }}</code>{{ preview.toolArgBytes ? `（${preview.toolArgBytes} B）` : '' }}…
          </p>
          <CopilotMessageBody
            v-if="preview.text || preview.thinking"
            :text="preview.text"
            :thinking="preview.thinking"
          />
          <p
            v-if="preview.truncated || preview.thinkingTruncated"
            class="preview-trunc-note"
          >
            预览已截断（滚动显示最新输出）
          </p>
        </aside>

        <CopilotProposals
          v-if="task"
          :key="owner + task.id"
          :owner="owner"
          :task-id="task.id"
          :credential="credential"
          :disabled="disabled || task.state !== 'WAITING_APPROVAL'"
          @unauthorized="emit('unauthorized')"
          @changed="emit('changed')"
        />
        <CopilotCommands
          v-if="task"
          :key="owner + task.id + ':commands'"
          :owner="owner"
          :exercise-id="exerciseId"
          :task="task"
          :credential="credential"
          :disabled="disabled"
          @unauthorized="emit('unauthorized')"
          @changed="emit('changed')"
        />
        <CopilotTests
          v-if="task"
          :key="owner + task.id + ':tests'"
          :owner="owner"
          :task-id="task.id"
          :running="running"
          @inspect="emit('inspect-test', $event)"
        />
        <CopilotDelegations
          v-if="task && !delegated"
          :owner="owner"
          :exercise-id="exerciseId"
          :parent-task-id="task.id"
          @unauthorized="emit('unauthorized')"
        >
          <template #default="childScope">
            <CopilotPanel
              :key="childScope.conversationId"
              :owner="owner"
              :exercise-id="exerciseId"
              :conversation-id="childScope.conversationId"
              delegated
              :credential="credential"
              :disabled="disabled"
              :prepare="prepare"
              @unauthorized="emit('unauthorized')"
              @changed="emit('changed')"
              @inspect-test="emit('inspect-test', $event)"
            />
          </template>
        </CopilotDelegations>
      </div>

      <div
        v-if="!delegated"
        ref="composerHost"
        class="composer-dock"
      >
        <div
          v-show="slashOpen"
          class="floating-popover ui-popover"
          role="dialog"
          aria-label="指令与 Skill"
        >
          <div class="slash-shortcuts">
            <button
              type="button"
              class="slash-cmd-row"
              @click="runSlashCommand('btw')"
            >
              <code>/btw</code>
              <span>切换 BTW 旁支</span>
            </button>
            <button
              type="button"
              class="slash-cmd-row"
              @click="runSlashCommand('compact')"
            >
              <code>/compact</code>
              <span>上下文压缩与用量</span>
            </button>
            <button
              type="button"
              class="slash-cmd-row"
              @click="runSlashCommand('agents')"
            >
              <code>/agents</code>
              <span>查看 AGENTS.md</span>
            </button>
          </div>
          <CopilotSkills
            v-model="selectedSkills"
            :owner="owner"
            :task-id="task?.id ?? null"
            :disabled="disabled || running || saving || start.pending.value || start.uncertain.value"
            @unauthorized="emit('unauthorized')"
          />
        </div>

        <div
          v-show="atOpen"
          class="floating-popover ui-popover"
          role="dialog"
          aria-label="引用上下文"
        >
          <CopilotReferences
            v-model="references"
            :owner="owner"
            :exercise-id="exerciseId"
            :conversation-id="conversationId ?? exerciseId"
            :paths="paths"
            :disabled="disabled || running || saving || start.pending.value || start.uncertain.value"
            @attached="atOpen = false"
            @unauthorized="emit('unauthorized')"
          />
        </div>

        <CopilotSteers
          v-if="task"
          :key="owner + task.id + ':steers'"
          ref="steerRef"
          slim
          :owner="owner"
          :exercise-id="exerciseId"
          :task-id="task.id"
          :state="task.state"
          :credential="credential"
          :disabled="disabled"
          @unauthorized="emit('unauthorized')"
        />
        <CopilotFollowups
          ref="followupRef"
          slim
          :owner="owner"
          :exercise-id="exerciseId"
          :conversation-id="conversationId ?? exerciseId"
          :credential="credential"
          :disabled="disabled"
          :running="running"
          @unauthorized="emit('unauthorized')"
          @changed="latest.refetch()"
        />

        <div
          v-if="saveError || start.error.value || start.uncertain.value || handoffNotice || handoff.error.value || handoff.uncertain.value"
          class="composer-alerts"
        >
          <p
            v-if="saveError"
            role="alert"
            class="alert-danger"
          >
            {{ saveError }}
          </p>
          <p
            v-if="start.error.value"
            role="alert"
            class="alert-danger"
          >
            {{ start.error.value }}
          </p>
          <div
            v-if="start.uncertain.value"
            class="alert-warn-row"
          >
            <span>受理结果未知，仅可核对原请求</span>
            <Button
              size="small"
              :disabled="start.pending.value || disabled || !credential"
              @click="start.retry"
            >
              核对原 Copilot 请求
            </Button>
          </div>
          <p
            v-if="handoffNotice"
            role="status"
            class="alert-status"
          >
            {{ handoffNotice }}
          </p>
          <p
            v-if="handoff.error.value"
            role="alert"
            class="alert-danger"
          >
            {{ handoff.error.value }}
          </p>
          <Button
            v-if="handoff.uncertain.value"
            size="small"
            :disabled="handoff.pending.value"
            @click="handoff.retry()"
          >
            核对原旁支带回 steer
          </Button>
        </div>

        <div class="composer-capsule ui-capsule">
          <div
            v-if="selectedSkills.length || references.length"
            class="composer-chips"
          >
            <span
              v-for="skillId in selectedSkills"
              :key="`skill:${skillId}`"
              class="ctx-chip"
            >
              <span>Skill: {{ skillId }}</span>
              <button
                type="button"
                class="chip-remove"
                :aria-label="`移除 Skill ${skillId}`"
                @click="removeSkill(skillId)"
              >
                <X
                  :size="11"
                  aria-hidden="true"
                />
              </button>
            </span>
            <span
              v-for="(refItem, idx) in references"
              :key="`${refItem.source}:${refItem.offset}`"
              class="ctx-chip font-mono-num"
            >
              <span>{{ referenceLabel(refItem) }}</span>
              <button
                type="button"
                class="chip-remove"
                :aria-label="`移除引用 ${refItem.source}`"
                @click="removeReference(idx)"
              >
                <X
                  :size="11"
                  aria-hidden="true"
                />
              </button>
            </span>
            <span
              v-if="references.length"
              class="ref-quota font-mono-num"
            >{{ references.length }}/4</span>
          </div>

          <label
            :for="`copilot-text-${exerciseId}`"
            class="sr-only"
          >给 Copilot 的请求</label>
          <textarea
            :id="`copilot-text-${exerciseId}`"
            ref="textareaRef"
            v-model="text"
            class="composer-textarea"
            rows="2"
            :placeholder="running ? '输入方向调整或排队消息，Enter 发送…' : '输入指令或问题，输 / 选 Skill，输 @ 引用文件…'"
            :disabled="saving || start.pending.value || start.uncertain.value"
            @input="onTextInput"
            @keydown="onComposerKeydown"
          />

          <div class="composer-toolbar">
            <div class="toolbar-left">
              <Tooltip text="指令与 Skill ( / )">
                <Button
                  variant="ghost"
                  size="icon"
                  class="tool-icon-btn"
                  aria-label="指令与 Skill"
                  :aria-expanded="slashOpen"
                  @click="slashOpen = !slashOpen; atOpen = false"
                >
                  <Slash
                    :size="13"
                    aria-hidden="true"
                  />
                </Button>
              </Tooltip>
              <Tooltip text="添加上下文引用 ( @ )">
                <Button
                  variant="ghost"
                  size="icon"
                  class="tool-icon-btn"
                  aria-label="添加上下文引用"
                  :aria-expanded="atOpen"
                  @click="atOpen = !atOpen; slashOpen = false"
                >
                  <AtSign
                    :size="13"
                    aria-hidden="true"
                  />
                </Button>
              </Tooltip>
              <CopilotApprovalMode
                ref="approvalModeRef"
                compact
                :owner="owner"
                :exercise-id="exerciseId"
                :credential="credential"
                :disabled="disabled"
                @unauthorized="emit('unauthorized')"
              />
            </div>

            <div class="toolbar-right">
              <CopilotUsage
                compact
                :conversation-id="conversationId"
                :owner="owner"
                :exercise-id="exerciseId"
                @open-detail="usageModalOpen = true"
                @unauthorized="emit('unauthorized')"
              />
              <Tooltip
                v-if="running"
                text="加入后续队列（不打断当前执行）"
              >
                <Button
                  variant="ghost"
                  size="icon"
                  class="tool-icon-btn"
                  aria-label="加入后续队列"
                  :disabled="!canQueueRunning"
                  @click="handleFollowupQueue"
                >
                  <ListOrdered
                    :size="13"
                    aria-hidden="true"
                  />
                </Button>
              </Tooltip>
              <Button
                variant="primary"
                size="icon"
                class="send-circle-btn"
                :aria-label="running ? '发送方向调整' : '发送消息'"
                :disabled="!canSubmitPrimary"
                :loading="saving || start.pending.value || steerRef?.pending"
                @click="handlePrimarySubmit"
              >
                <ArrowUp
                  :size="14"
                  aria-hidden="true"
                />
              </Button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div
      v-if="!delegated"
      v-show="subview === 'btw'"
      class="btw-view-wrap"
    >
      <CopilotBtw
        :owner="owner"
        :exercise-id="exerciseId"
        :parent-conversation-id="conversationId ?? exerciseId"
        :credential="credential"
        :main-running="running"
        :disabled="disabled || saving || start.pending.value || start.uncertain.value"
        :handoff-disabled="handoff.pending.value || handoff.uncertain.value || (running && !canSteer)"
        @unauthorized="emit('unauthorized')"
        @bringback="bringBack"
      />
    </div>

    <Dialog
      v-if="!delegated"
      v-model:open="usageModalOpen"
      title="上下文与用量"
      description="查看最近上下文占用，或在需要时执行压缩。"
    >
      <div class="modal-stack">
        <CopilotUsage
          :conversation-id="conversationId"
          :owner="owner"
          :exercise-id="exerciseId"
          @unauthorized="emit('unauthorized')"
        />
        <CopilotContext
          :conversation-id="conversationId"
          :owner="owner"
          :exercise-id="exerciseId"
          :credential="credential"
          :disabled="disabled"
          @busy="childContextBusy = $event"
          @unauthorized="emit('unauthorized')"
        />
      </div>
    </Dialog>

    <Dialog
      v-if="!delegated"
      v-model:open="instructionsModalOpen"
      title="项目指令 (AGENTS.md)"
    >
      <CopilotProjectInstructions
        :owner="owner"
        :exercise-id="exerciseId"
        :task-id="task?.id ?? null"
        :running="running"
        @unauthorized="emit('unauthorized')"
      />
    </Dialog>

    <Dialog
      v-if="!delegated"
      v-model:open="feeConsentOpen"
      title="确认调用付费模型"
      description="Copilot 将调用您配置的模型接口并可能产生费用。确认后当前会话内免重复提示。"
    >
      <template #footer>
        <Button
          variant="ghost"
          size="small"
          @click="feeConsentOpen = false; pendingAction = null"
        >
          取消
        </Button>
        <Button
          variant="primary"
          size="small"
          @click="confirmFeeConsent"
        >
          确认并发送
        </Button>
      </template>
    </Dialog>
  </section>
</template>

<style scoped>
.copilot-panel {
  flex: 1 1 0%;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--oc-bg-subtle);
}

.copilot-panel.is-delegated {
  flex: none;
  border: 1px solid var(--oc-border);
  border-radius: 8px;
  padding: 8px;
  margin-top: 6px;
}

.main-chat-shell,
.btw-view-wrap {
  flex: 1 1 0%;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.more-menu-wrap {
  position: relative;
  display: inline-flex;
  align-items: center;
}

.more-trigger-btn {
  width: 26px;
  height: 26px;
  min-height: 26px;
  padding: 0;
}

.more-popover {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 4px;
  min-width: 184px;
  display: grid;
  gap: 2px;
}

.more-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 9px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--oc-fg);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}

.more-item:hover {
  background: var(--oc-selected);
}

.task-status-bar {
  flex-shrink: 0;
  padding: 6px 12px;
  border-bottom: 1px solid var(--oc-border);
  background: rgba(255, 255, 255, 0.02);
  display: grid;
  gap: 3px;
  font-size: 12px;
}

.status-pill-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.status-label {
  font-weight: 550;
  color: var(--oc-fg);
}

.status-conn {
  font-size: 11px;
  color: var(--oc-fg-dim);
}

.stop-task-btn {
  margin-left: auto;
  min-height: 22px;
  padding: 1px 7px;
  font-size: 11px;
  color: var(--oc-danger);
}

.status-muted,
.status-sub-muted {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-fg-muted);
}

.status-sub-warn {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-warning);
}

.status-danger,
.status-sub-danger {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-danger);
}

.messages-viewport {
  flex: 1 1 0%;
  min-height: 0;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.history-pager {
  display: flex;
  justify-content: center;
  gap: 12px;
}

.quiet-pager-btn {
  border: 0;
  background: transparent;
  color: var(--oc-fg-dim);
  font-size: 11.5px;
  cursor: pointer;
  padding: 2px 6px;
}

.quiet-pager-btn:hover {
  color: var(--oc-fg);
}

.empty-welcome {
  margin: auto 0;
  padding: 24px 12px;
  display: grid;
  justify-items: center;
  gap: 10px;
  text-align: center;
}

.welcome-icon {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  color: var(--oc-accent);
}

.welcome-title {
  margin: 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--oc-fg-muted);
}

.welcome-chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 6px;
}

.welcome-chip {
  padding: 5px 10px;
  border-radius: 999px;
  border: 1px solid var(--oc-border);
  background: var(--oc-surface);
  color: var(--oc-fg-muted);
  font-size: 12px;
  cursor: pointer;
}

.welcome-chip:hover {
  color: var(--oc-fg);
  border-color: var(--oc-border-strong);
  background: var(--oc-surface-2);
}

.stream-note {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-dim);
}

.stream-error {
  margin: 0;
  font-size: 12px;
  color: var(--oc-danger);
}

.chat-msg {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.chat-msg[data-kind="USER"] {
  padding: 9px 11px;
  border-radius: 10px;
  background: var(--oc-surface-2);
}

.chat-msg[data-kind="TOOL"] {
  padding: 6px 10px;
  border-left: 2px solid var(--oc-border-strong);
}

.chat-msg-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.chat-role {
  font-size: 11px;
  font-weight: 600;
  color: var(--oc-fg-dim);
}

.fork-inline-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: 0;
  background: transparent;
  color: var(--oc-fg-dim);
  font-size: 11px;
  cursor: pointer;
  opacity: 0;
  transition: opacity 120ms var(--oc-ease), color 120ms var(--oc-ease);
}

.chat-msg:hover .fork-inline-btn,
.fork-inline-btn:focus-visible {
  opacity: 1;
}

.fork-inline-btn:hover:not(:disabled) {
  color: var(--oc-fg);
}

.assistant-tool-calls {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tool-call-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 7px;
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.04);
  font-size: 11.5px;
  color: var(--oc-fg-muted);
}

.partial-preview {
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(110, 231, 183, 0.04);
  border: 1px solid rgba(110, 231, 183, 0.2);
  display: grid;
  gap: 6px;
}

.preview-badge {
  font-size: 11px;
  color: var(--oc-accent);
}

.preview-trunc-note {
  margin: 0;
  font-size: 11px;
  color: var(--oc-fg-dim);
}

.composer-dock {
  flex-shrink: 0;
  position: relative;
  padding: 6px 10px 10px;
  display: grid;
  gap: 6px;
}

.floating-popover {
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: 100%;
  margin-bottom: 6px;
  max-height: min(360px, 55dvh);
  overflow-y: auto;
  display: grid;
  gap: 8px;
}

.slash-shortcuts {
  display: grid;
  gap: 2px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--oc-border);
}

.slash-cmd-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--oc-fg);
  font-size: 12px;
  cursor: pointer;
}

.slash-cmd-row:hover {
  background: var(--oc-selected);
}

.slash-cmd-row span {
  color: var(--oc-fg-muted);
  font-size: 11.5px;
}

.composer-alerts {
  display: grid;
  gap: 4px;
  font-size: 12px;
}

.alert-danger {
  margin: 0;
  color: var(--oc-danger);
}

.alert-status {
  margin: 0;
  color: var(--oc-fg-muted);
}

.alert-warn-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--oc-warning);
}

.composer-capsule {
  padding: 6px 8px;
  display: grid;
  gap: 4px;
}

.composer-chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
  padding-bottom: 4px;
}

.ctx-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 190px;
  padding: 2px 6px;
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--oc-fg);
  font-size: 11.5px;
}

.ctx-chip span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chip-remove {
  border: 0;
  background: transparent;
  color: var(--oc-fg-dim);
  padding: 0;
  display: grid;
  place-items: center;
  cursor: pointer;
}

.chip-remove:hover {
  color: var(--oc-fg);
}

.ref-quota {
  font-size: 11px;
  color: var(--oc-fg-dim);
  margin-left: auto;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
}

.composer-textarea {
  width: 100%;
  min-height: 44px;
  max-height: 140px;
  padding: 2px 4px;
  border: 0 !important;
  background: transparent !important;
  color: var(--oc-fg);
  font-size: 13px;
  line-height: 1.5;
  resize: none;
  box-shadow: none !important;
}

.composer-textarea:focus {
  outline: none;
}

.composer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.toolbar-left,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 4px;
}

.tool-icon-btn {
  width: 26px;
  height: 26px;
  min-height: 26px;
  padding: 0;
  color: var(--oc-fg-muted);
}

.send-circle-btn {
  width: 26px;
  height: 26px;
  min-height: 26px;
  padding: 0;
  border-radius: 999px;
}

.modal-stack {
  display: grid;
  gap: 14px;
}
</style>
