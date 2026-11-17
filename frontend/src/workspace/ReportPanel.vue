<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import Button from '../components/ui/Button.vue';
import StatePanel from '../components/ui/StatePanel.vue';
import WorkspaceHistory from './WorkspaceHistory.vue';
import FeedbackComposer from './FeedbackComposer.vue';
import { current, message } from './api';
import * as api from './reportApi';

const props = defineProps<{ owner: string; exerciseId: string }>();
const client = useQueryClient();
const root = computed(() => ['report', props.owner, props.exerciseId]);
const report = useQuery({
  queryKey: root,
  gcTime: 0,
  retry: false,
  queryFn: ({ signal }) => api.read(props.owner, props.exerciseId, signal),
  refetchInterval: query => query.state.data && ['QUEUED', 'PREPARING', 'RUNNING', 'NOT_READY'].includes(query.state.data.state) ? 2000 : false,
});
const exercise = useQuery({
  queryKey: computed(() => [...root.value, 'exercise']),
  gcTime: 0,
  retry: false,
  queryFn: ({ signal }) => current(signal, props.exerciseId, props.owner),
});

const text = ref('');
const selected = ref('');
const selectedHistoryChange = ref('');
const historyChange = computed(() => selectedHistoryChange.value || (selected.value.startsWith('change:') ? selected.value.slice(7) : ''));
const evidence = useQuery({
  queryKey: computed(() => [...root.value, 'evidence', report.data.value?.generation, selected.value]),
  gcTime: 0,
  retry: false,
  enabled: computed(() => !!selected.value && !!report.data.value && !report.isError.value),
  queryFn: ({ signal }) => api.detail(props.owner, props.exerciseId, report.data.value!.generation, selected.value, signal),
});

const command = useCommand<api.Report>(() => `${props.owner}/${props.exerciseId}`, value => {
  client.setQueryData(root.value, value);
  text.value = '';
  selected.value = '';
  selectedHistoryChange.value = '';
}, {
  definitiveRejection: error => error instanceof ApiError && [400, 401, 403, 404, 409, 410, 429].includes(error.status),
  errorMessage: message,
  identityFailure: () => false,
});

const busy = computed(() => command.pending.value || command.uncertain.value);
const reviewBytes = computed(() => new globalThis.TextEncoder().encode(text.value).length);
const validReview = computed(() => text.value.trim().length > 0 && reviewBytes.value <= 4096);

function review(skip: boolean) {
  if (busy.value || report.data.value?.state !== 'WAITING_REVIEW' || (!skip && !validReview.value)) return;
  const { owner, exerciseId } = props;
  const content = skip ? '' : text.value;
  void command.submit((key, signal) => api.review(owner, exerciseId, skip, content, key, signal));
}

function retry() {
  const generation = report.data.value?.generation;
  if (busy.value || !generation) return;
  const { owner, exerciseId } = props;
  void command.submit((key, signal) => api.retry(owner, exerciseId, generation, key, signal));
}

const downloading = ref(false);
const downloadError = ref('');
const lifetime = new globalThis.AbortController();
onScopeDispose(() => lifetime.abort());

async function pdf() {
  const generation = report.data.value?.generation;
  if (generation === undefined || downloading.value) return;
  downloading.value = true;
  downloadError.value = '';
  try {
    const blob = await api.pdf(props.owner, props.exerciseId, generation, lifetime.signal);
    if (lifetime.signal.aborted) return;
    if (generation !== report.data.value?.generation) throw new ApiError(409, 'REVISION_CONFLICT');
    const url = globalThis.URL.createObjectURL(blob);
    const anchor = globalThis.document.createElement('a');
    anchor.href = url;
    anchor.download = `codecraft-report-${generation}.pdf`;
    anchor.click();
    globalThis.setTimeout(() => globalThis.URL.revokeObjectURL(url), 0);
  } catch (error) {
    if (!lifetime.signal.aborted) downloadError.value = message(error);
  } finally {
    downloading.value = false;
  }
}

const dimensionNames: Record<string, string> = { CORRECTNESS: '实现正确性', QUALITY: '代码质量', VALIDATION: '验证能力', COLLABORATION: 'AI 协作' };
const weights: Record<string, number> = { CORRECTNESS: 35, QUALITY: 25, VALIDATION: 20, COLLABORATION: 20 };
const states: Record<string, string> = {
  WAITING_REVIEW: '等待回顾',
  NOT_READY: '等待最终冻结',
  QUEUED: '评价排队中',
  PREPARING: '正在整理证据',
  RUNNING: '正在评价',
  SUCCEEDED: '评价已生成',
  FAILED: '评价生成失败',
  UNKNOWN: '评价调用结果未知',
};
const verification: Record<string, string> = {
  PASSED: '最终验收通过',
  FAILED: '最终验收未通过',
  TIMED_OUT: '最终验收超时',
  UNAVAILABLE: '平台验收不可用',
  PENDING: '最终验收尚未结束',
};
const timelineKindLabels: Record<string, string> = {
  INTERVIEWER_ASKED: '面试官交互',
  IMPLEMENTATION_STARTED: '开始实现',
  WORKSPACE_CHANGED: '代码变更',
  TEST_COMPLETED: '测试完成',
  CHALLENGE_ENTERED: '进入挑战',
  EXERCISE_FROZEN: '演练冻结',
};

const traceMetrics = computed(() => {
  const trace = report.data.value?.contextTrace;
  if (!trace) return [];
  return [
    { label: '@引用次数', value: trace.referenceCount },
    { label: 'Skill 加载', value: trace.skillLoadCount },
    { label: 'Steer 纠偏', value: trace.steerCount },
    { label: 'BTW 旁支', value: trace.btwTaskCount },
    { label: '需求澄清', value: trace.interviewerClarifyCount },
    { label: '代码答辩', value: trace.interviewerDefenseCount },
  ];
});

const timelineItems = computed(() => {
  const items = report.data.value?.timeline ?? [];
  return [...items].sort((left, right) => Date.parse(left.timestamp) - Date.parse(right.timestamp));
});

const evidenceIdSet = computed(() => new Set((report.data.value?.evidence ?? []).map(item => item.id)));

function resolveTimelineEvidence(item: api.TimelineEntry): string | null {
  if (!item.sourceId) return null;
  const candidates: string[] = [];
  if (item.kind === 'WORKSPACE_CHANGED') candidates.push(`change:${item.sourceId}`);
  if (item.kind === 'INTERVIEWER_ASKED') candidates.push(`interviewer:${item.sourceId}`);
  if (item.kind === 'TEST_COMPLETED') candidates.push(`test:${item.sourceId}`);
  candidates.push(item.sourceId);
  return candidates.find(candidate => evidenceIdSet.value.has(candidate)) ?? null;
}

function openTimelineNode(item: api.TimelineEntry) {
  const evidenceId = resolveTimelineEvidence(item);
  if (evidenceId) selected.value = evidenceId;
  if (item.kind === 'WORKSPACE_CHANGED' && item.sourceId) selectedHistoryChange.value = item.sourceId;
}

function timelineTone(kind: string): 'accent' | 'warning' | undefined {
  if (kind === 'CHALLENGE_ENTERED' || kind === 'EXERCISE_FROZEN') return 'warning';
  if (kind === 'WORKSPACE_CHANGED' || kind === 'TEST_COMPLETED') return 'accent';
  return undefined;
}

function formatTime(timestamp: string): string {
  const parsed = new Date(timestamp);
  return Number.isFinite(parsed.getTime()) ? parsed.toLocaleString() : timestamp;
}
</script>

<template>
  <StatePanel
    v-if="report.isPending.value"
    kind="loading"
    title="正在读取报告"
    description="读取冻结结果…"
  />
  <StatePanel
    v-else-if="report.isError.value"
    kind="error"
    title="无法读取报告"
    :description="message(report.error.value)"
  >
    <Button
      variant="secondary"
      size="small"
      @click="report.refetch()"
    >
      重新读取
    </Button>
  </StatePanel>

  <div
    v-else-if="report.data.value"
    class="report-layout"
  >
    <section class="report-main">
      <header class="report-heading">
        <div class="heading-left">
          <h2>演练报告</h2>
          <p class="report-sub font-mono-num">
            {{ states[report.data.value.state] }} · 冻结修订 {{ report.data.value.finalRevision ?? '暂无' }}
          </p>
        </div>
        <div class="heading-actions">
          <span
            class="verdict-pill"
            role="status"
            :data-verdict="report.data.value.verification ?? 'PENDING'"
          >
            {{ verification[report.data.value.verification ?? 'PENDING'] }}
          </span>
          <Button
            variant="secondary"
            size="small"
            :disabled="report.data.value.finalRevision === null || downloading"
            @click="pdf"
          >
            {{ downloading ? '正在生成 PDF…' : '下载 PDF' }}
          </Button>
        </div>
      </header>

      <p
        v-if="report.data.value.verification === 'UNAVAILABLE'"
        class="muted-banner"
      >
        平台故障不视为代码失败。无法证实的分数留空，不构造总分。
      </p>

      <section
        v-if="report.data.value.state === 'WAITING_REVIEW'"
        class="surface-block review-form"
        aria-label="演练回顾"
      >
        <div class="review-head">
          <h3>回顾你的实现与取舍</h3>
          <span class="review-hint">提交后不可修改；亦可直接跳过</span>
        </div>
        <label
          for="report-review"
          class="sr-only"
        >实现思路、验证方法与改进点</label>
        <textarea
          id="report-review"
          v-model="text"
          rows="5"
          placeholder="总结核心思路、权衡取舍、验证方式或可改进点…"
          :disabled="busy"
          aria-describedby="review-limit"
        />
        <div class="review-foot">
          <span
            id="review-limit"
            class="byte-count font-mono-num"
          >{{ reviewBytes }} / 4096 B</span>
          <div class="actions">
            <Button
              variant="ghost"
              size="small"
              :disabled="busy"
              @click="review(true)"
            >
              跳过回顾并评价
            </Button>
            <Button
              variant="primary"
              size="small"
              :disabled="busy || !validReview"
              @click="review(false)"
            >
              提交回顾并评价
            </Button>
          </div>
        </div>
      </section>

      <template v-if="report.data.value.evaluation">
        <section class="surface-block conclusion">
          <div class="conclusion-score-col">
            <span class="sub-label">综合评价总分</span>
            <p class="total font-mono-num">
              {{ report.data.value.evaluation.total ?? '暂无' }}<span> / 100</span>
            </p>
            <div
              class="score-bar score-bar-lg"
              aria-hidden="true"
            >
              <div
                class="score-bar-fill"
                :style="{ width: `${report.data.value.evaluation.total !== null ? Math.min(100, Math.max(0, report.data.value.evaluation.total)) : 0}%` }"
              />
            </div>
          </div>
          <div class="conclusion-text-col">
            <h3>评价摘要</h3>
            <p>{{ report.data.value.evaluation.conclusion }}</p>
          </div>
        </section>

        <div class="surface-block ui-divide-list dimensions">
          <section
            v-for="dimension in report.data.value.evaluation.dimensions"
            :key="dimension.key"
            class="ui-divide-row dimension"
          >
            <div class="dimension-top">
              <div class="dim-left">
                <h3>{{ dimensionNames[dimension.key] }}</h3>
                <div
                  class="score-bar"
                  aria-hidden="true"
                >
                  <div
                    class="score-bar-fill"
                    :style="{ width: `${dimension.score !== null && weights[dimension.key] ? Math.min(100, Math.max(0, (dimension.score / weights[dimension.key]!) * 100)) : 0}%` }"
                  />
                </div>
              </div>
              <p class="score font-mono-num">
                {{ dimension.score ?? '暂无' }} <span>/ {{ weights[dimension.key] }}</span>
              </p>
            </div>
            <p class="dimension-feedback">
              {{ dimension.feedback }}
            </p>
            <div
              v-if="dimension.evidenceIds.length"
              class="evidence-links"
            >
              <Button
                v-for="id in dimension.evidenceIds"
                :key="id"
                variant="ghost"
                size="small"
                @click="selected = id"
              >
                证据 {{ id }}
              </Button>
            </div>
          </section>
        </div>
      </template>
      <p
        v-else-if="report.data.value.state !== 'WAITING_REVIEW'"
        class="muted-banner"
      >
        暂未生成 AI 评价。
      </p>

      <p
        v-if="report.data.value.reviewSkipped"
        class="muted-banner"
      >
        已跳过回顾。
      </p>

      <div
        v-if="['FAILED', 'UNKNOWN'].includes(report.data.value.state)"
        class="surface-block retry-area"
      >
        <span class="retry-reason">原因：{{ report.data.value.reason }}</span>
        <Button
          variant="primary"
          size="small"
          :disabled="busy"
          @click="retry"
        >
          明确重试评价
        </Button>
      </div>

      <p
        v-if="command.error.value || downloadError"
        role="alert"
        class="inline-alert"
      >
        {{ command.error.value || downloadError }}
      </p>
      <Button
        v-if="command.uncertain.value"
        variant="secondary"
        size="small"
        :disabled="command.pending.value"
        @click="command.retry"
      >
        核对原请求
      </Button>

      <section
        v-if="report.data.value.contextTrace"
        class="surface-block context-trace"
        aria-label="上下文工程与协作轨迹"
      >
        <h3>上下文工程与协作轨迹</h3>
        <dl class="trace-grid">
          <div
            v-for="metric in traceMetrics"
            :key="metric.label"
            class="trace-metric"
          >
            <dt>{{ metric.label }}</dt>
            <dd class="font-mono-num">
              {{ metric.value }}
            </dd>
          </div>
        </dl>
        <div
          v-if="report.data.value.contextTrace.hintsUsed?.length"
          class="trace-hints"
        >
          <span class="sub-label">分主题提示最高等级</span>
          <div class="trace-hint-badges">
            <span
              v-for="hint in report.data.value.contextTrace.hintsUsed"
              :key="hint.topic"
              class="hint-pill font-mono"
            >
              {{ hint.topic }} · L{{ hint.maxLevel }}
            </span>
          </div>
        </div>
      </section>

      <section
        v-if="timelineItems.length"
        class="surface-block session-timeline"
        aria-label="演练时间轴回放"
      >
        <div class="timeline-head">
          <h3>演练时间轴回放</h3>
          <span class="sub-label font-mono-num">{{ timelineItems.length }} 项</span>
        </div>
        <ol class="timeline-list">
          <li
            v-for="(item, index) in timelineItems"
            :key="`${item.timestamp}-${item.kind}-${item.sourceId ?? index}`"
            class="timeline-node"
          >
            <div class="timeline-meta">
              <span
                class="meta-badge"
                :data-tone="timelineTone(item.kind)"
              >
                {{ timelineKindLabels[item.kind] ?? item.kind }}
              </span>
              <span
                v-if="item.revision !== null && item.revision !== undefined"
                class="timeline-rev font-mono"
              >
                r{{ item.revision }}
              </span>
              <time
                :datetime="item.timestamp"
                class="timeline-time font-mono-num"
              >
                {{ formatTime(item.timestamp) }}
              </time>
            </div>
            <div class="timeline-body">
              <p class="timeline-title">
                {{ item.title }}
              </p>
              <p
                v-if="item.detail"
                class="timeline-detail"
              >
                {{ item.detail }}
              </p>
            </div>
            <div
              v-if="resolveTimelineEvidence(item) || (item.kind === 'WORKSPACE_CHANGED' && item.sourceId && exercise.data.value)"
              class="timeline-actions"
            >
              <Button
                variant="ghost"
                size="small"
                @click="openTimelineNode(item)"
              >
                {{ resolveTimelineEvidence(item) ? '查看关联证据' : '查看变更 Diff' }}
              </Button>
            </div>
          </li>
        </ol>
      </section>

      <details
        v-if="exercise.data.value"
        class="fold-block"
        :open="!!historyChange"
      >
        <summary>查看只读代码修改历史与 Diff</summary>
        <WorkspaceHistory
          :owner="owner"
          :exercise="exercise.data.value"
          :initial-change="historyChange"
          credential=""
          disabled
        />
      </details>

      <details
        v-if="report.data.value.reportId"
        class="fold-block"
      >
        <summary>对此评价授权反馈</summary>
        <FeedbackComposer
          :key="`${report.data.value.reportId}/${report.data.value.generation}`"
          :owner="owner"
          :exercise-id="exerciseId"
          target-type="REPORT"
          :target-id="report.data.value.reportId"
          :generation="report.data.value.generation"
          :choices="report.data.value.evidence"
        />
      </details>
    </section>

    <aside
      class="report-evidence"
      aria-label="报告证据目录"
    >
      <div class="evidence-head">
        <h2>证据目录</h2>
        <span class="sub-label font-mono-num">版本 {{ report.data.value.generation }} · 只读</span>
      </div>
      <p
        v-if="!report.data.value.evidence.length"
        class="muted-note"
      >
        尚未整理证据。
      </p>
      <ul
        v-else
        class="ui-divide-list evidence-list"
      >
        <li
          v-for="item in report.data.value.evidence"
          :key="item.id"
          class="ui-divide-row evidence-row"
        >
          <button
            type="button"
            class="evidence-button font-mono"
            :aria-pressed="selected === item.id"
            @click="selected = item.id"
          >
            {{ item.kind }} · {{ item.source }}
          </button>
          <p class="evidence-summary">
            {{ item.summary }}{{ item.truncated ? '（已截断）' : '' }}
          </p>
        </li>
      </ul>
      <p
        v-if="selected && evidence.isPending.value"
        role="status"
        class="muted-note"
      >
        正在读取证据…
      </p>
      <p
        v-if="evidence.isError.value"
        role="alert"
        class="inline-alert"
      >
        {{ message(evidence.error.value) }}
      </p>
      <section
        v-if="evidence.data.value && !evidence.isError.value"
        class="evidence-viewer"
        aria-label="证据原文"
      >
        <h3 class="font-mono">
          {{ evidence.data.value.source }}
        </h3>
        <pre class="font-mono">{{ evidence.data.value.content }}</pre>
      </section>
    </aside>
  </div>
</template>

<style scoped>
.report-layout {
  max-width: 1120px;
  margin: 0 auto;
  padding: 28px 24px 64px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 24px;
  align-items: start;
  min-width: 0;
}

.report-main {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.report-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.heading-left h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.report-sub {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.heading-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.verdict-pill {
  font-size: 11.5px;
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  color: var(--oc-fg-muted);
}

.verdict-pill[data-verdict="PASSED"] {
  color: #34d399;
  border-color: rgba(52, 211, 153, 0.35);
}

.verdict-pill[data-verdict="FAILED"],
.verdict-pill[data-verdict="TIMED_OUT"] {
  color: var(--oc-danger);
  border-color: var(--oc-danger-border);
}

.muted-banner {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.surface-block {
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  padding: 16px;
  display: grid;
  gap: 12px;
  min-width: 0;
}

.ui-divide-list.surface-block {
  padding: 0;
  gap: 0;
}

.review-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.review-head h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.review-hint,
.sub-label,
.muted-note {
  font-size: 12px;
  color: var(--oc-fg-dim);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
}

.review-form textarea {
  width: 100%;
  min-width: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-bg-input);
  color: var(--oc-fg);
  border: 1px solid var(--oc-border-input);
  font-size: 13px;
  line-height: 1.55;
  resize: vertical;
}

.review-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.byte-count {
  font-size: 12px;
  color: var(--oc-fg-dim);
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.conclusion {
  grid-template-columns: 180px minmax(0, 1fr);
  gap: 20px;
  align-items: center;
}

.conclusion-score-col {
  display: grid;
  gap: 6px;
}

.total {
  margin: 0;
  font-size: 28px;
  font-weight: 600;
  line-height: 1.1;
}

.total span {
  font-size: 14px;
  color: var(--oc-fg-dim);
  font-weight: 400;
}

.score-bar {
  height: 4px;
  border-radius: 999px;
  background: var(--oc-surface-2);
  overflow: hidden;
}

.score-bar-lg {
  height: 6px;
}

.score-bar-fill {
  height: 100%;
  background: var(--oc-accent);
  border-radius: 999px;
}

.conclusion-text-col h3,
.context-trace h3,
.timeline-head h3 {
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--oc-fg-muted);
}

.conclusion-text-col p {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.dimension {
  padding: 14px 16px;
  display: grid;
  gap: 8px;
}

.dimension-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.dim-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 0;
}

.dim-left h3 {
  margin: 0;
  font-size: 13.5px;
  font-weight: 500;
  min-width: 90px;
}

.dim-left .score-bar {
  flex: 1;
  max-width: 180px;
}

.score {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.score span {
  font-size: 12px;
  color: var(--oc-fg-dim);
  font-weight: 400;
}

.dimension-feedback {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--oc-fg-muted);
  white-space: pre-wrap;
  word-break: break-word;
}

.evidence-links {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.retry-area {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.retry-reason {
  font-size: 12.5px;
  color: var(--oc-danger);
}

.inline-alert {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-danger-bg);
  color: var(--oc-danger);
  font-size: 12.5px;
}

.trace-grid {
  margin: 0;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.trace-metric {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-bg-subtle);
  display: grid;
  gap: 2px;
}

.trace-metric dt {
  font-size: 11.5px;
  color: var(--oc-fg-dim);
}

.trace-metric dd {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.trace-hints {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.trace-hint-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.hint-pill {
  font-size: 11.5px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  color: var(--oc-warning);
}

.timeline-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.timeline-list {
  list-style: none;
  margin: 0;
  padding: 0 0 0 14px;
  border-left: 1px solid var(--oc-border-strong);
  display: grid;
  gap: 14px;
}

.timeline-node {
  display: grid;
  gap: 4px;
  position: relative;
}

.timeline-node::before {
  content: '';
  position: absolute;
  left: -18px;
  top: 6px;
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: var(--oc-border-strong);
}

.timeline-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.meta-badge {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--oc-surface-2);
  color: var(--oc-fg-muted);
}

.timeline-rev,
.timeline-time {
  font-size: 11.5px;
  color: var(--oc-fg-dim);
}

.timeline-title {
  margin: 0;
  font-size: 13px;
  font-weight: 500;
}

.timeline-detail {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.fold-block {
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  padding: 12px 16px;
}

.fold-block summary {
  font-size: 13px;
  font-weight: 500;
  color: var(--oc-fg-muted);
  cursor: pointer;
}

.fold-block summary:hover {
  color: var(--oc-fg);
}

.report-evidence {
  position: sticky;
  top: 20px;
  padding: 16px;
  border-radius: 10px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 12px;
  min-width: 0;
}

.evidence-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.evidence-head h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.evidence-list {
  border-radius: 8px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  overflow: hidden;
}

.evidence-row {
  padding: 10px 12px;
  display: grid;
  gap: 4px;
}

.evidence-button {
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--oc-accent);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.evidence-button[aria-pressed="true"] {
  font-weight: 600;
  text-decoration: underline;
}

.evidence-summary {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
  line-height: 1.45;
}

.evidence-viewer {
  display: grid;
  gap: 6px;
}

.evidence-viewer h3 {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.evidence-viewer pre {
  margin: 0;
  max-height: 360px;
  overflow: auto;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-bg);
  border: 1px solid var(--oc-border);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: normal;
  overflow-wrap: break-word;
}

@media (max-width: 960px) {
  .report-layout {
    grid-template-columns: 1fr;
    padding: 20px 16px 40px;
  }
  .report-evidence {
    position: static;
  }
  .conclusion {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 600px) {
  .trace-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
