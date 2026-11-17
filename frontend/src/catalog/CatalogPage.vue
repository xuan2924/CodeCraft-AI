<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useIntervalFn, useTimestamp } from '@vueuse/core';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { RouterLink, useRouter } from 'vue-router';
import { ArrowRight, Play, Search, Star } from '@lucide/vue';
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

interface ProblemGroup {
  key: string;
  problemId?: string;
  title: string;
  versions: workspace.Problem[];
}

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
const selectedGroupKey = ref('');
const selectedVersionId = ref('');
const page = ref(0);
const saved = computed(() => new Set(favorites.data.value?.map((entry) => entry.problemId)));
const filtered = computed(() => (catalogue.data.value ?? []).filter((entry) => entry.title.toLowerCase().includes(search.value.toLowerCase())
  && (!onlyFavorites.value || !!entry.problemId && saved.value.has(entry.problemId))));

const grouped = computed<ProblemGroup[]>(() => {
  const map = new Map<string, ProblemGroup>();
  for (const entry of filtered.value) {
    const key = entry.problemId ?? entry.problemVersionId;
    const existing = map.get(key);
    if (existing) {
      existing.versions.push(entry);
    } else {
      map.set(key, { key, problemId: entry.problemId, title: entry.title, versions: [entry] });
    }
  }
  return [...map.values()].map(group => ({
    ...group,
    versions: [...group.versions].sort((a, b) => {
      if (!!a.executionAvailable !== !!b.executionAvailable) return a.executionAvailable ? -1 : 1;
      return b.version.localeCompare(a.version, undefined, { numeric: true });
    }),
  }));
});

const visibleGroups = computed(() => grouped.value.slice(page.value * 12, (page.value + 1) * 12));
const activeGroup = computed(() => visibleGroups.value.find(g => g.key === selectedGroupKey.value) ?? visibleGroups.value[0]);
const detail = computed(() => {
  const group = activeGroup.value;
  if (!group) return undefined;
  return group.versions.find(v => v.problemVersionId === selectedVersionId.value) ?? group.versions[0];
});
const withdrawn = computed(() => (favorites.data.value ?? []).filter((entry) => !entry.available));

watch([search, onlyFavorites, owner], () => { page.value = 0; selectedGroupKey.value = ''; selectedVersionId.value = ''; });
watch(owner, () => { search.value = ''; onlyFavorites.value = false; });
watch(() => grouped.value.length, () => { if (page.value * 12 >= grouped.value.length) page.value = 0; });

function selectGroup(group: ProblemGroup) {
  selectedGroupKey.value = group.key;
  selectedVersionId.value = group.versions[0]?.problemVersionId ?? '';
}

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
    <header class="catalog-topbar">
      <div class="topbar-left">
        <h1>题库</h1>
        <div
          v-if="allowed"
          class="catalog-filters"
        >
          <div class="search-box">
            <Search
              :size="14"
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
          <span class="version-count font-mono-num">{{ grouped.length }} 题 · {{ filtered.length }} 版本</span>
        </div>
      </div>
      <div
        v-if="allowed"
        class="topbar-right"
      >
        <div
          v-if="current.isError.value"
          role="alert"
          class="current-chip current-chip-error"
        >
          <span>无法确认进行中演练</span>
          <Button
            variant="ghost"
            size="small"
            @click="current.refetch()"
          >
            重试
          </Button>
        </div>
        <RouterLink
          v-else-if="current.data.value"
          :to="{ name: routes.workspace.name, query: { exercise: current.data.value.exerciseId } }"
          class="current-chip current-chip-active"
          :title="`标识：${current.data.value.exerciseId}`"
        >
          <span
            class="ui-status-dot"
            data-tone="accent"
          />
          <span class="current-chip-name">{{ currentTitle }}</span>
          <span class="current-chip-action">继续演练</span>
          <Play
            :size="12"
            aria-hidden="true"
          />
        </RouterLink>
        <RouterLink
          class="ui-button ui-button-ghost ui-button-small history-entry"
          :to="{ name: routes.history.name }"
        >
          <span>历史演练</span>
          <ArrowRight
            :size="14"
            aria-hidden="true"
          />
        </RouterLink>
      </div>
    </header>

    <div
      v-if="account.isPending.value || !account.isFetchedAfterMount.value"
      class="catalog-state-wrap"
    >
      <StatePanel
        kind="loading"
        title="正在确认身份"
        description="请稍候。"
      />
    </div>
    <div
      v-else-if="!allowed"
      class="catalog-state-wrap"
    >
      <StatePanel
        kind="error"
        title="需要有效登录"
        description="请登录并完成临时密码修改。"
      >
        <RouterLink :to="{ name: routes.account.name }">前往账号页</RouterLink>
      </StatePanel>
    </div>
    <template v-else>
      <div
        v-if="favorites.isError.value || favorite.error.value || favorite.uncertain.value || create.error.value || create.uncertain.value"
        class="catalog-alerts"
      >
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
      </div>

      <div
        v-if="catalogue.isPending.value"
        class="catalog-state-wrap"
      >
        <StatePanel
          kind="loading"
          title="正在读取题库"
          description="只展示已发布的可用版本。"
        />
      </div>
      <div
        v-else-if="catalogue.isError.value"
        class="catalog-state-wrap"
      >
        <StatePanel
          kind="error"
          title="题库读取失败"
          description="不会使用过期列表创建演练。"
        >
          <Button @click="catalogue.refetch()">
            重试
          </Button>
        </StatePanel>
      </div>
      <div
        v-else-if="!grouped.length"
        class="catalog-state-wrap"
      >
        <StatePanel
          title="没有符合筛选的题目"
          description="调整搜索或收藏条件后重试。"
        />
      </div>
      <div
        v-else
        class="catalog-columns"
      >
        <aside
          class="problem-list-pane"
          aria-label="已发布题目"
        >
          <div class="problem-list ui-divider-list">
            <Tooltip
              v-for="group in visibleGroups"
              :key="group.key"
              :text="group.title"
            >
              <button
                type="button"
                class="problem-choice"
                :aria-pressed="activeGroup?.key === group.key"
                @click="selectGroup(group)"
              >
                <div class="choice-title-row">
                  <strong>{{ group.title }}</strong>
                  <Star
                    v-if="group.problemId && saved.has(group.problemId)"
                    :size="13"
                    fill="currentColor"
                    class="choice-star"
                    aria-hidden="true"
                  />
                </div>
                <span class="choice-meta font-mono-num">
                  <span class="meta-chip">Java</span>
                  <span class="meta-chip">
                    {{ group.versions.length > 1 ? `${group.versions.length} 版本` : `v${group.versions[0]?.version}` }}
                  </span>
                  <span class="meta-chip">{{ (group.versions[0]?.durationSeconds ?? 0) / 60 }} 分钟</span>
                </span>
              </button>
            </Tooltip>
          </div>
          <nav
            v-if="grouped.length > 12"
            class="catalog-pagination"
            aria-label="题库分页"
          >
            <Button
              variant="ghost"
              size="small"
              :disabled="page === 0"
              @click="page--"
            >
              上一页
            </Button>
            <span class="page-indicator font-mono-num">第 {{ page + 1 }} 页</span>
            <Button
              variant="ghost"
              size="small"
              :disabled="(page + 1) * 12 >= grouped.length"
              @click="page++"
            >
              下一页
            </Button>
          </nav>
          <section
            v-if="withdrawn.length"
            class="withdrawn-section"
            aria-label="已下架收藏"
          >
            <h3>已下架收藏</h3>
            <div
              v-for="entry in withdrawn"
              :key="entry.problemId"
              class="withdrawn-row"
            >
              <span>{{ entry.title }} · 不可开始</span>
              <Button
                variant="ghost"
                size="small"
                :disabled="favorite.pending.value || favorite.uncertain.value"
                @click="toggle(entry.problemId)"
              >
                取消收藏
              </Button>
            </div>
          </section>
        </aside>

        <section
          v-if="detail"
          class="problem-detail"
          aria-label="题目详情"
        >
          <header class="detail-header">
            <div class="detail-title-wrap">
              <div class="problem-meta font-mono-num">
                <span
                  class="ui-pill"
                  data-tone="accent"
                >JAVA</span>
                <div
                  v-if="activeGroup && activeGroup.versions.length > 1"
                  class="ui-segmented version-switcher"
                  role="group"
                  aria-label="切换题目版本"
                >
                  <button
                    v-for="ver in activeGroup.versions"
                    :key="ver.problemVersionId"
                    type="button"
                    class="ui-segmented-btn font-mono-num"
                    :aria-pressed="detail.problemVersionId === ver.problemVersionId"
                    @click="selectedVersionId = ver.problemVersionId"
                  >
                    v{{ ver.version }}
                  </button>
                </div>
                <span
                  v-else
                  class="ui-pill"
                >v{{ detail.version }}</span>
                <span class="ui-pill">{{ detail.durationSeconds / 60 }} 分钟</span>
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
            <p class="runtime-meta font-mono-num">
              时限 {{ detail.durationSeconds / 60 }} 分钟 · 服务端执行环境
            </p>
          </div>
          <footer class="start-action">
            <div class="start-status">
              <span
                class="ui-status-dot"
                :data-tone="!detail.executionAvailable ? 'warning' : current.data.value ? 'muted' : 'accent'"
              />
              <span
                class="start-status-text"
                :class="{ 'is-warning': !detail.executionAvailable }"
                :role="!detail.executionAvailable ? 'alert' : undefined"
              >
                {{ !detail.executionAvailable ? '该版本执行环境不可用，不能开始。' : current.data.value ? '请先继续或结束当前演练。' : '就绪 · 创建后先检查环境再计时' }}
              </span>
            </div>
            <Tooltip :text="startReason || '进入准备页，检查模型与执行环境'">
              <span :tabindex="startReason ? 0 : undefined"><Button
                variant="primary"
                :disabled="!!startReason"
                @click="start"
              >
                {{ create.pending.value ? '正在创建…' : '准备演练' }}
              </Button></span>
            </Tooltip>
          </footer>
        </section>
      </div>
    </template>
  </main>
</template>

<style scoped>
.catalog-page {
  height: 100dvh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
  background: var(--oc-bg);
}

.catalog-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  padding: 10px 20px;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
  flex-shrink: 0;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  min-width: 0;
}

.catalog-topbar h1 {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
  letter-spacing: -0.02em;
}

.catalog-filters {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.search-box {
  position: relative;
  width: 220px;
  display: flex;
  align-items: center;
}

.search-icon {
  position: absolute;
  left: 9px;
  color: var(--oc-fg-dim);
  pointer-events: none;
}

input[type="search"] {
  width: 100%;
  padding: 5px 10px 5px 29px;
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-control);
  color: var(--oc-fg);
  font-size: 12.5px;
}

.favorite-filter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: var(--radius-control);
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  font-size: 12.5px;
  font-weight: 500;
  color: var(--oc-fg);
  cursor: pointer;
}

.favorite-filter input {
  accent-color: var(--oc-accent);
}

.version-count {
  color: var(--oc-fg-muted);
  font-size: 11.5px;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.current-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  border: 1px solid var(--oc-border);
  background: var(--oc-surface);
  color: var(--oc-fg);
  text-decoration: none;
}

.current-chip-active:hover {
  border-color: var(--oc-border-accent);
  background: var(--oc-surface-2);
}

.current-chip-name {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 550;
}

.current-chip-action {
  color: var(--oc-accent);
  font-weight: 550;
}

.current-chip-error {
  border-color: var(--oc-danger-border);
  color: var(--oc-danger);
}

.history-entry {
  text-decoration: none;
}

.catalog-state-wrap {
  padding: 32px;
  max-width: 680px;
  margin: 0 auto;
  width: 100%;
}

.catalog-alerts {
  padding: 8px 20px;
  border-bottom: 1px solid var(--oc-border);
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  flex-shrink: 0;
}

.inline-alert {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-danger);
}

/* Full-height Master-Detail */
.catalog-columns {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
}

.problem-list-pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
  overflow-y: auto;
}

.problem-list {
  display: grid;
}

.problem-choice {
  position: relative;
  width: 100%;
  padding: 13px 16px;
  text-align: left;
  display: grid;
  gap: 7px;
  color: var(--oc-fg);
  background: transparent;
  border: 0;
  border-left: 2px solid transparent;
  cursor: pointer;
  overflow-wrap: anywhere;
  transition: background-color 120ms var(--oc-ease);
}

.problem-choice:hover {
  background: rgba(255, 255, 255, 0.03);
}

.problem-choice[aria-pressed="true"] {
  background: var(--oc-surface);
  border-left-color: var(--oc-accent);
}

.choice-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.problem-choice strong {
  font-size: 13.5px;
  font-weight: 550;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.choice-star {
  color: var(--oc-warning);
  flex-shrink: 0;
  margin-top: 2px;
}

.choice-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.meta-chip {
  font-size: 11px;
  color: var(--oc-fg-muted);
}

.meta-chip + .meta-chip::before {
  content: '·';
  margin-right: 6px;
  color: var(--oc-fg-dim);
}

.catalog-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 14px;
  border-top: 1px solid var(--oc-border);
  margin-top: auto;
}

.page-indicator {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.withdrawn-section {
  margin-top: auto;
  padding: 12px 16px;
  border-top: 1px solid var(--oc-border);
  background: rgba(255, 255, 255, 0.02);
}

.withdrawn-section h3 {
  margin: 0 0 6px;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--oc-fg-muted);
}

.withdrawn-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  color: var(--oc-fg-muted);
  padding: 4px 0;
  overflow-wrap: anywhere;
}

/* Detail Reading Pane + Sticky Footer */
.problem-detail {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--oc-bg);
}

.detail-header {
  flex-shrink: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  padding: 18px 28px 14px;
  border-bottom: 1px solid var(--oc-border);
}

.detail-title-wrap {
  flex: 1;
  min-width: 0;
}

.detail-header h2 {
  margin: 10px 0 0;
  font-size: 19px;
  font-weight: 650;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.problem-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.version-switcher {
  padding: 2px;
}

.favorite-toggle[aria-pressed="true"] {
  color: var(--oc-warning);
}

.requirements {
  flex: 1;
  overflow: auto;
  min-height: 0;
  padding: 22px 28px 28px;
  max-width: 82ch;
}

.runtime-meta {
  margin: 20px 0 0;
  padding-top: 12px;
  border-top: 1px solid var(--oc-border);
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.start-action {
  flex-shrink: 0;
  border-top: 1px solid var(--oc-border);
  padding: 12px 28px;
  background: var(--oc-bg-subtle);
  display: flex;
  align-items: center;
  gap: 14px;
  justify-content: space-between;
}

.start-status {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.start-status-text {
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.start-status-text.is-warning {
  color: var(--oc-warning);
}

@media (max-width: 850px) {
  .catalog-page {
    height: auto;
    min-height: 100dvh;
    overflow: visible;
  }
  .catalog-topbar {
    padding: 10px 14px;
  }
  .search-box {
    width: 100%;
  }
  .catalog-columns {
    grid-template-columns: minmax(0, 1fr);
  }
  .problem-list-pane {
    border-right: 0;
    border-bottom: 1px solid var(--oc-border);
    max-height: 45dvh;
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
