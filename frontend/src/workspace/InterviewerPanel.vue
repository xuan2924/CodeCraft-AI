<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import Tooltip from '../components/ui/Tooltip.vue';
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
    <header>
      <div class="interviewer-title">
        <h2>面试官</h2>
        <span class="interviewer-badge">平台承担</span>
      </div>
      <Tooltip :text="`只解释需求，不修改代码。费用由平台承担，与 Copilot 对话分开。${phase === 'READY' ? '准备阶段不计时，可直接开始实现，无需先提问。' : '提问不会自动保存草稿或延长作答时间。'}`">
        <Button
          variant="ghost"
          size="small"
          aria-label="面试官使用说明"
        >
          ?
        </Button>
      </Tooltip>
    </header>
    <p
      v-if="phase === 'READY'"
      class="interviewer-phase-hint"
    >
      现在不计时；可直接开始实现，无需先提问。
    </p>
    <p
      v-if="records.isPending.value"
      role="status"
      class="interviewer-note"
    >
      正在读取面试官对话…
    </p>
    <div
      v-else-if="records.isError.value"
      role="alert"
      class="interviewer-error-box"
    >
      <p>无法读取对话，未发起新的模型请求。</p>
      <Button
        variant="secondary"
        size="small"
        @click="records.refetch()"
      >
        重新读取
      </Button>
    </div>
    <p
      v-else-if="!records.data.value?.length"
      class="interviewer-empty"
    >
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
        <p
          role="status"
          class="interviewer-task-state"
        >
          {{ labels[task.state] }}
        </p>
        <p
          v-if="task.answer"
          class="interviewer-answer"
        >
          {{ task.answer }}
        </p>
        <p
          v-if="task.state === 'FAILED'"
          class="interviewer-task-failed"
        >
          {{ task.reason === 'INTERVIEWER_RESULT_UNKNOWN' ? '调用结果未知，不会自动重新调用。' : '本次回答未完成或授权已改变。已保存提问，可核对状态后重新提问。' }}
        </p>
        <details class="interviewer-feedback">
          <summary>对此问答授权反馈</summary>
          <FeedbackComposer
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
        size="small"
        @click="before = ''"
      >
        返回最新提问
      </Button>
      <Button
        v-if="records.data.value?.length === 20"
        variant="ghost"
        size="small"
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
        placeholder="关于需求边界或验收规则的问题…"
        :readonly="blocked"
        aria-describedby="interviewer-help"
      />
      <p id="interviewer-help">
        {{ disabled ? '当前阶段暂停提问，历史仍可查看。' : !credential ? '取得本页操作权后可提问。' : running ? '请等待当前回答结束后再提问。' : '最多 4 KiB 文字。提问不会自动保存草稿或延长作答时间。' }}
      </p>
      <p
        v-if="command.error.value"
        role="alert"
        class="interviewer-task-failed"
      >
        {{ command.error.value }}
      </p>
      <Button
        v-if="command.uncertain.value"
        type="button"
        variant="secondary"
        size="small"
        :disabled="command.pending.value"
        @click="command.retry"
      >
        核对原提问
      </Button>
      <Button
        v-else
        type="submit"
        variant="secondary"
        size="small"
        :disabled="blocked || !text.trim()"
      >
        {{ command.pending.value ? '正在提交…' : '发送给面试官' }}
      </Button>
    </form>
  </section>
</template>

<style scoped>
.interviewer {
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: 12px;
  box-shadow: var(--oc-hairline-top), var(--oc-shadow-xs);
  padding: 16px;
  min-width: 0;
  display: grid;
  gap: 12px;
}

.interviewer header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--oc-border);
  padding-bottom: 10px;
}

.interviewer-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.interviewer h2 {
  margin: 0;
  font-size: 14.5px;
  font-weight: 600;
}

.interviewer-badge {
  font-size: 11px;
  font-weight: 550;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  color: var(--oc-fg-muted);
}

.interviewer-phase-hint,
.interviewer-note,
.interviewer-empty {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
  line-height: 1.55;
}

.interviewer-error-box {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-danger-bg);
  border: 1px solid var(--oc-danger-border);
  color: var(--oc-danger);
  font-size: 12.5px;
  display: grid;
  gap: 8px;
}

.interviewer-error-box p {
  margin: 0;
}

#interviewer-help {
  color: var(--oc-fg-muted);
  font-size: 12px;
  line-height: 1.55;
  margin: 0;
}

.interviewer-history {
  list-style: none;
  padding: 0;
  margin: 0;
  max-height: 40dvh;
  min-height: 60px;
  overflow: auto;
  display: grid;
  gap: 10px;
}

.interviewer-history li {
  padding: 12px;
  border-radius: 10px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 6px;
}

.interviewer-question {
  margin: 0;
  font-size: 13px;
  font-weight: 550;
  color: var(--oc-fg);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.interviewer-task-state {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-accent);
}

.interviewer-answer {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: rgba(243, 244, 242, 0.88);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.interviewer-task-failed {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-danger);
}

.interviewer-feedback {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.interviewer-feedback summary {
  cursor: pointer;
}

.interviewer form {
  display: grid;
  gap: 8px;
}

.interviewer label {
  font-size: 12.5px;
  font-weight: 550;
  color: var(--oc-fg);
}

.interviewer textarea {
  color: var(--oc-fg);
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border-input);
  border-radius: var(--radius-control);
  padding: 10px 12px;
  font-size: 13px;
  resize: vertical;
  transition: border-color 180ms var(--oc-ease), box-shadow 180ms var(--oc-ease);
}

.interviewer textarea:focus-visible {
  outline: 2px solid var(--oc-accent);
  outline-offset: 2px;
  border-color: var(--oc-accent);
  box-shadow: 0 0 0 3px var(--oc-accent-glow);
}

.interviewer-pagination {
  display: flex;
  gap: 8px;
}
</style>
