<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { useCommand } from '../api/useCommand';
import { commandPolicy, errorMessage } from './errors';
import * as api from './followupApi';
const props = defineProps<{ owner: string; exerciseId: string; conversationId: string; credential: string; disabled: boolean; running: boolean }>();
const emit = defineEmits<{ unauthorized: []; changed: [] }>();
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.conversationId}/${props.credential}`);
const text = ref(''); const fee = ref(false); const validation = ref('');
const query = useQuery({ queryKey: computed(() => ['copilot', props.owner, props.exerciseId, 'followups', props.conversationId]),
  queryFn: ({ signal }) => api.recent(props.owner, props.exerciseId, props.conversationId, signal), retry: false, gcTime: 0, refetchInterval: 2000 });
watch(query.error, error => { if (commandPolicy.identityFailure(error)) emit('unauthorized'); });
watch(scope, () => { text.value = ''; fee.value = false; validation.value = ''; }, { flush: 'sync' });
function changed() { void query.refetch(); emit('changed'); }
const enqueue = useCommand<api.Followup>(() => scope.value, () => { text.value = ''; fee.value = false; changed(); }, commandPolicy, () => emit('unauthorized'));
const cancel = useCommand<api.Followup>(() => scope.value, changed, commandPolicy, () => emit('unauthorized'));
const resume = useCommand<{ conversationId: string; requestKey: string }>(() => scope.value, () => { fee.value = false; changed(); }, commandPolicy, () => emit('unauthorized'));
const busy = computed(() => [enqueue, cancel, resume].some(command => command.pending.value || command.uncertain.value));
const canWrite = computed(() => !props.disabled && !!props.credential && !query.isPending.value && !query.isError.value && !busy.value);
const paused = computed(() => query.data.value?.some(item => item.state === 'PAUSED'));
const pending = computed(() => query.data.value?.filter(item => item.state === 'PAUSED' || item.state === 'WAITING').length ?? 0);
function append() {
  if (!canWrite.value || !props.running || !fee.value || !text.value.trim() || pending.value >= 32) return;
  if (new globalThis.TextEncoder().encode(text.value).length > 4096) { validation.value = '后续请求最多 4 KiB。'; return; }
  validation.value = '';
  const { owner, exerciseId, conversationId, credential } = props; const prompt = text.value;
  void enqueue.submit((key, signal) => api.enqueue(owner, exerciseId, conversationId, credential, prompt, key, signal));
}
function remove(id: string) {
  if (busy.value) return;
  const { owner, exerciseId, conversationId } = props;
  void cancel.submit((key, signal) => api.cancel(owner, exerciseId, conversationId, id, key, signal));
}
function continueQueue() {
  if (!canWrite.value || !fee.value || !paused.value) return;
  const { owner, exerciseId, conversationId, credential } = props;
  void resume.submit((key, signal) => api.resume(owner, exerciseId, conversationId, credential, key, signal));
}
const labels = { WAITING: '排队等待', PAUSED: '队列暂停', DISPATCHED: '已创建独立任务', CANCELLED: '已取消' };
</script>
<template>
  <section aria-label="后续任务队列">
    <h3>后续独立任务 · {{ pending }}/32</h3>
    <p>与 steer 不同：不会改变当前任务方向。只在当前任务确定完成后按接收顺序执行；待审批和暂停不算完成。失败或停止后暂停队列，须重新确认才能继续。</p>
    <p>队列归属当前会话，刷新不会重复发送。执行时读取服务端已保存代码，不自动保存草稿或继承当前勾选 Skill；结束演练后未执行项取消。</p>
    <p
      v-if="query.isPending.value"
      role="status"
    >
      正在读取队列…
    </p>
    <p
      v-if="query.error.value"
      role="alert"
    >
      {{ errorMessage(query.error.value) }} <button
        type="button"
        @click="query.refetch()"
      >
        重新读取队列
      </button>
    </p>
    <ol>
      <li
        v-for="item in query.data.value"
        :key="item.id"
      >
        <p>{{ labels[item.state] }}<span v-if="item.reason"> · {{ item.reason }}</span></p>
        <pre>{{ item.text }}</pre>
        <p v-if="item.taskId">
          任务 {{ item.taskId }}（运行/停止见主任务区域）
        </p>
        <button
          v-if="item.state === 'WAITING' || item.state === 'PAUSED'"
          type="button"
          :disabled="busy"
          @click="remove(item.id)"
        >
          取消此排队项
        </button>
      </li>
    </ol>
    <p v-if="(query.data.value?.length ?? 0) >= 100">
      展示最近 100 项及全部待执行项；待执行项不会被隐藏。
    </p>
    <label v-if="running">后续请求<textarea
      v-model="text"
      :disabled="busy || disabled"
    /></label>
    <label v-if="running || paused"><input
      v-model="fee"
      type="checkbox"
      :disabled="busy || disabled"
    >确认后续独立任务及自动摘要可能继续收费</label>
    <button
      v-if="running"
      type="button"
      :disabled="!canWrite || !fee || !text.trim() || pending >= 32"
      @click="append"
    >
      加入后续队列（不 steer）
    </button>
    <button
      v-if="paused"
      type="button"
      :disabled="!canWrite || !fee"
      @click="continueQueue"
    >
      重新授权并继续队列
    </button>
    <p
      v-if="validation"
      role="alert"
    >
      {{ validation }}
    </p>
    <template
      v-for="(command, index) in [enqueue, cancel, resume]"
      :key="index"
    >
      <p
        v-if="command.pending.value"
        role="status"
      >
        正在提交队列操作…
      </p>
      <p
        v-if="command.error.value"
        role="alert"
      >
        {{ command.error.value }}
      </p>
      <button
        v-if="command.uncertain.value"
        type="button"
        :disabled="command.pending.value"
        @click="command.retry()"
      >
        按原意图核对队列操作（不换键）
      </button>
    </template>
  </section>
</template>
<style scoped>
section { border-top: 1px solid var(--border); margin-top: 1rem; padding-top: .75rem; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
textarea { display: block; width: 100%; min-height: 5rem; }
label { display: block; margin: .5rem 0; }
li { margin-bottom: .75rem; }
</style>
