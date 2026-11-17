<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useIntervalFn, useTimestamp } from '@vueuse/core';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { RouterLink, useRouter } from 'vue-router';
import { ArrowRight, Search, Star } from '@lucide/vue';
import ProblemRequirements from './ProblemRequirements.vue';
import Button from '../components/ui/Button.vue';
import Tooltip from '../components/ui/Tooltip.vue';
import StatePanel from '../components/ui/StatePanel.vue';
import { session } from '../auth/api';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import * as workspace from '../workspace/api';
import * as api from './api';
import { routes } from '../routes';

const client = useQueryClient();
const router = useRouter();
const account = useQuery({ queryKey: ['identity-session'], queryFn: ({ signal }) => session(signal), retry: false });
const owner = computed(() => account.data.value?.id ?? '');
const now = useTimestamp({ scheduler: callback => useIntervalFn(callback, 1000) });
const allowed = computed(() => account.isFetchedAfterMount.value && !!owner.value && account.data.value?.role === 'USER'
  && !account.data.value.passwordChangeRequired && !account.isError.value && Date.parse(account.data.value.expiresAt) > now.value);
const catalogue = useQuery({ queryKey: ['catalog', owner], queryFn: ({ signal }) => workspace.problems(signal), enabled: allowed, retry: false });
const favorites = useQuery({ queryKey: ['favorites', owner], queryFn: ({ signal }) => api.favorites(owner.value, signal), enabled: allowed, retry: false });
const current = useQuery({ queryKey: ['workspace', owner, 'current'], queryFn: ({ signal }) => workspace.current(signal, undefined, owner.value), enabled: allowed, retry: false, refetchInterval: 5000 });
watch([favorites.error, current.error], (errors) => {
  if (errors.some(error => error instanceof ApiError && [401, 403].includes(error.status))) {
    client.setQueryData(['identity-session'], null);
    void account.refetch();
  }
});
const currentTitle = computed(() => catalogue.data.value?.find(entry => entry.problemVersionId === current.data.value?.problemVersionId)?.title ?? '当前演练');
const search = ref('');
const onlyFavorites = ref(false);
const selected = ref('');
const page = ref(0);
const saved = computed(() => new Set(favorites.data.value?.map((entry) => entry.problemId)));
const filtered = computed(() => (catalogue.data.value ?? []).filter((entry) => entry.title.toLowerCase().includes(search.value.toLowerCase())
  && (!onlyFavorites.value || !!entry.problemId && saved.value.has(entry.problemId))));
const visible = computed(() => filtered.value.slice(page.value * 12, (page.value + 1) * 12));
const detail = computed(() => visible.value.find((entry) => entry.problemVersionId === selected.value) ?? visible.value[0]);
const withdrawn = computed(() => (favorites.data.value ?? []).filter((entry) => !entry.available));
watch([search, onlyFavorites, owner], () => { page.value = 0; selected.value = ''; });
watch(owner, () => { search.value = ''; onlyFavorites.value = false; });
watch(() => filtered.value.length, () => { if (page.value * 12 >= filtered.value.length) page.value = 0; });
const policy = { definitiveRejection: (failure: unknown) => failure instanceof ApiError && [400, 401, 403, 404, 409, 429].includes(failure.status),
  errorMessage: workspace.message, identityFailure: (failure: unknown) => failure instanceof ApiError && failure.status === 401 };
const favorite = useCommand<void>(() => owner.value, () => { void client.invalidateQueries({ queryKey: ['favorites', owner.value] }); }, policy);
const create = useCommand<{ exerciseId: string; state: string }>(() => owner.value, (result) => {
  void client.invalidateQueries({ queryKey: ['workspace', owner.value, 'current'] });
  void router.push({ name: routes.workspace.name, query: { exercise: result.exerciseId } });
}, policy);
const startReason = computed(() => !detail.value?.executionAvailable ? '该版本执行环境不可用' : current.data.value ? '请先继续或结束当前演练'
  : current.isPending.value || current.isError.value ? '请先成功确认进行中的演练' : create.pending.value ? '正在创建，请稍候'
  : create.uncertain.value ? '请先核对原创建操作' : '');
function toggle(problem: string) {
  const user = owner.value, desired = !saved.value.has(problem);
  void favorite.submit((_key, signal) => api.setFavorite(user, problem, desired, signal));
}
function start() {
  if (!detail.value || !detail.value.executionAvailable || current.data.value || current.isError.value || current.isPending.value) return;
  const version = detail.value.problemVersionId, user = owner.value;
  void create.submit((key, signal) => workspace.act({ path: '/api/exercises', body: { problemVersionId: version }, key }, signal, undefined, user));
}
</script>

<template>
  <main class="catalog-page">
    <header class="catalog-header">
      <div>
        <h1>题库</h1>
        <p>从一个真实问题开始，在工作区中完成分析、编码与验证。</p>
      </div>
      <RouterLink
        class="ui-button ui-button-secondary ui-button-small history-entry"
        :to="{ name: routes.history.name }"
      >
        <span>历史演练</span>
        <ArrowRight
          :size="14"
          aria-hidden="true"
        />
      </RouterLink>
    </header>
    <StatePanel
      v-if="account.isPending.value || !account.isFetchedAfterMount.value"
      kind="loading"
      title="正在确认身份"
      description="请稍候。"
    />
    <StatePanel
      v-else-if="!allowed"
      kind="error"
      title="需要有效登录"
      description="请登录并完成临时密码修改。"
    >
      <RouterLink :to="{ name: routes.account.name }">前往账号页</RouterLink>
    </StatePanel>
    <template v-else>
      <section
        class="current-exercise"
        aria-label="进行中演练"
      >
        <div class="current-exercise-copy">
          <span class="current-strip-label">当前演练</span>
          <span
            v-if="current.isPending.value"
            class="current-strip-text"
          >正在查询…</span>
          <span
            v-else-if="current.isError.value"
            role="alert"
            class="current-strip-text failure-text"
          >无法确认进行中的演练；暂不创建新演练。</span>
          <span
            v-else-if="!current.data.value"
            class="current-strip-text"
          >暂无进行中演练，选择下方题目即可准备。</span>
          <div
            v-else
            class="current-active-meta"
          >
            <strong class="current-title">{{ currentTitle }}</strong>
            <span
              class="ui-pill"
              data-tone="accent"
            >{{ current.data.value.state === 'READY' ? '准备就绪' : current.data.value.state === 'PAUSED' ? '已暂停' : '进行中' }}</span>
            <details class="exercise-identity">
              <summary>标识</summary>
              <code>{{ current.data.value.exerciseId }}</code>
            </details>
          </div>
        </div>
        <div class="current-exercise-actions">
          <RouterLink
            v-if="current.data.value && !current.isError.value"
            :to="{ name: routes.workspace.name, query: { exercise: current.data.value.exerciseId } }"
            class="ui-button ui-button-primary ui-button-small"
          >继续演练</RouterLink>
          <Button
            v-if="current.isError.value"
            variant="secondary"
            size="small"
            @click="current.refetch()"
          >
            重新查询
          </Button>
        </div>
      </section>
      <div class="catalog-filters">
        <div class="search-box">
          <Search
            :size="15"
            class="search-icon"
            aria-hidden="true"
          />
          <input
            v-model="search"
            type="search"
            aria-label="搜索题目"
            placeholder="搜索题目…"
            maxlength="128"
          >
        </div>
        <label class="favorite-filter">
          <input
            v-model="onlyFavorites"
            type="checkbox"
            role="switch"
            :disabled="favorites.isError.value || favorites.isPending.value"
          >
          <span>仅收藏</span>
        </label>
        <span class="version-count">{{ filtered.length }} 个版本</span>
      </div>
      <StatePanel
        v-if="catalogue.isPending.value"
        kind="loading"
        title="正在读取题库"
        description="只展示已发布的可用版本。"
      />
      <StatePanel
        v-else-if="catalogue.isError.value"
        kind="error"
        title="题库读取失败"
        description="不会使用过期列表创建演练。"
      >
        <Button @click="catalogue.refetch()">
          重试
        </Button>
      </StatePanel>
      <StatePanel
        v-else-if="!filtered.length"
        title="没有符合筛选的题目"
        description="调整搜索或收藏条件后重试。"
      />
      <div
        v-else
        class="catalog-columns"
      >
        <section
          class="problem-list"
          aria-label="已发布题目"
        >
          <Tooltip
            v-for="entry in visible"
            :key="entry.problemVersionId"
            :text="entry.title"
          >
            <button
              class="problem-choice"
              :aria-pressed="detail?.problemVersionId === entry.problemVersionId"
              @click="selected = entry.problemVersionId"
            >
              <strong>{{ entry.title }}</strong>
              <span class="choice-meta">
                <span class="meta-chip">Java</span>
                <span class="meta-chip">v{{ entry.version }}</span>
                <span class="meta-chip tabular">{{ entry.durationSeconds / 60 }} 分钟</span>
              </span>
            </button>
          </Tooltip>
        </section>
        <section
          v-if="detail"
          class="problem-detail"
          aria-label="题目详情"
        >
          <header class="detail-header">
            <div class="detail-title-wrap">
              <div class="problem-meta">
                <span
                  class="ui-pill"
                  data-tone="accent"
                >JAVA</span>
                <span class="ui-pill">v{{ detail.version }}</span>
                <span class="ui-pill tabular">{{ detail.durationSeconds / 60 }} 分钟</span>
              </div>
              <h2>{{ detail.title }}</h2>
            </div>
            <Button
              v-if="detail.problemId"
              variant="ghost"
              size="icon"
              class="favorite-toggle"
              :aria-label="saved.has(detail.problemId) ? '取消收藏' : '收藏题目'"
              :aria-pressed="saved.has(detail.problemId)"
              :disabled="favorite.pending.value || favorite.uncertain.value || favorites.isError.value || favorites.isPending.value"
              @click="toggle(detail.problemId)"
            >
              <Star
                :size="17"
                :fill="saved.has(detail.problemId) ? 'currentColor' : 'none'"
              />
            </Button>
          </header>
          <div class="requirements">
            <ProblemRequirements :source="detail.requirements" />
            <p class="runtime-meta">
              时限 {{ detail.durationSeconds / 60 }} 分钟 · 服务端执行环境
            </p>
          </div>
          <div class="start-action">
            <p
              v-if="!detail.executionAvailable"
              class="alert-warning"
              role="alert"
            >
              该版本执行环境不可用，不能开始。
            </p>
            <p v-else-if="current.data.value">
              请先继续或结束当前演练。
            </p>
            <p v-else>
              创建后先完成模型与环境检查，不会立即开始计时。
            </p>
            <Tooltip :text="startReason || '进入准备页，检查模型与执行环境'">
              <span :tabindex="startReason ? 0 : undefined"><Button
                variant="primary"
                :disabled="!!startReason"
                @click="start"
              >
                {{ create.pending.value ? '正在创建…' : '准备演练' }}
              </Button></span>
            </Tooltip>
          </div>
        </section>
      </div>
      <nav
        v-if="filtered.length > 12"
        class="catalog-pagination"
        aria-label="题库分页"
      >
        <Button
          variant="secondary"
          size="small"
          :disabled="page === 0"
          @click="page--"
        >
          上一页
        </Button>
        <span class="page-indicator">第 {{ page + 1 }} 页</span>
        <Button
          variant="secondary"
          size="small"
          :disabled="(page + 1) * 12 >= filtered.length"
          @click="page++"
        >
          下一页
        </Button>
      </nav>
      <p
        v-if="favorites.isError.value"
        role="alert"
        class="inline-alert"
      >
        收藏读取失败。
        <Button
          variant="secondary"
          size="small"
          @click="favorites.refetch()"
        >
          重试收藏
        </Button>
      </p>
      <p
        v-if="favorite.error.value"
        role="alert"
        class="inline-alert"
      >
        {{ favorite.error.value }}
      </p>
      <Button
        v-if="favorite.uncertain.value"
        size="small"
        :disabled="favorite.pending.value"
        @click="favorite.retry()"
      >
        核对并重试原收藏操作
      </Button>
      <p
        v-if="create.error.value"
        role="alert"
        class="inline-alert"
      >
        {{ create.error.value }}
      </p>
      <Button
        v-if="create.uncertain.value"
        size="small"
        :disabled="create.pending.value"
        @click="create.retry()"
      >
        核对并重试原创建
      </Button>
      <section
        v-if="withdrawn.length"
        class="withdrawn-card"
        aria-label="已下架收藏"
      >
        <div class="withdrawn-header">
          <h2>已下架收藏</h2>
          <p>收藏仍保留，但不能创建新演练。</p>
        </div>
        <div
          v-for="entry in withdrawn"
          :key="entry.problemId"
          class="withdrawn"
        >
          <span>{{ entry.title }} · 不可开始</span>
          <Button
            variant="secondary"
            size="small"
            :disabled="favorite.pending.value || favorite.uncertain.value"
            @click="toggle(entry.problemId)"
          >
            取消收藏
          </Button>
        </div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.catalog-page {
  max-width: 1220px;
  margin: 0 auto;
  padding: 28px 32px 56px;
}

.catalog-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.catalog-header h1 {
  margin: 0 0 6px;
  font-size: clamp(22px, 2.4vw, 26px);
  font-weight: 650;
  letter-spacing: -0.025em;
}

.catalog-header p {
  margin: 0;
  color: var(--oc-fg-muted);
  font-size: 13.5px;
  line-height: 1.55;
}

.history-entry {
  text-decoration: none;
}

/* Single-row Compact Status Toolbar Strip */
.current-exercise {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  min-height: 44px;
  padding: 8px 14px;
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-control);
  background: var(--oc-surface);
  box-shadow: var(--oc-hairline-top);
  overflow-wrap: anywhere;
}

.current-exercise-copy {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  min-width: 0;
  flex: 1 1 280px;
}

.current-strip-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--oc-accent);
  padding: 2px 7px;
  border-radius: 5px;
  background: var(--oc-accent-soft);
  border: 1px solid var(--oc-border-accent);
  white-space: nowrap;
}

.current-strip-text {
  font-size: 13px;
  color: var(--oc-fg-muted);
}

.failure-text {
  color: var(--oc-danger);
}

.current-active-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}

.current-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--oc-fg);
}

.exercise-identity {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.exercise-identity summary {
  cursor: pointer;
}

.exercise-identity code {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
  font-size: 11.5px;
}

.current-exercise-actions a {
  text-decoration: none;
}

/* Inline Filter Bar */
.catalog-filters {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin: 14px 0 18px;
  padding: 10px 14px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-control);
}

.search-box {
  position: relative;
  flex: 1 1 240px;
  max-width: 340px;
  display: flex;
  align-items: center;
}

.search-icon {
  position: absolute;
  left: 10px;
  color: var(--oc-fg-dim);
  pointer-events: none;
}

input[type="search"] {
  width: 100%;
  padding: 7px 11px 7px 32px;
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border-input);
  border-radius: var(--radius-control);
  color: var(--oc-fg);
  font-size: 13px;
}

.favorite-filter {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 11px;
  border-radius: var(--radius-control);
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  font-size: 13px;
  font-weight: 500;
  color: var(--oc-fg);
  cursor: pointer;
  transition: border-color 140ms var(--oc-ease), background-color 140ms var(--oc-ease);
}

.favorite-filter:hover {
  border-color: var(--oc-border-strong);
  background: var(--oc-surface-hover);
}

.favorite-filter input {
  accent-color: var(--oc-accent);
}

.version-count {
  margin-left: auto;
  color: var(--oc-fg-muted);
  font-size: 12px;
  font-family: var(--oc-font-mono);
  font-variant-numeric: tabular-nums;
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
}

/* Master-Detail Columns */
.catalog-columns {
  display: grid;
  grid-template-columns: minmax(270px, 0.82fr) minmax(0, 1.48fr);
  gap: 16px;
  align-items: start;
}

.problem-list {
  display: grid;
  gap: 8px;
}

.problem-choice {
  position: relative;
  padding: 13px 15px;
  text-align: left;
  display: grid;
  gap: 9px;
  color: var(--oc-fg);
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-left: 2px solid transparent;
  border-radius: 8px;
  cursor: pointer;
  overflow-wrap: anywhere;
  transition:
    background-color 140ms var(--oc-ease),
    border-color 140ms var(--oc-ease);
}

.problem-choice:hover {
  background: var(--oc-surface-2);
  border-color: var(--oc-border-strong);
}

.problem-choice strong {
  font-size: 14px;
  font-weight: 600;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 2.8em;
}

.problem-choice[aria-pressed="true"] {
  background: var(--oc-surface-2);
  border-color: var(--oc-border-strong);
  border-left: 2px solid var(--oc-accent);
}

.choice-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.meta-chip {
  font-family: var(--oc-font-mono);
  font-size: 11.5px;
  color: var(--oc-fg-muted);
  padding: 1px 7px;
  border-radius: 4px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
}

.tabular {
  font-variant-numeric: tabular-nums;
}

/* Sticky Detail Panel: Fixed Header + Scrollable Body + Fixed Footer */
.problem-detail {
  min-width: 0;
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-panel);
  background: var(--oc-surface);
  box-shadow: var(--oc-shadow-sm), var(--oc-hairline-top);
  display: flex;
  flex-direction: column;
  max-height: calc(100dvh - 48px);
  position: sticky;
  top: 24px;
  overflow: hidden;
}

.detail-header {
  flex-shrink: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  padding: 18px 22px 16px;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-surface-2);
}

.detail-title-wrap {
  flex: 1;
  min-width: 0;
}

.detail-header h2 {
  margin: 8px 0 0;
  font-size: 18px;
  font-weight: 650;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.problem-meta {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  font-family: var(--oc-font-mono);
}

.favorite-toggle[aria-pressed="true"] {
  color: var(--oc-warning);
  background: var(--oc-warning-bg);
  border-color: var(--oc-warning-border);
}

.requirements {
  flex: 1;
  overflow: auto;
  min-height: 0;
  padding: 18px 22px 22px;
}

.runtime-meta {
  margin: 16px 0 0;
  padding-top: 12px;
  border-top: 1px solid var(--oc-border);
  font-size: 12px;
  color: var(--oc-fg-muted);
  font-variant-numeric: tabular-nums;
}

.start-action {
  flex-shrink: 0;
  border-top: 1px solid var(--oc-border);
  padding: 13px 22px;
  background: var(--oc-surface-2);
  display: flex;
  align-items: center;
  gap: 14px;
  justify-content: space-between;
}

.start-action p {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
  line-height: 1.45;
}

.start-action .alert-warning {
  color: var(--oc-warning);
  padding: 6px 10px;
  border-radius: var(--radius-control);
  background: var(--oc-warning-bg);
  border: 1px solid var(--oc-warning-border);
}

.catalog-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  margin: 22px 0 10px;
}

.page-indicator {
  font-size: 12.5px;
  color: var(--oc-fg-muted);
  font-variant-numeric: tabular-nums;
}

.inline-alert {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin: 14px 0;
  padding: 10px 14px;
  border-radius: var(--radius-control);
  background: var(--oc-danger-bg);
  border: 1px solid var(--oc-danger-border);
  color: var(--oc-danger);
  font-size: 13px;
}

.withdrawn-card {
  margin-top: 24px;
  padding: 18px 20px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-panel);
}

.withdrawn-header h2 {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
}

.withdrawn-header p {
  margin: 0 0 12px;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.withdrawn {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  padding: 10px 0;
  border-top: 1px solid var(--oc-border);
  font-size: 13px;
  color: var(--oc-fg-muted);
  overflow-wrap: anywhere;
}

@media (max-width: 850px) {
  .catalog-columns {
    grid-template-columns: minmax(0, 1fr);
  }
  .catalog-page {
    padding: 20px 16px 40px;
  }
  .problem-detail {
    position: static;
    max-height: none;
  }
  .detail-header,
  .requirements,
  .start-action {
    padding-left: 16px;
    padding-right: 16px;
  }
  .start-action {
    flex-wrap: wrap;
  }
}
</style>
