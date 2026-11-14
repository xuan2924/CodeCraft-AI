<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import * as api from './api';
import { commandPolicy, errorMessage } from './errors';
import CopilotProposals from './CopilotProposals.vue';
import CopilotTests from './CopilotTests.vue';
import CopilotApprovalMode from './CopilotApprovalMode.vue';

const props = defineProps<{ owner: string; exerciseId: string; credential: string; disabled: boolean; prepare: () => Promise<boolean> }>();
const emit = defineEmits<{ unauthorized: []; changed: []; 'inspect-test': [testId: string] }>();
const client = useQueryClient();
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.credential}`);
const text = ref('');
const fee = ref(false);
const saving = ref(false);
const saveError = ref('');
const after = ref('-1');
const connected = ref(false);
const taskKey = computed(() => ['copilot', props.owner, props.exerciseId, 'task']);
const historyKey = computed(() => ['copilot', props.owner, props.exerciseId, 'messages', after.value]);
const latest = useQuery({ queryKey: taskKey, queryFn: ({ signal }) => api.latest(props.owner, props.exerciseId, signal), retry: false, gcTime: 0, refetchInterval: 2000 });
const history = useQuery({ queryKey: historyKey, queryFn: ({ signal }) => api.messages(props.owner, props.exerciseId, after.value, signal), retry: false, gcTime: 0 });
const task = computed(() => latest.data.value?.task);
const running = computed(() => api.active(task.value));
watch([latest.error, history.error], (errors) => { if (errors.some(commandPolicy.identityFailure)) emit('unauthorized'); });
watch(() => latest.data.value?.lastSequence, () => { void client.invalidateQueries({ queryKey: ['copilot', props.owner, props.exerciseId, 'messages'] }); });
watch(scope, () => { text.value = ''; fee.value = false; saveError.value = ''; saving.value = false; }, { flush: 'sync' });
const start = useCommand<api.Task>(() => scope.value, () => {
  text.value = ''; fee.value = false; after.value = '-1'; void latest.refetch(); void history.refetch();
}, commandPolicy, () => emit('unauthorized'));
const stop = useCommand<api.Task>(() => `${props.owner}/${props.exerciseId}/${task.value?.id}`, () => { void latest.refetch(); }, commandPolicy, () => emit('unauthorized'));
const canSend = computed(() => !props.disabled && !!props.credential && !running.value && !latest.isPending.value && !latest.isError.value
  && !start.pending.value && !start.uncertain.value && !saving.value && fee.value && !!text.value.trim());
async function send() {
  if (!canSend.value) return;
  if (new globalThis.TextEncoder().encode(text.value).length > 4096) { saveError.value = '请求文字最多 4 KiB，请缩短后再发送。'; return; }
  const before = scope.value;
  const prompt = text.value;
  const { owner, exerciseId, credential } = props;
  saving.value = true; saveError.value = '';
  try {
    if (!await props.prepare()) { if (scope.value === before) saveError.value = '草稿保存未完成，未创建 Copilot 请求。请处理冲突或返回编辑。'; return; }
    if (scope.value !== before || props.disabled) return;
    await start.submit((key, signal) => api.start(owner, exerciseId, credential, prompt, key, signal));
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
      void client.invalidateQueries({ queryKey: ['copilot', owner, exercise, 'task'] });
    }, () => {
      if (disposed) return;
      connected.value = false; close();
      if (timer === undefined) timer = globalThis.setTimeout(() => { timer = undefined; connect(); }, 2000);
    }, controller.signal);
  }
  connect();
  cleanup(() => { disposed = true; controller.abort(); close(); if (timer !== undefined) globalThis.clearTimeout(timer); });
}, { immediate: true });
const labels: Record<api.Task['state'], string> = { QUEUED: '已受理，等待派发', RUNNING: '正在执行', PAUSED: '已暂停，等待处理', WAITING_APPROVAL: '等待修改审批', WAITING_WRITE: '等待写入回执', WAITING_TEST: '等待测试确定结果', STOPPING: '正在停止，等待当前操作收敛', STOPPED: '已停止', SUCCEEDED: '已完成', FAILED: '失败', INTERRUPTED: '已中断，不会自动重发' };
</script>
<template>
  <section
    class="copilot-panel"
    aria-label="Copilot 对话"
  >
    <h2>Copilot · 读取、修改提案与测试</h2>
    <p>模型读取服务端已保存代码，可提出整批修改，默认须经你审批后写入；可对指定保存版本运行基础测试，结果不代表后续版本或未保存草稿。</p>
    <p>请求可能收费，多轮任务可能持续调用模型。关闭页面不停止后台任务；停止后已发出的请求仍可能收费。</p>
    <CopilotApprovalMode
      :owner="owner"
      :exercise-id="exerciseId"
      :credential="credential"
      :disabled="disabled"
      @unauthorized="emit('unauthorized')"
    />
    <p
      v-if="latest.isPending.value"
      role="status"
    >
      正在读取任务…
    </p>
    <p
      v-else-if="latest.isError.value"
      role="alert"
    >
      {{ errorMessage(latest.error.value) }}
    </p>
    <p
      v-else-if="task"
      role="status"
    >
      {{ labels[task.state] }} · {{ task.id }}
    </p>
    <p v-else>
      尚无 Copilot 任务。
    </p>
    <p v-if="task?.reason">
      {{ errorMessage(new ApiError(409, task.reason)) }}
    </p>
    <p v-if="latest.data.value?.textHistory">
      模型配置域已变化：完整历史批次作为带来源的只读文本证据传入；原始协议证据仍保留，不重做已完成工具。
    </p>
    <p v-if="running">
      {{ connected ? '实时状态已连接' : '实时状态重连中；只读轮询仍在核对，不会重启任务。' }}
    </p>
    <NButton
      v-if="running"
      :disabled="stop.pending.value"
      @click="stop.uncertain.value ? stop.retry() : stopTask()"
    >
      {{ stop.uncertain.value ? '核对原停止请求' : '停止 Copilot 任务' }}
    </NButton>
    <p
      v-if="stop.error.value"
      role="alert"
    >
      {{ stop.error.value }}
    </p>
    <p v-if="task?.state === 'PAUSED'">
      请处理模型配置或容量问题；可先停止当前任务，再显式发起新请求。不会自动回退或重发。
    </p>
    <CopilotTests
      v-if="task"
      :key="owner + task.id + ':tests'"
      :owner="owner"
      :task-id="task.id"
      :running="running"
      @inspect="emit('inspect-test', $event)"
    />
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
    <div
      class="messages"
      aria-live="polite"
    >
      <p v-if="history.isPending.value">
        正在读取已保存对话…
      </p>
      <p
        v-else-if="history.isError.value"
        role="alert"
      >
        {{ errorMessage(history.error.value) }}
      </p>
      <p v-else-if="!history.data.value?.messages.length">
        本页没有保留的消息。
      </p>
      <article
        v-for="message in history.data.value?.messages"
        :key="message.id"
      >
        <h3>{{ ({ USER: '你', ASSISTANT: 'Copilot', TOOL: '工具回执' })[message.kind] }}</h3>
        <pre>{{ message.text }}</pre>
      </article>
    </div>
    <aside v-if="latest.data.value?.partial.text" aria-label="未确认回复预览">
      <h3>未确认的部分回复</h3>
      <pre>{{ latest.data.value.partial.text }}</pre>
      <p>此预览不代表任务完成，也不会作为完整助手消息或工具请求执行。</p>
      <p v-if="latest.data.value.partial.truncated">预览已截断。完整确认的回复将在上方对话记录显示。</p>
    </aside>
    <p>当前仅展示有界的一页消息，不代表完整历史；任务状态以上方结构化状态为准。</p>
    <NButton @click="after = '-1'; history.refetch()">
      显示最近消息
    </NButton>
    <NButton @click="after = '0'">
      从最早记录查看
    </NButton>
    <NButton
      v-if="after !== '-1' && history.data.value?.nextCursor"
      @click="after = history.data.value.nextCursor"
    >
      下一页历史
    </NButton>
    <NButton @click="latest.refetch(); history.refetch()">
      重新读取
    </NButton>
    <label :for="`copilot-text-${exerciseId}`">给 Copilot 的请求</label>
    <textarea
      :id="`copilot-text-${exerciseId}`"
      v-model="text"
      rows="4"
      :disabled="saving || start.pending.value || start.uncertain.value"
    />
    <label :for="`copilot-fee-${exerciseId}`"><input
      :id="`copilot-fee-${exerciseId}`"
      v-model="fee"
      type="checkbox"
      :disabled="saving || start.pending.value || start.uncertain.value"
    >我确认模型请求可能收费</label>
    <p>发送前将尝试保存当前页全部草稿；如需继续编辑，请不要发送。保存冲突或期间产生新编辑时不会启动请求。</p>
    <p
      v-if="saveError"
      role="alert"
    >
      {{ saveError }}
    </p>
    <p
      v-if="start.error.value"
      role="alert"
    >
      {{ start.error.value }}
    </p>
    <p v-if="start.uncertain.value">
      受理结果未知，仅可核对原请求；不会换键重复派发。
    </p>
    <NButton
      v-if="start.uncertain.value"
      :disabled="start.pending.value || disabled || !credential"
      @click="start.retry"
    >
      核对原 Copilot 请求
    </NButton>
    <NButton
      v-else
      :disabled="!canSend"
      :loading="saving || start.pending.value"
      @click="send"
    >
      保存草稿并发送
    </NButton>
  </section>
</template>
<style scoped>
.copilot-panel { border-top: 1px solid var(--line); padding-top: 20px; margin-top: 24px; }
.messages { max-height: 560px; overflow: auto; }
article { border-bottom: 1px solid var(--line); padding-bottom: 12px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; }
label, textarea { display: block; margin: 12px 0; }
textarea { box-sizing: border-box; width: 100%; }
</style>
