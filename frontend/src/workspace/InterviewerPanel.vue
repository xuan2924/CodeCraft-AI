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

const props = defineProps<{ owner: string; exerciseId: string; credential: string; disabled: boolean; phase: string; stage?: api.InterviewerStage }>();
const emit = defineEmits<{ unauthorized: [] }>();
const client = useQueryClient();
const text = ref('');
const before = ref('');
const mode = ref<api.InterviewerMode>('CLARIFY');
const topic = ref('');
const quickTopics = ['general', 'boundary', 'concurrency', 'precision'] as const;
const scope = () => `${props.owner}/${props.exerciseId}`;
watch(scope, () => { text.value = ''; before.value = ''; mode.value = 'CLARIFY'; topic.value = ''; }, { flush: 'sync' });
watch(() => props.phase, (phase) => { if (phase !== 'ACTIVE' && mode.value === 'DEFENSE') mode.value = 'CLARIFY'; }, { flush: 'sync' });
const prefix = computed(() => ['interviewer', props.owner, props.exerciseId]);
const records = useQuery({ queryKey: computed(() => [...prefix.value, before.value]),
  queryFn: ({ signal }) => api.history(props.owner, props.exerciseId, before.value, signal), retry: false, gcTime: 0,
  refetchInterval: (query) => !before.value && query.state.data?.some((item) => ['QUEUED', 'RUNNING'].includes(item.state)) ? 1000 : false });
const command = useCommand<api.InterviewerTask>(scope, () => {
  text.value = ''; before.value = ''; void client.invalidateQueries({ queryKey: prefix.value });
}, { definitiveRejection: (error) => error instanceof ApiError && [400, 401, 403, 404, 409, 410, 429].includes(error.status),
  identityFailure: (error) => error instanceof ApiError && [401, 403].includes(error.status), errorMessage: message }, () => emit('unauthorized'));
const running = computed(() => records.data.value?.some((item) => ['QUEUED', 'RUNNING'].includes(item.state)) ?? false);
const defenseAllowed = computed(() => props.phase === 'ACTIVE');
const cleanTopic = computed(() => topic.value.trim());
const topicValid = computed(() => mode.value !== 'HINT' || !cleanTopic.value || /^[A-Za-z0-9_-]{1,64}$/.test(cleanTopic.value));
const activeTopicHintLevel = computed(() => {
  const target = cleanTopic.value || 'general';
  let max = 0;
  for (const item of records.data.value ?? []) {
    if (item.mode === 'HINT' && item.state === 'SUCCEEDED' && (item.topic || 'general') === target && typeof item.hintLevel === 'number') {
      max = Math.max(max, item.hintLevel);
    }
  }
  return max;
});
const blocked = computed(() => props.disabled || !props.credential || command.pending.value || command.uncertain.value || running.value || !!before.value);
function send() {
  if (blocked.value || !text.value.trim() || !topicValid.value) return;
  if (mode.value === 'DEFENSE' && !defenseAllowed.value) return;
  const question = text.value;
  const owner = props.owner;
  const exercise = props.exerciseId;
  const selectedMode = mode.value;
  const selectedTopic = cleanTopic.value;
  void command.submit((key, signal) => selectedMode === 'CLARIFY' && !selectedTopic
    ? api.ask(owner, exercise, question, key, props.credential, signal)
    : api.ask(owner, exercise, question, key, props.credential, signal, {
      mode: selectedMode,
      ...(selectedMode === 'HINT' && selectedTopic ? { topic: selectedTopic } : {}),
    }));
}
function modeLabel(task: api.InterviewerTask): string {
  if (task.mode === 'HINT') {
    const level = task.hintLevel ? ` L${task.hintLevel}` : '';
    const topicPart = task.topic ? ` · ${task.topic}` : '';
    return `提示${level}${topicPart}`;
  }
  if (task.mode === 'DEFENSE') return '代码拷问';
  return '澄清';
}
const modeDescriptions: Record<api.InterviewerMode, string> = {
  CLARIFY: '询问业务边界、输入输出与验收约束',
  HINT: '按主题获取渐进式提示（L1 思路方向 → L2 模块定位 → L3 关键逻辑）',
  DEFENSE: '基于当前已保存代码发起设计与边界追问',
};
const modePlaceholders: Record<api.InterviewerMode, string> = {
  CLARIFY: '关于需求边界或验收规则的问题…',
  HINT: '描述你当前卡住的模块、边界或需要提示的思路…',
  DEFENSE: '输入想请面试官针对当前已保存代码拷问的关注点或设计取舍…',
};
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
        <span
          v-if="stage"
          class="ui-badge"
          :data-tone="stage === 'CHALLENGE' ? 'warning' : 'accent'"
        >
          {{ stage === 'CHALLENGE' ? '挑战阶段' : '基础阶段' }}
        </span>
      </div>
      <Tooltip :text="`只答疑、给提示与做代码拷问，不直接修改代码。费用由平台承担，与 Copilot 对话分开。${phase === 'READY' ? '准备阶段不计时，可直接开始实现，无需先提问。' : '提问不会自动保存草稿或延长作答时间。'}`">
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
        <div class="interviewer-card-head">
          <div class="interviewer-card-badges">
            <span
              class="ui-pill"
              :data-tone="task.mode === 'HINT' ? 'warning' : task.mode === 'DEFENSE' ? 'accent' : undefined"
            >
              {{ modeLabel(task) }}
            </span>
            <span
              v-if="task.stage"
              class="ui-badge"
            >
              {{ task.stage }}
            </span>
          </div>
          <p
            role="status"
            class="interviewer-task-state"
          >
            {{ labels[task.state] }}
          </p>
        </div>
        <p class="interviewer-question">
          你：{{ task.question }}
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
      <div
        class="interviewer-modes"
        role="group"
        aria-label="面试官互动模式"
      >
        <button
          type="button"
          class="mode-pill"
          :aria-pressed="mode === 'CLARIFY'"
          @click="mode = 'CLARIFY'"
        >
          需求澄清
        </button>
        <button
          type="button"
          class="mode-pill"
          :aria-pressed="mode === 'HINT'"
          @click="mode = 'HINT'"
        >
          提示锦囊
        </button>
        <Tooltip
          v-if="!defenseAllowed"
          text="进入实施阶段（ACTIVE）后可使用代码拷问"
        >
          <span
            tabindex="0"
            class="mode-pill-lock"
            aria-label="代码拷问（当前不可用：进入实施阶段后解锁）"
          >
            <button
              type="button"
              class="mode-pill"
              disabled
              :aria-pressed="false"
            >
              代码拷问
            </button>
          </span>
        </Tooltip>
        <button
          v-else
          type="button"
          class="mode-pill"
          :aria-pressed="mode === 'DEFENSE'"
          @click="mode = 'DEFENSE'"
        >
          代码拷问
        </button>
      </div>
      <p class="interviewer-mode-desc">
        {{ modeDescriptions[mode] }}
      </p>
      <div
        v-if="mode === 'HINT'"
        class="interviewer-hint-topic"
      >
        <label for="interviewer-topic">提示主题（可选，默认 general）</label>
        <div class="topic-row">
          <input
            id="interviewer-topic"
            v-model="topic"
            type="text"
            maxlength="64"
            placeholder="general"
            :readonly="blocked"
          >
          <div
            class="topic-pills"
            aria-label="常用提示主题"
          >
            <button
              v-for="item in quickTopics"
              :key="item"
              type="button"
              class="topic-pill"
              :aria-pressed="(cleanTopic || 'general') === item"
              :disabled="blocked"
              @click="topic = item"
            >
              {{ item }}
            </button>
          </div>
        </div>
        <p
          v-if="!topicValid"
          role="alert"
          class="interviewer-task-failed"
        >
          提示主题仅支持 1~64 位 ASCII 字母、数字、下划线或连字符。
        </p>
        <p
          v-else
          class="interviewer-hint-meta"
        >
          {{ activeTopicHintLevel > 0 ? `当前可见记录该主题已获提示至 L${activeTopicHintLevel}（上限 L3）` : '按主题独立递进：L1 思路方向 → L2 模块定位 → L3 关键逻辑' }}
        </p>
      </div>
      <label for="interviewer-question">向面试官提问</label>
      <textarea
        id="interviewer-question"
        v-model="text"
        rows="3"
        maxlength="4096"
        :placeholder="modePlaceholders[mode]"
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
        :disabled="blocked || !text.trim() || !topicValid"
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
  border-radius: var(--radius-panel);
  padding: 14px;
  min-width: 0;
  display: grid;
  gap: 10px;
}

.interviewer header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  border-bottom: 1px solid var(--oc-border);
  padding-bottom: 8px;
}

.interviewer-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.interviewer h2 {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
}

.interviewer-badge {
  font-size: 11px;
  font-weight: 550;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  color: var(--oc-fg-muted);
}

.interviewer-phase-hint,
.interviewer-note,
.interviewer-empty {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
  line-height: 1.5;
}

.interviewer-error-box {
  padding: 8px 10px;
  border-radius: var(--radius-control);
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
  font-size: 11.5px;
  line-height: 1.45;
  margin: 0;
}

.interviewer-history {
  list-style: none;
  padding: 0;
  margin: 0;
  max-height: 44dvh;
  min-height: 60px;
  overflow: auto;
  display: grid;
  gap: 8px;
}

.interviewer-history li {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 6px;
}

.interviewer-question {
  margin: 0;
  font-size: 12.5px;
  font-weight: 600;
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
  padding-left: 10px;
  border-left: 2px solid var(--oc-accent);
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--oc-fg);
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

.interviewer-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.interviewer-card-badges {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.interviewer-modes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.mode-pill-lock {
  display: inline-flex;
  border-radius: 999px;
}

.mode-pill,
.topic-pill {
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid var(--oc-border-strong);
  background: var(--oc-surface-2);
  color: var(--oc-fg-muted);
  font-size: 12px;
  font-weight: 550;
  cursor: pointer;
  transition: background-color 150ms var(--oc-ease), border-color 150ms var(--oc-ease), color 150ms var(--oc-ease);
}

.mode-pill:hover:not(:disabled),
.topic-pill:hover:not(:disabled) {
  color: var(--oc-fg);
  background: var(--oc-surface-hover);
  border-color: var(--oc-border-hover);
}

.mode-pill[aria-pressed="true"],
.topic-pill[aria-pressed="true"] {
  color: var(--oc-accent);
  background: var(--oc-accent-soft);
  border-color: var(--oc-border-accent);
}

.mode-pill:disabled,
.topic-pill:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.interviewer-mode-desc,
.interviewer-hint-meta {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-fg-muted);
  line-height: 1.45;
}

.interviewer-hint-topic {
  display: grid;
  gap: 6px;
  padding: 8px 10px;
  border-radius: var(--radius-control);
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
}

.topic-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.topic-row input {
  flex: 1 1 130px;
  min-width: 0;
  padding: 5px 9px;
  font-size: 12px;
  font-family: var(--oc-font-mono);
}

.topic-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.topic-pill {
  padding: 2px 8px;
  font-size: 11.5px;
  font-family: var(--oc-font-mono);
}

.interviewer-pagination {
  display: flex;
  gap: 8px;
}
</style>
