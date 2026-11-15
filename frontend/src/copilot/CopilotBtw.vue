<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { useCommand } from '../api/useCommand';
import { commandPolicy, errorMessage } from './errors';
import * as api from './api';
import * as btwApi from './btwApi';
import CopilotMessageBody from './CopilotMessageBody.vue';
import CopilotWebResult from './CopilotWebResult.vue';
import CopilotUsage from './CopilotUsage.vue';
const props = defineProps<{ owner: string; exerciseId: string; parentConversationId: string; credential: string; disabled: boolean; mainRunning: boolean; handoffDisabled?: boolean }>();
const emit = defineEmits<{ unauthorized: []; bringback: [text: string] }>();
const client = useQueryClient();
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.parentConversationId}/${props.credential}`);
const text = ref(''); const fee = ref(false); const validation = ref(''); const after = ref('-1'); const handoff = ref(''); const sourceMessage = ref('');
const side = useQuery({ queryKey: computed(() => ['copilot', props.owner, props.exerciseId, 'btw', props.parentConversationId]),
  queryFn: ({ signal }) => btwApi.find(props.owner, props.exerciseId, props.parentConversationId, signal), retry: false, gcTime: 0, refetchInterval: 2000 });
const conversation = computed(() => side.data.value?.conversationId);
const taskKey = computed(() => ['copilot', props.owner, props.exerciseId, 'task', conversation.value ?? `btw-uncreated:${props.parentConversationId}`]);
const latest = useQuery({ queryKey: taskKey, enabled: computed(() => !!conversation.value),
  queryFn: ({ signal }) => conversation.value ? api.latest(props.owner, props.exerciseId, signal, conversation.value) : Promise.resolve(null), retry: false, gcTime: 0, refetchInterval: 2000 });
const history = useQuery({ queryKey: computed(() => ['copilot', props.owner, props.exerciseId, 'messages', conversation.value, after.value]), enabled: computed(() => !!conversation.value),
  queryFn: ({ signal }) => conversation.value ? api.messages(props.owner, props.exerciseId, after.value, signal, conversation.value) : Promise.resolve({ messages: [], nextCursor: null }), retry: false, gcTime: 0 });
const task = computed(() => latest.data.value?.task); const running = computed(() => api.active(task.value));
watch(scope, () => { text.value = ''; fee.value = false; validation.value = ''; handoff.value = ''; sourceMessage.value = ''; after.value = '-1'; }, { flush: 'sync' });
watch([side.error, latest.error, history.error], errors => { if (errors.some(commandPolicy.identityFailure)) emit('unauthorized'); });
watch(() => latest.data.value?.lastSequence, () => { if (conversation.value) void history.refetch(); });
const send = useCommand<api.Task>(() => scope.value, () => { text.value = ''; fee.value = false; void side.refetch(); if (conversation.value) { void latest.refetch(); void history.refetch(); } }, commandPolicy, () => emit('unauthorized'));
const stop = useCommand<api.Task>(() => `${scope.value}/${task.value?.id}`, () => { void latest.refetch(); }, commandPolicy, () => emit('unauthorized'));
const busy = computed(() => send.pending.value || send.uncertain.value);
const canSend = computed(() => !props.disabled && !!props.credential && !busy.value && !running.value && fee.value && !!text.value.trim()
  && !side.isPending.value && !side.isError.value && (!conversation.value || !latest.isPending.value && !latest.isError.value));
function ask() {
  if (!canSend.value) return;
  if (new globalThis.TextEncoder().encode(text.value).length > 4096) { validation.value = '旁支问题最多 4 KiB。'; return; }
  const { owner, exerciseId, parentConversationId, credential } = props; const prompt = text.value; const expected = conversation.value;
  validation.value = ''; void send.submit((key, signal) => btwApi.start(owner, exerciseId, parentConversationId, credential, prompt, key, signal, expected));
}
function stopSide() {
  if (!task.value || !running.value) return;
  const { owner, exerciseId } = props; const id = task.value.id;
  void stop.submit((key, signal) => api.stop(owner, exerciseId, id, key, signal));
}
watch([() => props.owner, () => props.exerciseId, conversation, () => task.value?.id, running], ([owner, exercise, sideId, id, active], _old, cleanup) => {
  if (!sideId || !id || !active) return;
  const controller = new globalThis.AbortController();
  let close = () => {}; let disposed = false; let timer: ReturnType<typeof globalThis.setTimeout> | undefined;
  function refresh() { if (!disposed) void client.invalidateQueries({ queryKey: ['copilot', owner, exercise, 'task', sideId] }); }
  function connect() {
    if (disposed) return;
    close(); close = api.watchTask(exercise, id!, refresh, () => {
      if (disposed) return;
      refresh(); close();
      if (timer === undefined) timer = globalThis.setTimeout(() => { timer = undefined; connect(); }, 2000);
    }, controller.signal);
  }
  connect();
  cleanup(() => { disposed = true; controller.abort(); close(); if (timer !== undefined) globalThis.clearTimeout(timer); });
});
function choose(message: api.Message) {
  let excerpt = ''; let bytes = 0; const encoder = new globalThis.TextEncoder();
  for (const point of message.text) { const size = encoder.encode(point).length; if (bytes + size > 3800) break; excerpt += point; bytes += size; }
  sourceMessage.value = message.id; handoff.value = excerpt;
  validation.value = excerpt.length < message.text.length ? '带回预览仅选取前 3800 字节，可编辑整理；原旁支回复仍保留。' : '';
}
const bringbackText = computed(() => `[用户选取的 BTW 结论，来源消息 ${sourceMessage.value}]\n${handoff.value}`);
const canBringback = computed(() => !props.disabled && !props.handoffDisabled && !!sourceMessage.value && !!handoff.value.trim() && new globalThis.TextEncoder().encode(bringbackText.value).length <= 4096);
</script>
<template>
  <details
    class="btw"
    aria-label="BTW 旁支讨论"
  >
    <summary>BTW · 不打断主线的只读旁支</summary>
    <p>每场最多一个活动旁支，使用你的模型并可能收费。初始仅投影主线最近用户／助手文本各最多 4 KiB，不包含完整工具历史或 hidden thinking。仅可读取/检索/联网资料，不可写入、测试、Bash 或委派。</p>
    <p>旁支不会自动带回主线；关闭页面不停止已受理任务，停止旁支不会停止主任务。</p>
    <p v-if="task">
      旁支状态：{{ task.state }}<span v-if="task.reason"> · {{ task.reason }}</span>
    </p>
    <p v-if="task?.reason === 'MODEL_RESULT_UNKNOWN'">
      供应商可能已计费；旁支输出未确认，不会自动重发。
    </p>
    <p
      v-for="(failure, index) in [side.error.value, latest.error.value, history.error.value].filter(Boolean)"
      :key="index"
      role="alert"
    >
      {{ errorMessage(failure) }}
    </p>
    <button
      type="button"
      @click="side.refetch(); if (conversation) { latest.refetch(); history.refetch(); }"
    >
      重新读取旁支
    </button>
    <article
      v-for="message in history.data.value?.messages"
      :key="message.id"
    >
      <h4>{{ ({ USER: '旁支提问／初始投影', ASSISTANT: '旁支回复', TOOL: '旁支工具回执' })[message.kind] }}</h4>
      <CopilotWebResult
        v-if="message.kind === 'TOOL' && /^web_(search|read) \[/.test(message.text)"
        :text="message.text"
        :scope="`${scope}/${message.id}`"
      />
      <CopilotMessageBody
        v-else
        :text="message.text"
        :thinking="message.thinking"
      />
      <button
        v-if="message.kind === 'ASSISTANT' && message.text"
        type="button"
        :disabled="disabled"
        @click="choose(message)"
      >
        选择此回复并预览带回
      </button>
    </article>
    <aside
      v-if="latest.data.value?.partial.text || latest.data.value?.partial.thinking"
      aria-label="旁支未确认流式回复"
    >
      <CopilotMessageBody
        :text="latest.data.value.partial.text"
        :thinking="latest.data.value.partial.thinking"
      />
      <p>未确认的部分输出，不代表成功，不可直接作为完整结论带回；已发请求仍可能收费。</p>
      <p v-if="latest.data.value.partial.truncated || latest.data.value.partial.thinkingTruncated">
        展示预览已截断，确认回复见保留消息。
      </p>
    </aside>
    <template v-if="conversation">
      <p>当前只显示有界消息页；从最早保留记录继续读取不会重新发起模型请求。</p>
      <button
        type="button"
        @click="after = '-1'; history.refetch()"
      >
        旁支最近消息
      </button>
      <button
        type="button"
        @click="after = '0'"
      >
        旁支最早保留消息
      </button>
      <button
        v-if="after !== '-1' && history.data.value?.nextCursor"
        type="button"
        @click="after = history.data.value.nextCursor"
      >
        继续读取旁支消息
      </button>
      <CopilotUsage
        :owner="owner"
        :exercise-id="exerciseId"
        :conversation-id="conversation"
        @unauthorized="emit('unauthorized')"
      />
    </template>
    <label>旁支问题<textarea
      v-model="text"
      :disabled="disabled || busy"
    /></label>
    <label><input
      v-model="fee"
      type="checkbox"
      :disabled="disabled || busy"
    >确认旁支模型及摘要可能继续收费</label>
    <button
      type="button"
      :disabled="!canSend"
      @click="ask"
    >
      发送旁支问题（不替换主任务）
    </button>
    <button
      v-if="running"
      type="button"
      :disabled="stop.pending.value || stop.uncertain.value"
      @click="stopSide"
    >
      仅停止旁支
    </button>
    <p
      v-if="send.pending.value || stop.pending.value"
      role="status"
    >
      正在提交旁支操作…
    </p>
    <p
      v-if="send.error.value"
      role="alert"
    >
      {{ send.error.value }}
    </p><p
      v-if="stop.error.value"
      role="alert"
    >
      {{ stop.error.value }}
    </p>
    <button
      v-if="send.uncertain.value"
      type="button"
      :disabled="send.pending.value"
      @click="send.retry()"
    >
      核对原旁支请求（不换键）
    </button>
    <button
      v-if="stop.uncertain.value"
      type="button"
      :disabled="stop.pending.value"
      @click="stop.retry()"
    >
      核对原旁支停止
    </button>
    <div v-if="sourceMessage">
      <label>带回内容预览（可编辑，不复制全部历史）<textarea
        v-model="handoff"
        :disabled="disabled"
      /></label>
      <button
        type="button"
        :disabled="!canBringback"
        @click="emit('bringback', bringbackText)"
      >
        {{ mainRunning ? '将预览结论作为 steer 发送到主任务' : '将预览结论填入主请求（尚未发送）' }}
      </button>
      <p v-if="handoffDisabled">
        主线暂不能接收带回内容，请先核对原操作或等待任务收敛；旁支讨论不受影响。
      </p>
      <p v-if="!canBringback && !disabled && !handoffDisabled">
        带回内容须非空且含来源标记最多 4 KiB。
      </p>
    </div>
    <p
      v-if="validation"
      role="alert"
    >
      {{ validation }}
    </p>
  </details>
</template>
<style scoped>
.btw { border: 1px solid var(--border); padding: .75rem; margin-top: 1rem; }
label { display: block; margin-top: .5rem; }
textarea { display: block; width: 100%; min-height: 4rem; box-sizing: border-box; }
article { border-top: 1px solid var(--border); margin-top: .75rem; }
</style>
