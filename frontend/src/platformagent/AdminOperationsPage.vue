<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useIntervalFn, useTimestamp } from '@vueuse/core';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { RouterLink } from 'vue-router';
import { session } from '../auth/api';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import Button from '../components/ui/Button.vue';
import StatePanel from '../components/ui/StatePanel.vue';
import { adminCopilotMetrics } from '../copilot/telemetryApi';
import * as api from './adminOperationsApi';

const client = useQueryClient();
const account = useQuery({
  queryKey: ['identity-session'],
  queryFn: ({ signal }) => session(signal),
  retry: false,
  gcTime: 0,
  refetchInterval: 30000,
});
const owner = computed(() => account.data.value?.id ?? '');
const now = useTimestamp({ scheduler: callback => useIntervalFn(callback, 1000) });
const allowed = computed(() => account.isFetchedAfterMount.value
  && !!owner.value
  && account.data.value?.role === 'ADMIN'
  && !account.data.value.passwordChangeRequired
  && !account.isError.value
  && Date.parse(account.data.value.expiresAt) > now.value);
const commandScope = () => allowed.value ? owner.value : '';

const artifactText = ref('');
const validation = ref<api.ProblemValidation | null>(null);
const validatedPayload = ref('');
const validationError = ref('');
const validating = ref(false);
const notice = ref('');
const currentValidation = computed(() => validation.value && validatedPayload.value === artifactText.value ? validation.value : null);
const validationStale = computed(() => !!validation.value && validatedPayload.value !== artifactText.value);

const metrics = useQuery({
  queryKey: ['admin-copilot-metrics', owner],
  queryFn: ({ signal }) => adminCopilotMetrics(owner.value, signal),
  enabled: allowed,
  retry: false,
  refetchInterval: 15000,
});

const problems = useQuery({
  queryKey: ['admin-problems', owner],
  queryFn: ({ signal }) => api.listProblems(owner.value, signal),
  enabled: allowed,
  retry: false,
});

function clearPrivateQueries(user: string) {
  for (const namespace of ['admin-copilot-metrics', 'admin-problems']) {
    void client.cancelQueries({ queryKey: [namespace, user] });
    client.removeQueries({ queryKey: [namespace, user] });
  }
}

function refreshIdentity() {
  const user = owner.value;
  client.setQueryData(['identity-session'], null);
  clearPrivateQueries(user);
  void account.refetch();
}

watch([owner, allowed, () => account.data.value?.role, account.isFetchedAfterMount, account.isError], ([user, permit], [previous]) => {
  if (previous && (previous !== user || !permit)) {
    clearPrivateQueries(previous);
    artifactText.value = '';
    validation.value = null;
    validatedPayload.value = '';
    validationError.value = '';
    notice.value = '';
  }
}, { flush: 'sync' });

watch([metrics.error, problems.error], (errors) => {
  if (errors.some(error => error instanceof ApiError && [401, 403].includes(error.status))) {
    refreshIdentity();
  }
});

const policy = {
  definitiveRejection: (failure: unknown) => failure instanceof ApiError && [400, 401, 403, 404, 409, 413, 422, 429].includes(failure.status),
  errorMessage: api.problemErrorMessage,
  identityFailure: (failure: unknown) => failure instanceof ApiError && [401, 403].includes(failure.status),
};

const publishCommand = useCommand<api.PublishedProblem>(commandScope, (published) => {
  notice.value = `已发布题包：${published.problemId} v${published.version}（${published.id}）。`;
  void client.invalidateQueries({ queryKey: ['admin-problems', owner.value] });
}, policy, refreshIdentity);

const availabilityCommand = useCommand<void>(commandScope, () => {
  notice.value = '已更新题包可用状态。';
  void client.invalidateQueries({ queryKey: ['admin-problems', owner.value] });
}, policy, refreshIdentity);

const busy = computed(() => validating.value
  || publishCommand.pending.value
  || publishCommand.uncertain.value
  || availabilityCommand.pending.value
  || availabilityCommand.uncertain.value);

async function runValidate() {
  if (!allowed.value || validating.value || !artifactText.value.trim()) return;
  const payload = artifactText.value;
  validating.value = true;
  validationError.value = '';
  validation.value = null;
  validatedPayload.value = '';
  notice.value = '';
  const controller = new globalThis.AbortController();
  try {
    const result = await api.validateProblem(owner.value, payload, controller.signal);
    if (artifactText.value === payload) {
      validation.value = result;
      validatedPayload.value = payload;
    }
  } catch (error) {
    if (error instanceof ApiError && [401, 403].includes(error.status)) refreshIdentity();
    else validationError.value = api.problemErrorMessage(error);
  } finally {
    validating.value = false;
  }
}

function runPublish() {
  if (!allowed.value || busy.value || !artifactText.value.trim()) return;
  notice.value = '';
  const user = owner.value;
  const payload = artifactText.value;
  void publishCommand.submit((key, signal) => api.publishProblem(user, payload, key, signal));
}

function toggleAvailability(item: api.AdminProblem) {
  if (!allowed.value || busy.value) return;
  notice.value = '';
  const user = owner.value;
  const next = !item.available;
  void availabilityCommand.submit((key, signal) => api.setProblemAvailability(user, item.id, next, key, signal));
}

function formatNum(value: number | null | undefined, suffix = ''): string {
  return value === null || value === undefined ? '-' : `${value}${suffix}`;
}

function formatRatio(value: number | null | undefined): string {
  if (value === null || value === undefined) return '-';
  return `${Math.round(value * 100)}%`;
}

function languageLabel(language: string | null): string {
  if (!language) return '-';
  return language === 'PYTHON_PYTEST' ? 'Python (Pytest)' : language === 'JAVA_MAVEN' ? 'Java (Maven)' : language;
}

const activeError = computed(() => validationError.value || publishCommand.error.value || availabilityCommand.error.value);
</script>

<template>
  <main class="ops-page">
    <header class="ops-header">
      <div class="ops-title-row">
        <h1>题库与遥测</h1>
        <span
          v-if="allowed && account.data.value"
          class="owner-pill font-mono-num"
        >{{ account.data.value.username }}</span>
      </div>
    </header>

    <p
      v-if="account.isPending.value || !account.isFetchedAfterMount.value"
      role="status"
      class="inline-status"
    >
      正在确认权限…
    </p>
    <div
      v-else-if="account.isError.value"
      role="alert"
      class="gate-card"
    >
      <p>无法确认管理员权限。</p>
      <Button
        size="small"
        @click="account.refetch()"
      >
        重试确认
      </Button>
    </div>
    <div
      v-else-if="!allowed"
      class="gate-card"
    >
      <p>需要管理员身份且已完成临时密码修改才能管理题库或查看平台遥测。</p>
      <RouterLink
        class="ui-button ui-button-secondary ui-button-small"
        :to="{ name: 'account' }"
      >前往账号页</RouterLink>
    </div>

    <div
      v-else
      class="ops-stack"
    >
      <p
        v-if="activeError"
        role="alert"
        class="inline-alert"
      >
        {{ activeError }}
      </p>
      <p
        v-else-if="notice"
        role="status"
        class="inline-notice"
      >
        {{ notice }}
      </p>

      <div
        v-if="publishCommand.uncertain.value || availabilityCommand.uncertain.value"
        role="status"
        class="uncertain-bar"
      >
        <span>请求结果未确认，请仅使用原幂等键重试。</span>
        <Button
          size="small"
          variant="ghost"
          @click="publishCommand.uncertain.value ? publishCommand.retry() : availabilityCommand.retry()"
        >
          原幂等键重试
        </Button>
      </div>

      <section
        class="ops-block"
        aria-label="平台 Copilot 聚合遥测"
      >
        <div class="section-head">
          <h2 class="section-heading">
            平台 Copilot 聚合遥测（脱敏）
          </h2>
          <Button
            size="small"
            variant="ghost"
            :disabled="metrics.isFetching.value"
            @click="metrics.refetch()"
          >
            刷新
          </Button>
        </div>
        <p
          v-if="metrics.isPending.value"
          role="status"
          class="inline-status"
        >
          正在加载遥测指标…
        </p>
        <StatePanel
          v-else-if="metrics.isError.value"
          kind="error"
          title="遥测加载失败"
          description="无法读取平台 Copilot 聚合遥测"
        />
        <div
          v-else-if="metrics.data.value"
          class="metrics-surface"
        >
          <dl class="metrics-grid">
            <div>
              <dt>累计任务 / 轮次 / 工具</dt>
              <dd class="font-mono-num">
                {{ metrics.data.value.taskCount }} / {{ metrics.data.value.rounds }} / {{ metrics.data.value.tools }}
              </dd>
            </div>
            <div>
              <dt>只读并行窗口 / 槽位利用率</dt>
              <dd class="font-mono-num">
                {{ metrics.data.value.readWindows }}（{{ formatRatio(metrics.data.value.readWindowUtilization) }}）
              </dd>
            </div>
            <div>
              <dt>Explore 并发子任务数</dt>
              <dd class="font-mono-num">
                {{ metrics.data.value.exploreChildren }}
              </dd>
            </div>
            <div>
              <dt>Micro-compaction 轮次 / 节省</dt>
              <dd class="font-mono-num">
                {{ metrics.data.value.microCompactedRounds }} / {{ formatNum(metrics.data.value.microSavedBytes, ' B') }}
              </dd>
            </div>
            <div>
              <dt>冲突重试恢复 / Steer 中断</dt>
              <dd class="font-mono-num">
                {{ metrics.data.value.recoveredRevisionConflicts }} / {{ metrics.data.value.steerInterruptions }}
              </dd>
            </div>
            <div>
              <dt>CHAT-only 输入 / 输出 / 缓存读</dt>
              <dd class="font-mono-num">
                {{ formatNum(metrics.data.value.chatInputTokens) }} / {{ formatNum(metrics.data.value.chatOutputTokens) }} / {{ formatNum(metrics.data.value.chatCacheReadTokens) }}
              </dd>
            </div>
          </dl>
          <p class="metrics-footnote">
            仅聚合脱敏运行计数，不含用户源码、Prompt 或身份信息；Token 仅统计 CHAT 轮次，缺失样本以 - 标示。
          </p>
        </div>
      </section>

      <section
        class="ops-block"
        aria-label="题库版本管理"
      >
        <div class="section-head">
          <h2 class="section-heading">
            题库版本目录
          </h2>
          <Button
            size="small"
            variant="ghost"
            :disabled="problems.isFetching.value"
            @click="problems.refetch()"
          >
            刷新
          </Button>
        </div>
        <p
          v-if="problems.isPending.value"
          role="status"
          class="inline-status"
        >
          正在加载题库版本…
        </p>
        <StatePanel
          v-else-if="problems.isError.value"
          kind="error"
          title="题库目录加载失败"
          description="无法读取管理员题库版本列表"
        />
        <p
          v-else-if="!problems.data.value?.length"
          class="empty-line"
        >
          暂无已发布题包版本。
        </p>
        <ul
          v-else
          class="ui-divide-list surface-list"
        >
          <li
            v-for="item in problems.data.value"
            :key="item.id"
            class="ui-divide-row problem-row"
          >
            <div class="problem-main">
              <div class="problem-title-line">
                <strong class="font-mono">{{ item.problemId }}</strong>
                <span class="state-pill font-mono-num">v{{ item.version }}</span>
                <span class="state-pill">{{ languageLabel(item.language) }}</span>
                <span
                  class="state-pill"
                  :class="item.executionSupported ? 'pill-accent' : 'pill-warn'"
                >
                  {{ item.executionSupported ? '支持沙箱执行' : '仅静态契约' }}
                </span>
                <span
                  class="state-pill"
                  :class="item.available ? 'pill-accent' : 'pill-muted'"
                >
                  {{ item.available ? '已上架' : '已下架' }}
                </span>
              </div>
              <div class="problem-sha font-mono-num">
                SHA-256: {{ item.packageSha256 }}
              </div>
            </div>
            <Button
              size="small"
              :variant="item.available ? 'ghost' : 'secondary'"
              :disabled="busy"
              @click="toggleAvailability(item)"
            >
              {{ item.available ? '下架' : '上架' }}
            </Button>
          </li>
        </ul>
      </section>

      <section
        class="ops-block"
        aria-label="题包校验与幂等发布"
      >
        <h2 class="section-heading">
          题包 Artifact 校验与幂等发布
        </h2>
        <div class="publish-surface">
          <label
            for="problem-artifact-input"
            class="sub-label"
          >发布包 JSON（上限 1 MiB，先校验再幂等写入）</label>
          <textarea
            id="problem-artifact-input"
            v-model="artifactText"
            rows="7"
            class="artifact-textarea font-mono"
            placeholder="粘贴由 scripts/publish-problem.mjs 生成的 publication artifact JSON…"
          />
          <div class="publish-actions">
            <Button
              size="small"
              variant="secondary"
              :disabled="busy || !artifactText.trim()"
              @click="runValidate"
            >
              {{ validating ? '校验中…' : '仅校验契约' }}
            </Button>
            <Button
              size="small"
              variant="primary"
              :disabled="busy || !artifactText.trim()"
              @click="runPublish"
            >
              {{ publishCommand.pending.value ? '发布中…' : '幂等发布到目录' }}
            </Button>
          </div>
          <p
            v-if="validationStale"
            class="inline-status"
            role="status"
          >
            输入内容已修改，上次校验结果已失效，请重新校验。
          </p>
          <div
            v-if="currentValidation"
            class="validation-box"
            role="status"
          >
            <p class="validation-title">
              {{ currentValidation.valid ? '契约校验通过' : '契约校验未通过' }} ·
              {{ currentValidation.problemId ?? '-' }} v{{ currentValidation.version ?? '-' }} ·
              {{ languageLabel(currentValidation.language) }} ·
              {{ currentValidation.executionSupported ? '支持沙箱执行' : '仅静态契约（不可执行）' }}
            </p>
            <ul
              v-if="currentValidation.errors.length"
              class="validation-errors"
            >
              <li
                v-for="(err, idx) in currentValidation.errors"
                :key="idx"
              >
                {{ err }}
              </li>
            </ul>
          </div>
          <p class="metrics-footnote">
            发布仅将版本登记到 Java 题库目录；候选人要实际执行该版本，运行中的 Go 沙箱节点也需加载同一 SHA-256 的 publication 清单。
          </p>
        </div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.ops-page {
  max-width: 920px;
  margin: 0 auto;
  padding: 28px 24px 60px;
  display: grid;
  gap: 20px;
}

.ops-header {
  padding-bottom: 14px;
  border-bottom: 1px solid var(--oc-border);
}

.ops-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.ops-title-row h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.owner-pill {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--oc-surface-2);
  color: var(--oc-fg-muted);
  border: 1px solid var(--oc-border);
}

.ops-stack {
  display: grid;
  gap: 22px;
  min-width: 0;
}

.ops-block {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.section-heading {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--oc-fg-muted);
}

.inline-status,
.empty-line,
.gate-card p {
  margin: 0;
  font-size: 13px;
  color: var(--oc-fg-muted);
}

.gate-card,
.metrics-surface,
.publish-surface {
  padding: 14px 16px;
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 12px;
}

.inline-alert {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-danger-bg);
  color: var(--oc-danger);
  font-size: 13px;
}

.inline-notice {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-accent-bg);
  color: var(--oc-fg);
  font-size: 13px;
}

.uncertain-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border-strong);
  font-size: 12.5px;
}

.metrics-grid {
  margin: 0;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px 16px;
}

.metrics-grid dt {
  font-size: 11.5px;
  color: var(--oc-fg-dim);
  margin-bottom: 3px;
}

.metrics-grid dd {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--oc-fg);
}

.metrics-footnote {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-fg-dim);
}

.surface-list {
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  overflow: hidden;
}

.problem-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
}

.problem-main {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.problem-title-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 13px;
}

.problem-sha {
  font-size: 11.5px;
  color: var(--oc-fg-dim);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.state-pill {
  font-size: 11px;
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--oc-surface-2);
  color: var(--oc-fg-muted);
  border: 1px solid var(--oc-border);
}

.pill-accent {
  color: var(--oc-fg);
  border-color: var(--oc-border-strong);
}

.pill-warn {
  color: var(--oc-warning);
}

.pill-muted {
  color: var(--oc-fg-dim);
}

.sub-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--oc-fg-muted);
}

.artifact-textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid var(--oc-border);
  background: var(--oc-bg);
  color: var(--oc-fg);
  font-size: 12px;
  line-height: 1.5;
  resize: vertical;
}

.publish-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.validation-box {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-bg);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 6px;
}

.validation-title {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg);
}

.validation-errors {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--oc-danger);
}

@media (max-width: 720px) {
  .metrics-grid {
    grid-template-columns: 1fr;
  }
}
</style>
