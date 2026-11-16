<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import FeedbackComposer from './FeedbackComposer.vue';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import { message } from './api';
import * as api from './interviewerApi';

const props = defineProps<{ owner: string; exerciseId: string; credential: string; disabled: boolean; phase: string }>();
const emit = defineEmits<{ unauthorized: [] }>();
const client = useQueryClient();
const text = ref('');
const before = ref('');
const scope = () => `${props.owner}/${props.exerciseId}`;
watch(scope, () => { text.value = ''; before.value = ''; }, { flush: 'sync' });
const prefix = computed(() => ['interviewer', props.owner, props.exerciseId]);
const records = useQuery({ queryKey: computed(() => [...prefix.value, before.value]),
  queryFn: ({ signal }) => api.history(props.owner, props.exerciseId, before.value, signal), retry: false, gcTime: 0,
  refetchInterval: (query) => !before.value && query.state.data?.some((item) => ['QUEUED', 'RUNNING'].includes(item.state)) ? 1000 : false });
const command = useCommand<api.InterviewerTask>(scope, () => {
  text.value = ''; before.value = ''; void client.invalidateQueries({ queryKey: prefix.value });
}, { definitiveRejection: (error) => error instanceof ApiError && [400, 401, 403, 404, 409, 410, 429].includes(error.status),
  identityFailure: (error) => error instanceof ApiError && [401, 403].includes(error.status), errorMessage: message }, () => emit('unauthorized'));
const running = computed(() => records.data.value?.some((item) => ['QUEUED', 'RUNNING'].includes(item.state)) ?? false);
const blocked = computed(() => props.disabled || !props.credential || command.pending.value || command.uncertain.value || running.value || !!before.value);
function send() {
  if (blocked.value || !text.value.trim()) return;
  const question = text.value;
  const owner = props.owner;
  const exercise = props.exerciseId;
  void command.submit((key, signal) => api.ask(owner, exercise, question, key, props.credential, signal));
}
const labels: Record<string, string> = { QUEUED: '等待面试官', RUNNING: '面试官正在回答', SUCCEEDED: '已回答', FAILED: '回答未完成' };
</script>

<template>
  <section
    class="interviewer"
    aria-label="面试官需求澄清"
  >
    <header><h2>面试官</h2><p>只解释需求，不修改代码。费用由平台承担，与 Copilot 对话分开。</p></header>
    <p v-if="phase === 'READY'">
      现在不计时；可直接开始实现，无需先提问。
    </p>
    <p
      v-if="records.isPending.value"
      role="status"
    >
      正在读取面试官对话…
    </p>
    <div
      v-else-if="records.isError.value"
      role="alert"
    >
      <p>无法读取对话，未发起新的模型请求。</p><Button
        variant="secondary"
        @click="records.refetch()"
      >
        重新读取
      </Button>
    </div>
    <p v-else-if="!records.data.value?.length">
      尚无提问。可以先核对需求，也可以直接开始实现。
    </p>
    <ol
      v-else
      class="interviewer-history"
    >
      <li
        v-for="task in records.data.value"
        :key="task.id"
      >
        <p class="interviewer-question">
          你：{{ task.question }}
        </p>
        <p role="status">
          {{ labels[task.state] }}
        </p>
        <p
          v-if="task.answer"
          class="interviewer-answer"
        >
          {{ task.answer }}
        </p>
        <p v-if="task.state === 'FAILED'">
          {{ task.reason === 'INTERVIEWER_RESULT_UNKNOWN' ? '调用结果未知，不会自动重新调用。' : '本次回答未完成或授权已改变。已保存提问，可核对状态后重新提问。' }}
        </p>
        <details>
          <summary>对此问答授权反馈</summary><FeedbackComposer
            :key="`${owner}/${exerciseId}/${task.id}`"
            :owner="owner"
            :exercise-id="exerciseId"
            target-type="INTERVIEWER"
            :target-id="task.id"
            :generation="0"
          />
        </details>
      </li>
    </ol>
    <div class="interviewer-pagination">
      <Button
        v-if="before"
        variant="ghost"
        @click="before = ''"
      >
        返回最新提问
      </Button>
      <Button
        v-if="records.data.value?.length === 20"
        variant="ghost"
        @click="before = records.data.value[19]!.id"
      >
        更早提问
      </Button>
    </div>
    <form @submit.prevent="send">
      <label for="interviewer-question">向面试官提问</label>
      <textarea
        id="interviewer-question"
        v-model="text"
        rows="3"
        maxlength="4096"
        :readonly="blocked"
        aria-describedby="interviewer-help"
      />
      <p id="interviewer-help">
        {{ disabled ? '当前阶段暂停提问，历史仍可查看。' : !credential ? '取得本页操作权后可提问。' : running ? '请等待当前回答结束后再提问。' : '最多 4 KiB 文字。提问不会自动保存草稿或延长作答时间。' }}
      </p>
      <p
        v-if="command.error.value"
        role="alert"
      >
        {{ command.error.value }}
      </p>
      <Button
        v-if="command.uncertain.value"
        type="button"
        variant="secondary"
        :disabled="command.pending.value"
        @click="command.retry"
      >
        核对原提问
      </Button>
      <Button
        v-else
        type="submit"
        variant="secondary"
        :disabled="blocked || !text.trim()"
      >
        {{ command.pending.value ? '正在提交…' : '发送给面试官' }}
      </Button>
    </form>
  </section>
</template>

<style scoped>
.interviewer { border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 1rem; min-width: 0; }
.interviewer h2 { margin: 0; font-size: 1rem; }
.interviewer header p, #interviewer-help { color: var(--muted-foreground); font-size: .875rem; }
.interviewer-history { list-style: none; padding: 0; max-height: 24rem; overflow: auto; }
.interviewer-history li { border-bottom: 1px solid var(--border); padding: .75rem 0; }
.interviewer-question, .interviewer-answer { white-space: pre-wrap; overflow-wrap: anywhere; }
.interviewer form { display: grid; gap: .5rem; }
.interviewer textarea { color: var(--foreground); background: var(--background); border: 1px solid var(--border); border-radius: var(--radius); padding: .75rem; resize: vertical; }
.interviewer textarea:focus-visible { outline: 2px solid var(--ring); outline-offset: 2px; }
.interviewer-pagination { display: flex; gap: .5rem; }
</style>
