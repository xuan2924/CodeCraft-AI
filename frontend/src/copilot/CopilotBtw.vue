<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { useCommand } from '../api/useCommand';
import Dialog from '../components/ui/Dialog.vue';
import Tooltip from '../components/ui/Tooltip.vue';
import * as api from './api';
import * as btwApi from './btwApi';
import CopilotMessageBody from './CopilotMessageBody.vue';
import CopilotToolResult from './CopilotToolResult.vue';
import CopilotUsage from './CopilotUsage.vue';
import CopilotWebResult from './CopilotWebResult.vue';
import { commandPolicy, errorMessage } from './errors';
import { grantCopilotFeeConsent, hasCopilotFeeConsent } from './feeConsent';
import { isStructuredToolReceipt } from './toolPresentation';

const props = defineProps<{
  owner: string;
  exerciseId: string;
  parentConversationId: string;
  credential: string;
  disabled: boolean;
  mainRunning: boolean;
  handoffDisabled?: boolean;
}>();

const emit = defineEmits<{ unauthorized: []; bringback: [text: string] }>();

const client = useQueryClient();
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.parentConversationId}/${props.credential}`);
const text = ref('');
const validation = ref('');
const after = ref('-1');
const handoff = ref('');
const sourceMessage = ref('');
const feeDialogOpen = ref(false);
const usageDialogOpen = ref(false);

const side = useQuery({
  queryKey: computed(() => ['copilot', props.owner, props.exerciseId, 'btw', props.parentConversationId]),
  queryFn: ({ signal }) => btwApi.find(props.owner, props.exerciseId, props.parentConversationId, signal),
  retry: false,
  gcTime: 0,
  refetchInterval: 2000,
});

const conversation = computed(() => side.data.value?.conversationId);
const taskKey = computed(() => ['copilot', props.owner, props.exerciseId, 'task', conversation.value ?? `btw-uncreated:${props.parentConversationId}`]);

const latest = useQuery({
  queryKey: taskKey,
  enabled: computed(() => !!conversation.value),
  queryFn: ({ signal }) => (conversation.value ? api.latest(props.owner, props.exerciseId, signal, conversation.value) : Promise.resolve(null)),
  retry: false,
  gcTime: 0,
  refetchInterval: 2000,
});

const history = useQuery({
  queryKey: computed(() => ['copilot', props.owner, props.exerciseId, 'messages', conversation.value, after.value]),
  enabled: computed(() => !!conversation.value),
  queryFn: ({ signal }) => (conversation.value ? api.messages(props.owner, props.exerciseId, after.value, signal, conversation.value) : Promise.resolve({ messages: [], nextCursor: null })),
  retry: false,
  gcTime: 0,
});

const task = computed(() => latest.data.value?.task);
const running = computed(() => api.active(task.value));
const transientPreview = ref<api.PreviewEvent | null>(null);
const preview = computed(() => api.resolveDisplayPreview(transientPreview.value, latest.data.value, running.value));

watch(
  scope,
  () => {
    text.value = '';
    validation.value = '';
    handoff.value = '';
    sourceMessage.value = '';
    after.value = '-1';
    transientPreview.value = null;
    feeDialogOpen.value = false;
    usageDialogOpen.value = false;
  },
  { flush: 'sync' },
);

watch([side.error, latest.error, history.error], errors => {
  if (errors.some(commandPolicy.identityFailure)) emit('unauthorized');
});

watch(
  () => latest.data.value?.lastSequence,
  () => {
    transientPreview.value = null;
    if (conversation.value) void history.refetch();
  },
);

const send = useCommand<api.Task>(
  () => scope.value,
  () => {
    text.value = '';
    void side.refetch();
    if (conversation.value) {
      void latest.refetch();
      void history.refetch();
    }
  },
  commandPolicy,
  () => emit('unauthorized'),
);

const stop = useCommand<api.Task>(
  () => `${scope.value}/${task.value?.id}`,
  () => {
    void latest.refetch();
  },
  commandPolicy,
  () => emit('unauthorized'),
);

const busy = computed(() => send.pending.value || send.uncertain.value);
const queryErrors = computed(() => [side.error.value, latest.error.value, history.error.value].filter(Boolean));
const textBytes = computed(() => new globalThis.TextEncoder().encode(text.value).length);
const canSend = computed(
  () =>
    !props.disabled &&
    !!props.credential &&
    !busy.value &&
    !running.value &&
    !!text.value.trim() &&
    !side.isPending.value &&
    !side.isError.value &&
    (!conversation.value || (!latest.isPending.value && !latest.isError.value)),
);

function ask() {
  if (!canSend.value) return;
  if (textBytes.value > 4096) {
    validation.value = '旁支问题最多 4 KiB。';
    return;
  }
  const { owner, exerciseId, parentConversationId, credential } = props;
  const prompt = text.value;
  const expected = conversation.value;
  validation.value = '';
  void send.submit((key, signal) => btwApi.start(owner, exerciseId, parentConversationId, credential, prompt, key, signal, expected));
}

function requestAsk() {
  if (!canSend.value) return;
  if (textBytes.value > 4096) {
    validation.value = '旁支问题最多 4 KiB。';
    return;
  }
  if (!hasCopilotFeeConsent(props.owner)) {
    feeDialogOpen.value = true;
    return;
  }
  ask();
}

function confirmFeeAndAsk() {
  grantCopilotFeeConsent(props.owner);
  feeDialogOpen.value = false;
  ask();
}

function stopSide() {
  if (!task.value || !running.value) return;
  const { owner, exerciseId } = props;
  const id = task.value.id;
  void stop.submit((key, signal) => api.stop(owner, exerciseId, id, key, signal));
}

function onComposerKeydown(event: globalThis.KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    requestAsk();
  }
}

watch([() => props.owner, () => props.exerciseId, conversation, () => task.value?.id, running], ([owner, exercise, sideId, id, active], _old, cleanup) => {
  transientPreview.value = null;
  if (!sideId || !id || !active) return;
  const controller = new globalThis.AbortController();
  let close = () => {};
  let disposed = false;
  let timer: ReturnType<typeof globalThis.setTimeout> | undefined;
  function refresh() {
    if (!disposed) {
      transientPreview.value = null;
      void client.invalidateQueries({ queryKey: ['copilot', owner, exercise, 'task', sideId] });
    }
  }
  function connect() {
    if (disposed) return;
    close();
    close = api.watchTask(
      exercise,
      id!,
      refresh,
      () => {
        if (disposed) return;
        refresh();
        close();
        if (timer === undefined) {
          timer = globalThis.setTimeout(() => {
            timer = undefined;
            connect();
          }, 2000);
        }
      },
      controller.signal,
      incoming => {
        if (disposed) return;
        transientPreview.value = api.nextTransientPreview(transientPreview.value, incoming, id, running.value);
      },
    );
  }
  connect();
  cleanup(() => {
    disposed = true;
    transientPreview.value = null;
    controller.abort();
    close();
    if (timer !== undefined) globalThis.clearTimeout(timer);
  });
});

function choose(message: api.Message) {
  let excerpt = '';
  let bytes = 0;
  const encoder = new globalThis.TextEncoder();
  for (const point of message.text) {
    const size = encoder.encode(point).length;
    if (bytes + size > 3800) break;
    excerpt += point;
    bytes += size;
  }
  sourceMessage.value = message.id;
  handoff.value = excerpt;
  validation.value = excerpt.length < message.text.length ? '带回预览仅选取前 3800 字节，可编辑整理；原旁支回复仍保留。' : '';
}

const bringbackText = computed(() => `[用户选取的 BTW 结论，来源消息 ${sourceMessage.value}]\n${handoff.value}`);
const canBringback = computed(
  () => !props.disabled && !props.handoffDisabled && !!sourceMessage.value && !!handoff.value.trim() && new globalThis.TextEncoder().encode(bringbackText.value).length <= 4096,
);
const messageList = computed(() => history.data.value?.messages ?? []);
</script>

<template>
  <section
    class="btw-shell"
    aria-label="BTW 旁支"
  >
    <div
      v-if="queryErrors.length || task?.reason === 'MODEL_RESULT_UNKNOWN'"
      class="btw-banners"
    >
      <div
        v-for="(failure, index) in queryErrors"
        :key="index"
        class="btw-banner btw-banner-error"
        role="alert"
      >
        <span>{{ errorMessage(failure) }}</span>
        <button
          type="button"
          class="btw-inline-link"
          @click="side.refetch(); if (conversation) { latest.refetch(); history.refetch(); }"
        >
          重试
        </button>
      </div>
      <div
        v-if="task?.reason === 'MODEL_RESULT_UNKNOWN'"
        class="btw-banner btw-banner-warn"
        role="status"
      >
        <span>供应商可能已计费；旁支输出未确认，不会自动重发。</span>
      </div>
    </div>

    <div class="btw-stream">
      <div
        v-if="conversation && (messageList.length > 0 || after !== '-1')"
        class="btw-history-nav"
      >
        <button
          v-if="after === '-1'"
          type="button"
          class="btw-quiet-link"
          @click="after = '0'"
        >
          查看早期旁支记录
        </button>
        <template v-else>
          <button
            type="button"
            class="btw-quiet-link"
            @click="after = '-1'; history.refetch()"
          >
            回到最近消息
          </button>
          <button
            v-if="history.data.value?.nextCursor"
            type="button"
            class="btw-quiet-link"
            @click="after = history.data.value.nextCursor"
          >
            继续加载
          </button>
        </template>
      </div>

      <div
        v-if="!messageList.length && !preview"
        class="btw-empty"
      >
        <p class="btw-empty-title">
          旁支讨论
        </p>
        <p class="btw-empty-sub">
          不打断主线任务，支持只读检索、联网查证，可将整理后的结论带回主线。
        </p>
      </div>

      <article
        v-for="message in messageList"
        :key="message.id"
        class="btw-message"
        :class="`is-${message.kind.toLowerCase()}`"
      >
        <div
          v-if="message.kind === 'USER'"
          class="btw-user-bubble"
        >
          <CopilotMessageBody
            :text="message.text"
            :thinking="message.thinking"
          />
        </div>
        <div
          v-else-if="message.kind === 'TOOL'"
          class="btw-tool-block"
        >
          <CopilotWebResult
            v-if="/^web_(search|read) \[/.test(message.text)"
            :text="message.text"
            :scope="`${scope}/${message.id}`"
          />
          <CopilotToolResult
            v-else-if="isStructuredToolReceipt(message.text)"
            :text="message.text"
          />
          <CopilotMessageBody
            v-else
            :text="message.text"
            :thinking="message.thinking"
          />
        </div>
        <div
          v-else
          class="btw-assistant-block"
        >
          <CopilotMessageBody
            :text="message.text"
            :thinking="message.thinking"
          />
          <div
            v-if="message.text"
            class="btw-message-actions"
          >
            <button
              type="button"
              class="btw-action-chip"
              :disabled="disabled"
              @click="choose(message)"
            >
              选择此回复并预览带回
            </button>
          </div>
        </div>
      </article>

      <aside
        v-if="preview"
        class="btw-preview"
        aria-label="旁支未确认流式回复"
      >
        <div class="btw-preview-head">
          <span class="btw-pulse" />
          <span>旁支输出中…</span>
          <span
            v-if="preview.truncated || preview.thinkingTruncated"
            class="btw-preview-hint"
          >
            预览已截断
          </span>
        </div>
        <CopilotMessageBody
          :text="preview.text"
          :thinking="preview.thinking"
        />
      </aside>
    </div>

    <div
      v-if="sourceMessage"
      class="btw-bringback-sheet"
    >
      <div class="btw-bringback-head">
        <span>带回结论预览</span>
        <button
          type="button"
          class="btw-sheet-close"
          aria-label="取消带回"
          @click="sourceMessage = ''; handoff = ''"
        >
          ×
        </button>
      </div>
      <textarea
        v-model="handoff"
        class="btw-bringback-input"
        aria-label="带回内容预览"
        :disabled="disabled"
      />
      <p
        v-if="handoffDisabled"
        class="btw-inline-warn"
        role="alert"
      >
        主线暂不能接收带回内容，请等待任务收敛或核对原请求。
      </p>
      <p
        v-else-if="!canBringback && !disabled"
        class="btw-inline-warn"
        role="alert"
      >
        带回内容须非空且含来源标记最多 4 KiB。
      </p>
      <div class="btw-bringback-foot">
        <button
          type="button"
          class="btw-bringback-btn"
          :disabled="!canBringback"
          @click="emit('bringback', bringbackText)"
        >
          {{ mainRunning ? '将预览结论作为 steer 发送到主任务' : '将预览结论填入主请求（尚未发送）' }}
        </button>
      </div>
    </div>

    <div class="btw-composer-wrap">
      <div
        v-if="send.pending.value || stop.pending.value || send.error.value || stop.error.value || send.uncertain.value || stop.uncertain.value || validation"
        class="btw-composer-alerts"
      >
        <span
          v-if="send.pending.value || stop.pending.value"
          class="btw-status-msg"
          role="status"
        >
          正在提交旁支操作…
        </span>
        <span
          v-if="send.error.value"
          class="btw-alert-msg"
          role="alert"
        >
          {{ send.error.value }}
        </span>
        <span
          v-if="stop.error.value"
          class="btw-alert-msg"
          role="alert"
        >
          {{ stop.error.value }}
        </span>
        <span
          v-if="validation"
          class="btw-alert-msg"
          role="alert"
        >
          {{ validation }}
        </span>
        <button
          v-if="send.uncertain.value"
          type="button"
          class="btw-retry-chip"
          :disabled="send.pending.value"
          @click="send.retry()"
        >
          核对原旁支请求（不换键）
        </button>
        <button
          v-if="stop.uncertain.value"
          type="button"
          class="btw-retry-chip"
          :disabled="stop.pending.value"
          @click="stop.retry()"
        >
          核对原旁支停止
        </button>
      </div>

      <div class="btw-capsule">
        <textarea
          v-model="text"
          class="btw-textarea"
          aria-label="旁支问题"
          placeholder="向旁支提问（只读分析，Enter 发送）"
          :disabled="disabled || busy"
          @keydown="onComposerKeydown"
        />
        <div class="btw-capsule-bar">
          <div class="btw-bar-left">
            <CopilotUsage
              v-if="conversation"
              :owner="owner"
              :exercise-id="exerciseId"
              :conversation-id="conversation"
              compact
              @unauthorized="emit('unauthorized')"
              @open-detail="usageDialogOpen = true"
            />
            <span
              v-if="task && running"
              class="btw-state-pill"
            >
              旁支运行中
            </span>
            <Tooltip content="旁支使用已接入模型并独立于主线运行，仅支持只读检索与分析，可自选结论带回主线。">
              <button
                type="button"
                class="btw-help-btn"
                aria-label="旁支说明"
              >
                ?
              </button>
            </Tooltip>
          </div>
          <div class="btw-bar-right">
            <button
              v-if="running"
              type="button"
              class="btw-stop-btn"
              :disabled="stop.pending.value || stop.uncertain.value"
              @click="stopSide"
            >
              仅停止旁支
            </button>
            <button
              type="button"
              class="btw-send-btn"
              aria-label="发送旁支问题"
              :disabled="!canSend"
              @click="requestAsk"
            >
              ↑
            </button>
          </div>
        </div>
      </div>
    </div>

    <Dialog
      v-model:open="feeDialogOpen"
      title="确认启动旁支请求"
      description="旁支对话及主线摘要会调用你配置的模型并可能产生费用；确认后当前账号在本地不再重复弹窗。"
    >
      <template #footer>
        <button
          type="button"
          class="btw-dialog-cancel"
          @click="feeDialogOpen = false"
        >
          取消
        </button>
        <button
          type="button"
          class="btw-dialog-confirm"
          @click="confirmFeeAndAsk"
        >
          确认并发送
        </button>
      </template>
    </Dialog>

    <Dialog
      v-if="conversation"
      v-model:open="usageDialogOpen"
      title="旁支模型用量"
    >
      <CopilotUsage
        :owner="owner"
        :exercise-id="exerciseId"
        :conversation-id="conversation"
        @unauthorized="emit('unauthorized')"
      />
    </Dialog>
  </section>
</template>

<style scoped>
.btw-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  min-width: 0;
  background: var(--oc-surface);
}
.btw-banners {
  flex: 0 0 auto;
  display: grid;
  gap: 6px;
  padding: 8px 12px 0;
}
.btw-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 12px;
}
.btw-banner-error {
  background: rgba(248, 113, 113, 0.1);
  color: var(--oc-danger);
}
.btw-banner-warn {
  background: rgba(251, 191, 36, 0.1);
  color: #fbbf24;
}
.btw-inline-link,
.btw-quiet-link {
  border: none;
  background: transparent;
  padding: 2px 6px;
  color: var(--oc-fg-muted);
  font-size: 11.5px;
  cursor: pointer;
}
.btw-inline-link {
  color: inherit;
  text-decoration: underline;
}
.btw-quiet-link:hover {
  color: var(--oc-fg);
}
.btw-stream {
  flex: 1 1 0;
  min-height: 0;
  min-width: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.btw-history-nav {
  display: flex;
  justify-content: center;
  gap: 10px;
}
.btw-empty {
  margin: auto 0;
  padding: 24px 12px;
  text-align: center;
  display: grid;
  gap: 6px;
}
.btw-empty-title {
  margin: 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--oc-fg);
}
.btw-empty-sub {
  margin: 0 auto;
  max-width: 28ch;
  font-size: 12px;
  line-height: 1.5;
  color: var(--oc-fg-muted);
}
.btw-message {
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.btw-message.is-user {
  align-items: flex-end;
}
.btw-user-bubble {
  max-width: 88%;
  padding: 8px 12px;
  border-radius: 12px;
  background: var(--oc-selected);
  color: var(--oc-fg);
}
.btw-assistant-block {
  min-width: 0;
  display: grid;
  gap: 6px;
}
.btw-tool-block {
  min-width: 0;
  padding: 6px 10px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--oc-hairline);
}
.btw-message-actions {
  display: flex;
  justify-content: flex-start;
}
.btw-action-chip {
  border: 1px solid var(--oc-hairline);
  background: transparent;
  color: var(--oc-fg-muted);
  border-radius: 6px;
  padding: 3px 9px;
  font-size: 11.5px;
  cursor: pointer;
}
.btw-action-chip:hover:not(:disabled) {
  color: var(--oc-fg);
  background: var(--oc-hover);
}
.btw-preview {
  min-width: 0;
  padding: 8px 10px;
  border-left: 2px solid var(--oc-accent);
  background: rgba(255, 255, 255, 0.02);
  display: grid;
  gap: 6px;
}
.btw-preview-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--oc-fg-muted);
}
.btw-pulse {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--oc-accent);
}
.btw-preview-hint {
  margin-left: auto;
  color: #fbbf24;
}
.btw-bringback-sheet {
  flex: 0 0 auto;
  margin: 0 12px 8px;
  padding: 10px;
  border-radius: 10px;
  border: 1px solid var(--oc-border);
  background: var(--oc-bg-elevated);
  display: grid;
  gap: 8px;
}
.btw-bringback-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 500;
  color: var(--oc-fg);
}
.btw-sheet-close {
  border: none;
  background: transparent;
  color: var(--oc-fg-muted);
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
}
.btw-bringback-input {
  width: 100%;
  min-height: 68px;
  resize: vertical;
  border: 1px solid var(--oc-border-input);
  border-radius: 6px;
  background: var(--oc-surface);
  color: var(--oc-fg);
  font: inherit;
  font-size: 12px;
  padding: 7px 9px;
}
.btw-inline-warn {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-danger);
}
.btw-bringback-foot {
  display: flex;
  justify-content: flex-end;
}
.btw-bringback-btn {
  border: none;
  border-radius: 6px;
  background: var(--oc-primary);
  color: var(--oc-primary-fg);
  padding: 5px 11px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
}
.btw-bringback-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.btw-composer-wrap {
  flex: 0 0 auto;
  padding: 8px 12px 12px;
  display: grid;
  gap: 6px;
}
.btw-composer-alerts {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
}
.btw-status-msg {
  color: var(--oc-fg-muted);
}
.btw-alert-msg {
  color: var(--oc-danger);
}
.btw-retry-chip {
  border: 1px solid var(--oc-border);
  background: var(--oc-bg-elevated);
  color: var(--oc-fg);
  border-radius: 6px;
  padding: 3px 8px;
  font-size: 11.5px;
  cursor: pointer;
}
.btw-capsule {
  border: 1px solid var(--oc-border);
  border-radius: 12px;
  background: var(--oc-bg-elevated);
  padding: 8px 10px;
  display: grid;
  gap: 6px;
}
.btw-capsule:focus-within {
  border-color: var(--oc-border-strong);
}
.btw-textarea {
  width: 100%;
  min-height: 48px;
  max-height: 120px;
  resize: vertical;
  border: none;
  outline: none;
  background: transparent;
  color: var(--oc-fg);
  font: inherit;
  font-size: 12.5px;
  line-height: 1.5;
  padding: 0;
}
.btw-textarea::placeholder {
  color: var(--oc-fg-muted);
}
.btw-capsule-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.btw-bar-left,
.btw-bar-right {
  display: flex;
  align-items: center;
  gap: 6px;
}
.btw-state-pill {
  font-size: 11px;
  color: var(--oc-accent);
  background: rgba(255, 255, 255, 0.06);
  border-radius: 999px;
  padding: 2px 8px;
}
.btw-help-btn {
  width: 18px;
  height: 18px;
  border-radius: 999px;
  border: 1px solid var(--oc-hairline);
  background: transparent;
  color: var(--oc-fg-muted);
  font-size: 11px;
  cursor: pointer;
}
.btw-stop-btn {
  border: 1px solid rgba(248, 113, 113, 0.35);
  background: rgba(248, 113, 113, 0.12);
  color: var(--oc-danger);
  border-radius: 6px;
  padding: 4px 9px;
  font-size: 11.5px;
  cursor: pointer;
}
.btw-send-btn {
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 999px;
  background: var(--oc-primary);
  color: var(--oc-primary-fg);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.btw-send-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.btw-dialog-cancel,
.btw-dialog-confirm {
  border-radius: 6px;
  padding: 6px 12px;
  font-size: 12.5px;
  cursor: pointer;
}
.btw-dialog-cancel {
  border: 1px solid var(--oc-border);
  background: transparent;
  color: var(--oc-fg-muted);
}
.btw-dialog-confirm {
  border: none;
  background: var(--oc-primary);
  color: var(--oc-primary-fg);
  font-weight: 500;
}
</style>
