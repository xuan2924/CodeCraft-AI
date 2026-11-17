<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { HelpCircle, MessageSquare } from '@lucide/vue';
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
const records = useQuery({
  queryKey: computed(() => [...prefix.value, before.value]),
  queryFn: ({ signal }) => api.history(props.owner, props.exerciseId, before.value, signal),
  retry: false,
  gcTime: 0,
  refetchInterval: (query) => !before.value && query.state.data?.some((item) => ['QUEUED', 'RUNNING'].includes(item.state)) ? 1000 : false,
});
const command = useCommand<api.InterviewerTask>(scope, () => {
  text.value = '';
  before.value = '';
  void client.invalidateQueries({ queryKey: prefix.value });
}, {
  definitiveRejection: (error) => error instanceof ApiError && [400, 401, 403, 404, 409, 410, 429].includes(error.status),
  identityFailure: (error) => error instanceof ApiError && [401, 403].includes(error.status),
  errorMessage: message,
}, () => emit('unauthorized'));

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

const modePlaceholders: Record<api.InterviewerMode, string> = {
  CLARIFY: '询问需求边界、输入输出或验收约定…',
  HINT: '描述卡住的模块，按主题获取渐进提示…',
  DEFENSE: '输入针对当前已保存代码想追问的设计点…',
};
const labels: Record<string, string> = { QUEUED: '等待回答', RUNNING: '回答中', SUCCEEDED: '已回答', FAILED: '未完成' };
</script>

<template>
  <section
    class="interviewer"
    aria-label="面试官需求澄清"
  >
    <header class="interviewer-head">
      <div class="interviewer-title">
        <h2>面试官</h2>
        <span
          v-if="stage"
          class="stage-pill"
          :data-tone="stage === 'CHALLENGE' ? 'warning' : 'accent'"
        >
          {{ stage === 'CHALLENGE' ? '挑战阶段' : '基础阶段' }}
        </span>
      </div>
      <Tooltip :text="`只答疑、给提示与做代码拷问，不直接改代码。平台承担调用费用，与 Copilot 对话分离。${phase === 'READY' ? '准备阶段不计时。' : '提问不延长作答时间。'}`">
        <button
          type="button"
          class="help-btn"
          aria-label="面试官使用说明"
        >
          <HelpCircle :size="13" />
        </button>
      </Tooltip>
    </header>

    <div class="interviewer-stream">
      <p
        v-if="records.isPending.value"
        role="status"
        class="stream-note"
      >
        正在读取面试官对话…
      </p>
      <div
        v-else-if="records.isError.value"
        role="alert"
        class="stream-error"
      >
        <p>无法读取对话，未发起新的模型请求。</p>
        <Button
          variant="ghost"
          size="small"
          @click="records.refetch()"
        >
          重试
        </Button>
      </div>
      <div
        v-else-if="!records.data.value?.length"
        class="interviewer-empty"
      >
        <MessageSquare
          :size="20"
          class="empty-icon"
          aria-hidden="true"
        />
        <p class="empty-caption">
          尚无提问。可以先核对需求，也可以直接开始实现。
        </p>
        <div class="interviewer-empty-chips">
          <button
            v-for="prompt in ['请确认输入边界条件与空值处理约定？', '精度、四舍五入和异常状态如何约束？', '实现时有哪些不能破坏的接口或结构要求？']"
            :key="prompt"
            type="button"
            class="prompt-chip"
            :disabled="blocked"
            @click="text = prompt"
          >
            {{ prompt }}
          </button>
        </div>
      </div>
      <ol
        v-else
        class="interviewer-history"
      >
        <li
          v-for="task in records.data.value"
          :key="task.id"
          class="qa-item"
        >
          <div class="qa-head">
            <div class="qa-badges">
              <span
                class="meta-pill"
                :data-tone="task.mode === 'HINT' ? 'warning' : task.mode === 'DEFENSE' ? 'accent' : undefined"
              >
                {{ modeLabel(task) }}
              </span>
              <span
                v-if="task.stage"
                class="meta-pill font-mono"
              >
                {{ task.stage }}
              </span>
            </div>
            <p
              role="status"
              class="qa-state"
            >
              {{ labels[task.state] }}
            </p>
          </div>
          <p class="qa-question">
            {{ task.question }}
          </p>
          <p
            v-if="task.answer"
            class="qa-answer"
          >
            {{ task.answer }}
          </p>
          <p
            v-if="task.state === 'FAILED'"
            class="qa-failed"
          >
            {{ task.reason === 'INTERVIEWER_RESULT_UNKNOWN' ? '调用结果未知，不会自动重新调用。' : '本次回答未完成或授权已改变。' }}
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

      <div
        v-if="before || records.data.value?.length === 20"
        class="interviewer-pagination"
      >
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
    </div>

    <form
      class="composer-dock"
      @submit.prevent="send"
    >
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
          text="进入实施阶段后解锁代码拷问"
        >
          <span
            tabindex="0"
            class="mode-pill-lock"
            aria-label="代码拷问（进入实施阶段后解锁）"
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

      <div
        v-if="mode === 'HINT'"
        class="interviewer-hint-topic"
      >
        <div class="topic-row">
          <input
            id="interviewer-topic"
            v-model="topic"
            type="text"
            class="font-mono"
            aria-label="提示主题"
            maxlength="64"
            placeholder="主题 (默认 general)"
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
          class="qa-failed"
        >
          提示主题仅支持 1~64 位 ASCII 字母、数字、下划线或连字符。
        </p>
        <p
          v-else-if="activeTopicHintLevel > 0"
          class="hint-level-note"
        >
          当前可见记录该主题已获提示至 L{{ activeTopicHintLevel }}（上限 L3）
        </p>
      </div>

      <div class="ui-capsule interviewer-capsule">
        <label
          for="interviewer-question"
          class="sr-only"
        >向面试官提问</label>
        <textarea
          id="interviewer-question"
          v-model="text"
          rows="2"
          maxlength="4096"
          class="composer-input"
          :placeholder="modePlaceholders[mode]"
          :readonly="blocked"
          aria-describedby="interviewer-help"
        />
        <div class="capsule-footer">
          <span
            v-if="disabled || !credential || running"
            id="interviewer-help"
            class="capsule-status"
          >
            {{ disabled ? '当前阶段暂停提问' : !credential ? '未取得操作权' : '等待回答结束' }}
          </span>
          <span
            v-else
            id="interviewer-help"
            class="capsule-status"
          >平台承担费用</span>

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
            variant="primary"
            size="small"
            :disabled="blocked || !text.trim() || !topicValid"
          >
            {{ command.pending.value ? '提交中…' : '发送给面试官' }}
          </Button>
        </div>
      </div>

      <p
        v-if="command.error.value"
        role="alert"
        class="qa-failed"
      >
        {{ command.error.value }}
      </p>
    </form>
  </section>
</template>

<style scoped>
.interviewer {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  min-width: 0;
}

.interviewer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--oc-border);
  flex-shrink: 0;
}

.interviewer-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.interviewer-title h2 {
  margin: 0;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--oc-fg-muted);
}

.stage-pill {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--oc-surface-2);
  color: var(--oc-fg-muted);
  border: 1px solid var(--oc-border);
}

.stage-pill[data-tone="warning"] {
  color: var(--oc-warning);
  border-color: rgba(245, 158, 11, 0.35);
}

.help-btn {
  display: inline-flex;
  align-items: center;
  padding: 3px;
  border: 0;
  background: transparent;
  color: var(--oc-fg-dim);
  cursor: pointer;
}

.help-btn:hover {
  color: var(--oc-fg-muted);
}

.interviewer-stream {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.stream-note {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.stream-error {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-danger-bg);
  color: var(--oc-danger);
  font-size: 12.5px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.stream-error p {
  margin: 0;
}

.interviewer-empty {
  margin: auto 0;
  display: grid;
  justify-items: center;
  text-align: center;
  gap: 10px;
  padding: 16px 8px;
}

.empty-icon {
  color: var(--oc-fg-dim);
}

.empty-caption {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.interviewer-empty-chips {
  display: grid;
  gap: 6px;
  width: 100%;
}

.prompt-chip {
  text-align: left;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid var(--oc-border);
  background: var(--oc-surface);
  color: var(--oc-fg-muted);
  font-size: 12px;
  cursor: pointer;
}

.prompt-chip:hover:not(:disabled) {
  color: var(--oc-fg);
  border-color: var(--oc-border-strong);
  background: var(--oc-surface-2);
}

.interviewer-history {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 14px;
}

.qa-item {
  display: grid;
  gap: 6px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--oc-border);
}

.qa-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.qa-badges {
  display: flex;
  align-items: center;
  gap: 6px;
}

.meta-pill {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--oc-surface-2);
  color: var(--oc-fg-muted);
}

.qa-state {
  margin: 0;
  font-size: 11px;
  color: var(--oc-fg-dim);
}

.qa-question {
  margin: 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--oc-surface-2);
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--oc-fg);
  white-space: pre-wrap;
  word-break: break-word;
}

.qa-answer {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--oc-fg);
  white-space: pre-wrap;
  word-break: break-word;
}

.qa-failed {
  margin: 0;
  font-size: 12px;
  color: var(--oc-danger);
}

.interviewer-feedback summary {
  font-size: 11.5px;
  color: var(--oc-fg-dim);
  cursor: pointer;
}

.interviewer-pagination {
  display: flex;
  gap: 8px;
  justify-content: center;
}

.composer-dock {
  flex-shrink: 0;
  padding: 8px 12px 12px;
  border-top: 1px solid var(--oc-border);
  display: grid;
  gap: 8px;
  background: var(--oc-bg-subtle);
}

.interviewer-modes {
  display: flex;
  align-items: center;
  gap: 4px;
}

.mode-pill {
  border: 0;
  border-radius: 6px;
  padding: 4px 8px;
  font-size: 11.5px;
  background: transparent;
  color: var(--oc-fg-muted);
  cursor: pointer;
}

.mode-pill:hover:not(:disabled) {
  color: var(--oc-fg);
  background: rgba(255, 255, 255, 0.05);
}

.mode-pill[aria-pressed="true"] {
  background: var(--oc-surface-2);
  color: var(--oc-fg);
  font-weight: 500;
}

.mode-pill:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.interviewer-hint-topic {
  display: grid;
  gap: 6px;
}

.topic-row {
  display: grid;
  gap: 6px;
}

.topic-row input {
  width: 100%;
  min-width: 0;
  padding: 5px 8px;
  font-size: 12px;
}

.topic-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.topic-pill {
  border: 1px solid var(--oc-border);
  border-radius: 999px;
  padding: 2px 8px;
  font-size: 11px;
  background: transparent;
  color: var(--oc-fg-muted);
  cursor: pointer;
}

.topic-pill[aria-pressed="true"] {
  background: var(--oc-surface-2);
  color: var(--oc-fg);
  border-color: var(--oc-border-strong);
}

.hint-level-note {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-fg-muted);
}

.interviewer-capsule {
  padding: 8px 10px;
  display: grid;
  gap: 6px;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
}

.composer-input {
  width: 100%;
  min-width: 0;
  border: 0 !important;
  background: transparent;
  color: var(--oc-fg);
  padding: 0;
  font-size: 12.5px;
  line-height: 1.5;
  resize: none;
  outline: none;
}

.capsule-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.capsule-status {
  font-size: 11px;
  color: var(--oc-fg-dim);
}
</style>
