<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import * as api from './api';
import { commandPolicy, errorMessage } from './errors';
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

const props = defineProps<{ owner: string; exerciseId: string; conversationId?: string; delegated?: boolean; paths?: string[]; credential: string; disabled: boolean; prepare: () => Promise<boolean> }>();
const emit = defineEmits<{ unauthorized: []; changed: []; 'inspect-test': [testId: string]; fork: [messageId: string] }>();
const client = useQueryClient();
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.conversationId ?? props.exerciseId}/${props.credential}`);
const text = ref('');
const fee = ref(false);
const selectedSkills = ref<string[]>([]);
const references = ref<ReferencePage[]>([]);
const contextBusy = ref(true);
const saving = ref(false);
const saveError = ref('');
const handoffNotice = ref('');
const after = ref('-1');
const connected = ref(false);
const transientPreview = ref<api.PreviewEvent | null>(null);
const taskKey = computed(() => ['copilot', props.owner, props.exerciseId, 'task', props.conversationId ?? props.exerciseId]);
const historyKey = computed(() => ['copilot', props.owner, props.exerciseId, 'messages', props.conversationId ?? props.exerciseId, after.value]);
const latest = useQuery({ queryKey: taskKey, queryFn: ({ signal }) => api.latest(props.owner, props.exerciseId, signal, props.conversationId), retry: false, gcTime: 0, refetchInterval: 2000 });
const history = useQuery({ queryKey: historyKey, queryFn: ({ signal }) => api.messages(props.owner, props.exerciseId, after.value, signal, props.conversationId), retry: false, gcTime: 0 });
const task = computed(() => latest.data.value?.task);
const running = computed(() => api.active(task.value));
const preview = computed(() => api.resolveDisplayPreview(transientPreview.value, latest.data.value, running.value));
watch([latest.error, history.error], (errors) => { if (errors.some(commandPolicy.identityFailure)) emit('unauthorized'); });
watch(() => latest.data.value?.lastSequence, () => {
  transientPreview.value = null;
  void client.invalidateQueries({ queryKey: ['copilot', props.owner, props.exerciseId, 'messages'] });
});
watch(scope, () => { text.value = ''; fee.value = false; selectedSkills.value = []; references.value = []; saveError.value = ''; handoffNotice.value = ''; saving.value = false; transientPreview.value = null; }, { flush: 'sync' });
const start = useCommand<api.Task>(() => scope.value, () => {
  text.value = ''; fee.value = false; selectedSkills.value = []; references.value = []; after.value = '-1'; void latest.refetch(); void history.refetch();
}, commandPolicy, () => emit('unauthorized'));
const stop = useCommand<api.Task>(() => `${props.owner}/${props.exerciseId}/${task.value?.id}`, () => { void latest.refetch(); }, commandPolicy, () => emit('unauthorized'));
const handoff = useCommand<api.Steer>(() => `${scope.value}/${task.value?.id}`, receipt => {
  handoffNotice.value = receipt.state === 'NOT_APPLIED' ? '旁支带回未生效，未另起任务。' : '旁支结论已作为 steer 接收；是否生效以主线方向调整记录为准。';
  void client.invalidateQueries({ queryKey: ['copilot-steers', props.owner, props.exerciseId, receipt.taskId] });
}, commandPolicy, () => emit('unauthorized'));
const canSteer = computed(() => !!task.value && ['QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'WAITING_WRITE', 'WAITING_TEST', 'WAITING_COMMAND', 'WAITING_CHILD'].includes(task.value.state));
function bringBack(conclusion: string) {
  if (props.disabled || saving.value || start.pending.value || start.uncertain.value || handoff.pending.value || handoff.uncertain.value) return;
  if (new globalThis.TextEncoder().encode(conclusion).length > 4096) { handoffNotice.value = '带回内容过长，请缩短预览片段。'; return; }
  if (running.value) {
    if (!task.value || !canSteer.value) { handoffNotice.value = '主任务暂停或停止收敛中，当前不能接收 steer；旁支结论仍保留。'; return; }
    const { owner, exerciseId, credential } = props; const id = task.value.id;
    void handoff.submit((key, signal) => api.sendSteer(owner, exerciseId, id, credential, conclusion, key, signal));
  } else {
    const draft = text.value ? `${text.value}\n\n${conclusion}` : conclusion;
    if (new globalThis.TextEncoder().encode(draft).length > 4096) { handoffNotice.value = '主请求已有草稿，合并后超过 4 KiB，未覆盖原草稿；请缩短或手动复制。'; return; }
    text.value = draft; fee.value = false; handoffNotice.value = '已填入主请求草稿，尚未发送；请预览并确认费用。';
  }
}
const canSend = computed(() => !props.delegated && !props.disabled && !!props.credential && !running.value && !contextBusy.value && !latest.isPending.value && !latest.isError.value
  && !start.pending.value && !start.uncertain.value && !saving.value && fee.value && !!text.value.trim());
async function send() {
  if (!canSend.value) return;
  if (new globalThis.TextEncoder().encode(text.value).length > 4096) { saveError.value = '请求文字最多 4 KiB，请缩短后再发送。'; return; }
  const before = scope.value;
  const prompt = text.value;
  const skillIds = [...selectedSkills.value];
  const referenceSelections = references.value.map(selection);
  const { owner, exerciseId, credential, conversationId } = props;
  saving.value = true; saveError.value = '';
  try {
    if (!await props.prepare()) { if (scope.value === before) saveError.value = '草稿保存未完成，未创建 Copilot 请求。请处理冲突或返回编辑。'; return; }
    if (scope.value !== before || props.disabled) return;
    await start.submit((key, signal) => api.start(owner, exerciseId, credential, prompt, key, signal, skillIds, conversationId, referenceSelections));
  } catch {
    if (scope.value === before) saveError.value = '操作结果待核对，请重新读取任务并检查编辑器；不要换键重发。';
  } finally { if (scope.value === before) saving.value = false; }
}
function stopTask() {
  if (!task.value || !running.value) return;
  const { owner, exerciseId } = props; const id = task.value.id;
  void stop.submit((key, signal) => api.stop(owner, exerciseId, id, key, signal));
}
watch([() => props.owner, () => props.exerciseId, () => task.value?.id, running], ([owner, exercise, id, active], _old, cleanup) => {
  connected.value = false;
  transientPreview.value = null;
  if (!id || !active) return;
  const controller = new globalThis.AbortController();
  let close = () => {};
  let timer: ReturnType<typeof globalThis.setTimeout> | undefined;
  let disposed = false;
  function connect() {
    if (disposed) return;
    close();
    close = api.watchTask(exercise, id!, () => {
      if (disposed) return;
      connected.value = true;
      transientPreview.value = null;
      void client.invalidateQueries({ queryKey: ['copilot', owner, exercise, 'task'] });
    }, () => {
      if (disposed) return;
      connected.value = false;
      transientPreview.value = null;
      close();
      if (timer === undefined) timer = globalThis.setTimeout(() => { timer = undefined; connect(); }, 2000);
    }, controller.signal, (incoming) => {
      if (disposed) return;
      transientPreview.value = api.nextTransientPreview(transientPreview.value, incoming, id, running.value);
    });
  }
  connect();
  cleanup(() => { disposed = true; transientPreview.value = null; controller.abort(); close(); if (timer !== undefined) globalThis.clearTimeout(timer); });
}, { immediate: true });
const labels: Record<api.Task['state'], string> = { QUEUED: '已受理，等待派发', RUNNING: '正在执行', PAUSED: '已暂停，等待处理', WAITING_APPROVAL: '等待审批', WAITING_WRITE: '等待写入回执', WAITING_TEST: '等待测试确定结果', WAITING_COMMAND: '等待命令及文件同步结果', WAITING_CHILD: '等待真实子任务交接，父 worker 已释放', STOPPING: '正在停止，等待当前操作收敛', STOPPED: '已停止', SUCCEEDED: '已完成', FAILED: '失败', INTERRUPTED: '已中断，不会自动重发' };
</script>
<template>
  <section
    class="copilot-panel"
    :aria-label="delegated ? '委派子任务对话' : 'Copilot 对话'"
  >
    <div class="copilot-head">
      <div class="copilot-head-row">
        <h2>{{ delegated ? '子任务 Copilot' : 'Copilot' }}</h2>
        <details class="copilot-rules-drawer">
          <summary>协作与计费说明</summary>
          <p v-if="delegated">
            子任务不能再次委派；审批、方向调整与停止仅针对当前子任务，共享本场审批模式。
          </p>
          <p>
            读取服务端已保存代码，可提出整批修改与运行测试；请求可能收费，关闭页面不停止后台任务。
          </p>
        </details>
      </div>
    </div>
    <CopilotApprovalMode
      v-if="!delegated"
      :owner="owner"
      :exercise-id="exerciseId"
      :credential="credential"
      :disabled="disabled"
      @unauthorized="emit('unauthorized')"
    />
    <div
      v-if="latest.isPending.value || latest.isError.value || task || running"
      class="copilot-task-strip"
    >
      <p
        v-if="latest.isPending.value"
        role="status"
        class="copilot-status"
      >
        正在读取任务…
      </p>
      <p
        v-else-if="latest.isError.value"
        role="alert"
        class="copilot-alert"
      >
        {{ errorMessage(latest.error.value) }}
      </p>
      <div
        v-else-if="task"
        class="copilot-task-row"
      >
        <p
          role="status"
          class="copilot-task-badge"
        >
          {{ labels[task.state] }} · {{ task.id }}
        </p>
        <Button
          v-if="running"
          variant="danger"
          size="small"
          :disabled="stop.pending.value"
          @click="stop.uncertain.value ? stop.retry() : stopTask()"
        >
          {{ stop.uncertain.value ? '核对原停止请求' : delegated ? '停止此子任务并交回结果' : '停止 Copilot 任务' }}
        </Button>
      </div>
      <p
        v-if="task?.reason"
        class="copilot-warn"
      >
        {{ errorMessage(new ApiError(409, task.reason)) }}
      </p>
      <p
        v-if="running"
        class="copilot-status"
      >
        {{ connected ? '实时状态已连接' : '实时状态重连中；只读轮询仍在核对，不会重启任务。' }}
      </p>
      <p
        v-if="stop.error.value"
        role="alert"
        class="copilot-alert"
      >
        {{ stop.error.value }}
      </p>
      <p
        v-if="task?.state === 'PAUSED'"
        class="copilot-warn"
      >
        {{ delegated ? '子任务已暂停。处理配置或容量问题后，可停止子任务并把停止结果交回父任务；不会自动重发。' : '请处理模型配置或容量问题；可先停止当前任务，再显式发起新请求。不会自动回退或重发。' }}
      </p>
    </div>
    <p
      v-else
      class="copilot-desc"
    >
      尚无 Copilot 任务。
    </p>
    <p
      v-if="latest.data.value?.textHistory"
      class="copilot-desc"
    >
      模型配置域已变化：完整历史批次作为带来源的只读文本证据传入；原始协议证据仍保留，不重做已完成工具。
    </p>
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
    <CopilotSteers
      v-if="task"
      :key="owner + task.id + ':steers'"
      :owner="owner"
      :exercise-id="exerciseId"
      :task-id="task.id"
      :state="task.state"
      :credential="credential"
      :disabled="disabled"
      @unauthorized="emit('unauthorized')"
    />
    <div
      class="messages"
      aria-live="polite"
    >
      <p
        v-if="history.isPending.value"
        class="copilot-desc"
      >
        正在读取已保存对话…
      </p>
      <p
        v-else-if="history.isError.value"
        role="alert"
        class="copilot-alert"
      >
        {{ errorMessage(history.error.value) }}
      </p>
      <p
        v-else-if="!history.data.value?.messages.length"
        class="copilot-desc"
      >
        本页没有保留的消息。
      </p>
      <article
        v-for="message in history.data.value?.messages"
        :key="message.id"
        class="message-card"
        :data-kind="message.kind"
      >
        <div class="message-head">
          <h3>{{ ({ USER: '你', ASSISTANT: 'Copilot', TOOL: '工具回执' })[message.kind] }}</h3>
          <Button
            v-if="!delegated"
            variant="ghost"
            size="small"
            :disabled="disabled || !credential"
            @click="emit('fork', message.id)"
          >
            Fork 到此消息
          </Button>
        </div>
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
              class="ui-badge tool-call-chip"
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
    </div>
    <aside
      v-if="preview"
      class="partial-preview"
      aria-label="未确认回复预览"
    >
      <h3>未确认的部分回复</h3>
      <CopilotMessageBody
        :text="preview.text"
        :thinking="preview.thinking"
      />
      <p>此预览不代表任务完成，也不会作为完整助手消息或工具请求执行。</p>
      <p v-if="preview.truncated || preview.thinkingTruncated">
        预览已截断。完整确认的回复将在上方对话记录显示。
      </p>
    </aside>
    <div class="copilot-pagination">
      <Button
        variant="ghost"
        size="small"
        @click="after = '-1'; history.refetch()"
      >
        显示最近消息
      </Button>
      <Button
        variant="ghost"
        size="small"
        @click="after = '0'"
      >
        从最早记录查看
      </Button>
      <Button
        v-if="after !== '-1' && history.data.value?.nextCursor"
        variant="ghost"
        size="small"
        @click="after = history.data.value.nextCursor"
      >
        下一页历史
      </Button>
      <Button
        variant="ghost"
        size="small"
        @click="latest.refetch(); history.refetch()"
      >
        重新读取
      </Button>
    </div>
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
    <div
      v-if="!delegated"
      class="copilot-composer"
    >
      <label
        :for="`copilot-text-${exerciseId}`"
        class="sr-only"
      >给 Copilot 的请求</label>
      <textarea
        :id="`copilot-text-${exerciseId}`"
        v-model="text"
        rows="3"
        placeholder="向 Copilot 描述目标、约束或待排查问题…"
        :disabled="saving || start.pending.value || start.uncertain.value"
      />
      <div class="composer-bar">
        <label
          :for="`copilot-fee-${exerciseId}`"
          class="fee-checkbox"
        ><input
          :id="`copilot-fee-${exerciseId}`"
          v-model="fee"
          type="checkbox"
          :disabled="saving || start.pending.value || start.uncertain.value"
        ><span>确认模型请求可能收费</span></label>
        <Button
          v-if="start.uncertain.value"
          size="small"
          :disabled="start.pending.value || disabled || !credential"
          @click="start.retry"
        >
          核对原 Copilot 请求
        </Button>
        <Button
          v-else
          variant="primary"
          size="small"
          :disabled="!canSend"
          :loading="saving || start.pending.value"
          @click="send"
        >
          保存草稿并发送
        </Button>
      </div>
      <p
        v-if="saveError"
        role="alert"
        class="copilot-alert"
      >
        {{ saveError }}
      </p>
      <p
        v-if="start.error.value"
        role="alert"
        class="copilot-alert"
      >
        {{ start.error.value }}
      </p>
      <p
        v-if="start.uncertain.value"
        class="copilot-warn"
      >
        受理结果未知，仅可核对原请求；不会换键重复派发。
      </p>
      <div class="composer-drawers">
        <details class="panel-drawer">
          <summary>Skill 指导（已选 {{ selectedSkills.length }}）</summary>
          <CopilotSkills
            v-model="selectedSkills"
            :owner="owner"
            :task-id="task?.id ?? null"
            :disabled="disabled || running || saving || start.pending.value || start.uncertain.value"
            @unauthorized="emit('unauthorized')"
          />
        </details>
        <details class="panel-drawer">
          <summary>上下文引用（{{ references.length }}/4）</summary>
          <CopilotReferences
            v-model="references"
            :owner="owner"
            :exercise-id="exerciseId"
            :conversation-id="conversationId ?? exerciseId"
            :paths="paths"
            :disabled="disabled || running || saving || start.pending.value || start.uncertain.value"
            @unauthorized="emit('unauthorized')"
          />
        </details>
        <details class="panel-drawer">
          <summary>后续队列与 BTW 旁支</summary>
          <div class="drawer-stack">
            <CopilotFollowups
              :owner="owner"
              :exercise-id="exerciseId"
              :conversation-id="conversationId ?? exerciseId"
              :credential="credential"
              :disabled="disabled"
              :running="running"
              @unauthorized="emit('unauthorized')"
              @changed="latest.refetch()"
            />
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
        </details>
      </div>
      <p
        v-if="handoffNotice"
        role="status"
        class="copilot-status"
      >
        {{ handoffNotice }}
      </p>
      <p
        v-if="handoff.error.value"
        role="alert"
        class="copilot-alert"
      >
        {{ handoff.error.value }}
      </p>
      <Button
        v-if="handoff.uncertain.value"
        size="small"
        :disabled="handoff.pending.value"
        @click="handoff.retry()"
      >
        核对原旁支带回 steer（不换键）
      </Button>
    </div>
    <details class="panel-drawer">
      <summary>用量与上下文状态</summary>
      <div class="drawer-stack">
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
          @busy="contextBusy = $event"
          @unauthorized="emit('unauthorized')"
        />
      </div>
    </details>
    <CopilotProjectInstructions
      :owner="owner"
      :exercise-id="exerciseId"
      :task-id="task?.id ?? null"
      :running="running"
      @unauthorized="emit('unauthorized')"
    />
  </section>
</template>
<style scoped>
.copilot-panel {
  border-top: 1px solid var(--oc-border);
  padding-top: 10px;
  margin-top: 2px;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 8px;
}

.copilot-head-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.copilot-head h2 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.copilot-rules-drawer {
  font-size: 11.5px;
  color: var(--oc-fg-muted);
}

.copilot-rules-drawer summary {
  cursor: pointer;
}

.copilot-rules-drawer p {
  margin: 4px 0 0;
  line-height: 1.45;
}

.copilot-task-strip {
  display: grid;
  gap: 6px;
}

.copilot-task-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.copilot-desc {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
  line-height: 1.5;
}

.copilot-status {
  margin: 0;
  font-size: 12px;
  color: var(--oc-accent);
}

.copilot-task-badge {
  margin: 0;
  padding: 4px 9px;
  border-radius: var(--radius-control);
  background: var(--oc-accent-soft);
  border: 1px solid var(--oc-border-accent);
  color: var(--oc-accent);
  font-size: 11.5px;
  font-weight: 550;
  overflow-wrap: anywhere;
}

.copilot-alert {
  margin: 0;
  padding: 7px 10px;
  border-radius: var(--radius-control);
  background: var(--oc-danger-bg);
  border: 1px solid var(--oc-danger-border);
  color: var(--oc-danger);
  font-size: 12px;
}

.copilot-warn {
  margin: 0;
  padding: 7px 10px;
  border-radius: var(--radius-control);
  background: var(--oc-warning-bg);
  border: 1px solid var(--oc-warning-border);
  color: var(--oc-warning);
  font-size: 12px;
}

.messages {
  max-height: 460px;
  overflow: auto;
  display: grid;
  gap: 8px;
}

.message-card {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 6px;
}

.message-card[data-kind="USER"] {
  background: var(--oc-selected);
  border-color: var(--oc-border-accent);
}

.message-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.message-head h3 {
  margin: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--oc-fg);
}

.assistant-tool-calls {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tool-call-chip {
  font-size: 11.5px;
}

.tool-call-chip code {
  color: var(--oc-accent);
  font-size: 11.5px;
}

.partial-preview {
  padding: 10px;
  border-radius: 8px;
  background: var(--oc-surface-2);
  border: 1px dashed var(--oc-border-accent);
  display: grid;
  gap: 6px;
}

.partial-preview h3 {
  margin: 0;
  font-size: 12.5px;
}

.partial-preview p {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.copilot-pagination {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.copilot-composer {
  padding: 10px 12px;
  border-radius: var(--radius-panel);
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border-strong);
  display: grid;
  gap: 8px;
}

.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.fee-checkbox {
  display: flex;
  align-items: center;
  gap: 7px;
  font-weight: 500;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.composer-drawers {
  display: grid;
  gap: 6px;
  padding-top: 6px;
  border-top: 1px solid var(--oc-border);
}

.panel-drawer {
  padding: 7px 10px;
  border-radius: var(--radius-control);
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.panel-drawer summary {
  cursor: pointer;
  font-weight: 550;
  color: var(--oc-fg);
}

.drawer-stack {
  margin-top: 8px;
  display: grid;
  gap: 10px;
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

pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
}

textarea {
  width: 100%;
  padding: 8px 10px;
  font-size: 12.5px;
  resize: vertical;
}
</style>
