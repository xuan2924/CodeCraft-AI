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
const report = useQuery({ queryKey: root, gcTime: 0, retry: false, queryFn: ({ signal }) => api.read(props.owner, props.exerciseId, signal),
  refetchInterval: query => query.state.data && ['QUEUED', 'PREPARING', 'RUNNING', 'NOT_READY'].includes(query.state.data.state) ? 2000 : false });
const exercise = useQuery({ queryKey: computed(() => [...root.value, 'exercise']), gcTime: 0, retry: false, queryFn: ({ signal }) => current(signal, props.exerciseId, props.owner) });
const text = ref('');
const selected = ref('');
const historyChange = computed(() => selected.value.startsWith('change:') ? selected.value.slice(7) : '');
const evidence = useQuery({ queryKey: computed(() => [...root.value, 'evidence', report.data.value?.generation, selected.value]), gcTime: 0, retry: false,
  enabled: computed(() => !!selected.value && !!report.data.value && !report.isError.value),
  queryFn: ({ signal }) => api.detail(props.owner, props.exerciseId, report.data.value!.generation, selected.value, signal) });
const command = useCommand<api.Report>(() => `${props.owner}/${props.exerciseId}`, value => { client.setQueryData(root.value, value); text.value = ''; selected.value = ''; }, {
  definitiveRejection: error => error instanceof ApiError && [400, 401, 403, 404, 409, 410, 429].includes(error.status), errorMessage: message, identityFailure: () => false });
const busy = computed(() => command.pending.value || command.uncertain.value);
const reviewBytes = computed(() => new globalThis.TextEncoder().encode(text.value).length);
const validReview = computed(() => text.value.trim().length > 0 && reviewBytes.value <= 4096);
function review(skip: boolean) {
  if (busy.value || report.data.value?.state !== 'WAITING_REVIEW' || !skip && !validReview.value) return;
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
  downloading.value = true; downloadError.value = '';
  try {
    const blob = await api.pdf(props.owner, props.exerciseId, generation, lifetime.signal);
    if (lifetime.signal.aborted) return;
    if (generation !== report.data.value?.generation) throw new ApiError(409, 'REVISION_CONFLICT');
    const url = globalThis.URL.createObjectURL(blob);
    const anchor = globalThis.document.createElement('a');
    anchor.href = url; anchor.download = `codecraft-report-${generation}.pdf`; anchor.click();
    globalThis.setTimeout(() => globalThis.URL.revokeObjectURL(url), 0);
  } catch (error) { if (!lifetime.signal.aborted) downloadError.value = message(error); }
  finally { downloading.value = false; }
}
const dimensionNames: Record<string, string> = { CORRECTNESS: '实现正确性', QUALITY: '代码质量', VALIDATION: '验证能力', COLLABORATION: 'AI 协作' };
const weights: Record<string, number> = { CORRECTNESS: 35, QUALITY: 25, VALIDATION: 20, COLLABORATION: 20 };
const states: Record<string, string> = { WAITING_REVIEW: '等待回顾', NOT_READY: '等待最终冻结', QUEUED: '评价排队中', PREPARING: '正在整理证据', RUNNING: '正在评价', SUCCEEDED: '评价已生成', FAILED: '评价生成失败', UNKNOWN: '评价调用结果未知' };
const verification: Record<string, string> = { PASSED: '最终验收通过', FAILED: '最终验收未通过', TIMED_OUT: '最终验收超时', UNAVAILABLE: '平台验收不可用', PENDING: '最终验收尚未结束' };
</script>
<template>
  <StatePanel
    v-if="report.isPending.value"
    kind="loading"
    title="正在读取报告"
    description="读取冻结结果，不会触发新的评价。"
  />
  <StatePanel
    v-else-if="report.isError.value"
    kind="error"
    title="无法读取报告"
    :description="message(report.error.value)"
  >
    <Button
      variant="secondary"
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
        <div><h2>演练报告</h2><p>{{ states[report.data.value.state] }} · 冻结修订 {{ report.data.value.finalRevision ?? '暂无' }}</p></div>
        <Button
          variant="secondary"
          :disabled="report.data.value.finalRevision === null || downloading"
          @click="pdf"
        >
          {{ downloading ? '正在生成 PDF…' : '下载 PDF' }}
        </Button>
      </header>
      <p
        class="verification"
        role="status"
      >
        {{ verification[report.data.value.verification ?? 'PENDING'] }}
      </p>
      <p v-if="report.data.value.verification === 'UNAVAILABLE'">
        平台故障不视为代码失败。无法证实的分数留空，不构造总分。
      </p>
      <section
        v-if="report.data.value.state === 'WAITING_REVIEW'"
        class="review-form"
        aria-label="演练回顾"
      >
        <h3>回顾你的实现与取舍</h3><p>提交后不能修改。自述补充上下文，但不能代替代码、测试或协作证据；也可以直接跳过，不自动扣分。</p>
        <label for="report-review">实现思路、验证方法与改进点</label><textarea
          id="report-review"
          v-model="text"
          rows="6"
          :disabled="busy"
          aria-describedby="review-limit"
        />
        <p id="review-limit">
          最多 4 KiB UTF-8 文本。当前 {{ reviewBytes }} 字节。
        </p>
        <div class="actions">
          <Button
            variant="primary"
            :disabled="busy || !validReview"
            @click="review(false)"
          >
            提交回顾并评价
          </Button><Button
            variant="secondary"
            :disabled="busy"
            @click="review(true)"
          >
            跳过回顾并评价
          </Button>
        </div>
      </section>
      <template v-if="report.data.value.evaluation">
        <section class="conclusion">
          <div class="conclusion-score-col">
            <span class="conclusion-label">综合评价总分</span>
            <p class="total">
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
        <div class="dimensions">
          <section
            v-for="dimension in report.data.value.evaluation.dimensions"
            :key="dimension.key"
            class="dimension"
          >
            <div class="dimension-top">
              <h3>{{ dimensionNames[dimension.key] }}</h3>
              <p class="score">
                {{ dimension.score ?? '暂无' }} <span>/ {{ weights[dimension.key] }}</span>
              </p>
            </div>
            <div
              class="score-bar"
              aria-hidden="true"
            >
              <div
                class="score-bar-fill"
                :style="{ width: `${dimension.score !== null && weights[dimension.key] ? Math.min(100, Math.max(0, (dimension.score / weights[dimension.key]!) * 100)) : 0}%` }"
              />
            </div>
            <p class="dimension-feedback">
              {{ dimension.feedback }}
            </p>
            <div class="evidence-links">
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
      <p v-else-if="report.data.value.state !== 'WAITING_REVIEW'">
        暂未生成可发布的 AI 评价。已有最终验收结果保留，刷新页面不会重新调用模型。
      </p>
      <p v-if="report.data.value.reviewSkipped">
        已跳过回顾；跳过不自动扣分。未参与可选挑战不扣基础分。
      </p>
      <div
        v-if="['FAILED', 'UNKNOWN'].includes(report.data.value.state)"
        class="retry-area"
      >
        <p>原因：{{ report.data.value.reason }}。重试会开启新的报告版本；结果未知的调用不会自动重复派发。</p><Button
          variant="primary"
          :disabled="busy"
          @click="retry"
        >
          明确重试评价
        </Button>
      </div>
      <p
        v-if="command.error.value || downloadError"
        role="alert"
      >
        {{ command.error.value || downloadError }}
      </p>
      <Button
        v-if="command.uncertain.value"
        variant="secondary"
        :disabled="command.pending.value"
        @click="command.retry"
      >
        核对原请求
      </Button>
      <details
        v-if="exercise.data.value"
        :open="!!historyChange"
      >
        <summary>查看只读代码修改历史与 Diff</summary><WorkspaceHistory
          :owner="owner"
          :exercise="exercise.data.value"
          :initial-change="historyChange"
          credential=""
          disabled
        />
      </details>
      <details v-if="report.data.value.reportId">
        <summary>对此评价授权反馈</summary><FeedbackComposer
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
      <h2>证据目录</h2><p>版本 {{ report.data.value.generation }} · 只读回顾，不重放工具或测试。证据存在取样和保留范围限制。</p>
      <p v-if="!report.data.value.evidence.length">
        尚未整理证据。
      </p>
      <ul>
        <li
          v-for="item in report.data.value.evidence"
          :key="item.id"
        >
          <button
            class="evidence-button"
            :aria-pressed="selected === item.id"
            @click="selected = item.id"
          >
            {{ item.kind }} · {{ item.source }}
          </button><p>{{ item.summary }}{{ item.truncated ? '（原始内容已截断）' : '' }}</p>
        </li>
      </ul>
      <p
        v-if="selected && evidence.isPending.value"
        role="status"
      >
        正在读取证据…
      </p><p
        v-if="evidence.isError.value"
        role="alert"
      >
        {{ message(evidence.error.value) }}
      </p>
      <section
        v-if="evidence.data.value && !evidence.isError.value"
        aria-label="证据原文"
      >
        <h3>{{ evidence.data.value.source }}</h3><pre>{{ evidence.data.value.content }}</pre>
      </section>
    </aside>
  </div>
</template>
<style scoped>
.report-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.85fr) minmax(280px, 1fr);
  gap: 24px;
  align-items: start;
}

.report-main,
.report-evidence {
  min-width: 0;
}

.report-main {
  display: grid;
  gap: 20px;
}

.report-heading {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  flex-wrap: wrap;
  padding: 20px 24px;
  border-radius: var(--radius-panel);
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  box-shadow: var(--oc-hairline-top), var(--oc-shadow-xs);
}

.report-heading h2 {
  margin: 0 0 4px;
  font-size: 24px;
  font-weight: 650;
}

.report-heading p {
  margin: 0;
  font-size: 13px;
  color: var(--oc-fg-muted);
}

.actions {
  display: flex;
  justify-content: flex-start;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}

p {
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.verification {
  margin: 0;
  border-left: 3px solid var(--oc-accent);
  border-top: 1px solid var(--oc-border);
  border-right: 1px solid var(--oc-border);
  border-bottom: 1px solid var(--oc-border);
  border-radius: 10px;
  padding: 12px 16px;
  background: var(--oc-surface);
  font-size: 13.5px;
  font-weight: 550;
}

.review-form,
.conclusion,
.retry-area {
  margin: 0;
  padding: 24px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-panel);
  box-shadow: var(--oc-hairline-top), var(--oc-shadow-sm);
}

.conclusion {
  display: grid;
  grid-template-columns: minmax(180px, 0.7fr) minmax(0, 1.5fr);
  gap: 20px;
  align-items: center;
  background: var(--oc-surface);
}

.conclusion-score-col {
  display: grid;
  gap: 6px;
  padding-right: 20px;
  border-right: 1px solid var(--oc-border);
}

.conclusion-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--oc-fg-muted);
}

.conclusion-text-col {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.conclusion h3,
.review-form h3 {
  margin: 0;
  font-size: 14.5px;
  font-weight: 600;
}

.conclusion-text-col p,
.review-form p {
  margin: 0;
  font-size: 13.5px;
  color: var(--oc-fg-muted);
}

.review-form label {
  display: block;
  margin-top: 14px;
  font-size: 13px;
  font-weight: 550;
}

textarea {
  display: block;
  width: 100%;
  box-sizing: border-box;
  margin: 8px 0 10px;
  resize: vertical;
  color: var(--oc-fg);
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border-input);
  border-radius: var(--radius-control);
  padding: 10px 12px;
  font: inherit;
}

textarea:focus-visible {
  border-color: var(--oc-accent);
  box-shadow: 0 0 0 3px var(--oc-accent-glow);
}

.total {
  font-size: 36px;
  margin: 0;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-family: var(--oc-font-mono);
  font-variant-numeric: tabular-nums;
  color: var(--oc-fg);
}

.total span,
.score span {
  color: var(--oc-fg-muted);
  font-size: 13.5px;
  font-weight: 450;
}

.score-bar {
  width: 100%;
  height: 5px;
  border-radius: 999px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  overflow: hidden;
}

.score-bar-lg {
  height: 7px;
}

.score-bar-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--oc-accent);
  transition: width 260ms var(--oc-ease);
}

/* 2x2 Dimensions Dashboard */
.dimensions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.dimension {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-panel);
  box-shadow: var(--oc-hairline-top), var(--oc-shadow-xs);
  transition: border-color 150ms var(--oc-ease);
}

.dimension:hover {
  border-color: var(--oc-border-strong);
}

.dimension-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.dimension h3 {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--oc-fg-muted);
}

.score {
  margin: 0;
  font-size: 22px;
  font-weight: 650;
  font-family: var(--oc-font-mono);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}

.dimension-feedback {
  margin: 0;
  font-size: 13px;
  color: var(--oc-fg);
  line-height: 1.55;
}

.evidence-links {
  margin-top: auto;
  padding-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.report-evidence {
  position: sticky;
  top: 24px;
  padding: 22px;
  border-radius: var(--radius-panel);
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  box-shadow: var(--oc-hairline-top), var(--oc-shadow-sm);
  display: grid;
  gap: 12px;
}

.report-evidence h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.report-evidence > p {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

ul {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 10px;
}

li {
  padding: 12px;
  border-radius: 10px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 6px;
}

li p {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.evidence-button {
  text-align: left;
  color: var(--oc-accent);
  background: none;
  border: 0;
  padding: 0;
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  font-weight: 550;
  font-family: var(--oc-font-mono);
  overflow-wrap: anywhere;
}

.evidence-button[aria-pressed="true"] {
  color: var(--oc-fg);
  text-decoration: underline;
  text-underline-offset: 4px;
}

pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 460px;
  overflow: auto;
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
  border-radius: 10px;
  padding: 14px;
  font-size: 12.5px;
  line-height: 1.6;
}

details {
  padding: 14px 18px;
  border-radius: 12px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
}

summary {
  cursor: pointer;
  font-size: 13.5px;
  font-weight: 550;
}

h3 {
  overflow-wrap: anywhere;
}

@media (max-width: 900px) {
  .report-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .report-evidence {
    position: static;
  }
}

@media (max-width: 560px) {
  .conclusion {
    grid-template-columns: 1fr;
    gap: 14px;
  }
  .conclusion-score-col {
    padding-right: 0;
    padding-bottom: 12px;
    border-right: 0;
    border-bottom: 1px solid var(--oc-border);
  }
  .dimensions {
    grid-template-columns: 1fr;
  }
  .review-form,
  .conclusion,
  .retry-area,
  .report-heading,
  .report-evidence {
    padding: 16px;
  }
}
</style>
