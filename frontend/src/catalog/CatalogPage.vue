<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useIntervalFn, useTimestamp } from '@vueuse/core';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { RouterLink, useRouter } from 'vue-router';
import { ArrowRight, ChevronRight, Play, Search, Star } from '@lucide/vue';
import ProblemRequirements from './ProblemRequirements.vue';
import Button from '../components/ui/Button.vue';
import Dialog from '../components/ui/Dialog.vue';
import SegmentedTabs from '../components/ui/SegmentedTabs.vue';
import StatePanel from '../components/ui/StatePanel.vue';
import Tooltip from '../components/ui/Tooltip.vue';
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
const languageFilter = ref<'ALL' | 'JAVA'>('ALL');
const onlyFavorites = ref(false);
const selectedGroupKey = ref('');
const selectedVersionId = ref('');
const drawerOpen = ref(false);
const page = ref(0);

const languageItems = [
  { id: 'ALL', label: '全部' },
  { id: 'JAVA', label: 'Java' },
];

const saved = computed(() => new Set(favorites.data.value?.map((entry) => entry.problemId)));
const filtered = computed(() => (catalogue.data.value ?? []).filter((entry) => entry.title.toLowerCase().includes(search.value.toLowerCase())
  && (languageFilter.value === 'ALL' || languageFilter.value === 'JAVA')
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
const activeGroup = computed(() => grouped.value.find(g => g.key === selectedGroupKey.value) ?? visibleGroups.value[0]);
const detail = computed(() => {
  const group = activeGroup.value;
  if (!group) return undefined;
  return group.versions.find(v => v.problemVersionId === selectedVersionId.value) ?? group.versions[0];
});
const versionItems = computed(() => (activeGroup.value?.versions ?? []).map(ver => ({
  id: ver.problemVersionId,
  label: `v${ver.version}`,
})));
const withdrawn = computed(() => (favorites.data.value ?? []).filter((entry) => !entry.available));

watch([search, onlyFavorites, languageFilter, owner], () => {
  page.value = 0;
  selectedGroupKey.value = '';
  selectedVersionId.value = '';
  drawerOpen.value = false;
});
watch(owner, () => { search.value = ''; languageFilter.value = 'ALL'; onlyFavorites.value = false; drawerOpen.value = false; });
watch(() => grouped.value.length, () => { if (page.value * 12 >= grouped.value.length) page.value = 0; });

function openGroup(group: ProblemGroup) {
  selectedGroupKey.value = group.key;
  selectedVersionId.value = group.versions[0]?.problemVersionId ?? '';
  drawerOpen.value = true;
}

const policy = { definitiveRejection: (failure: unknown) => failure instanceof ApiError && [400, 401, 403, 404, 409, 429].includes(failure.status),
  errorMessage: workspace.message, identityFailure: (failure: unknown) => failure instanceof ApiError && failure.status === 401 };
const favorite = useCommand<void>(() => owner.value, () => { void client.invalidateQueries({ queryKey: ['favorites', owner.value] }); }, policy);
const create = useCommand<{ exerciseId: string; state: string }>(() => owner.value, (result) => {
  drawerOpen.value = false;
  void client.invalidateQueries({ queryKey: ['workspace', owner.value, 'current'] });
  void router.push({ name: routes.workspace.name, query: { exercise: result.exerciseId } });
}, policy);
const startReason = computed(() => !detail.value?.executionAvailable ? '该版本执行环境不可用' : current.data.value ? '请先继续或结束当前演练'
  : current.isPending.value || current.isError.value ? '请先确认进行中演练' : create.pending.value ? '正在创建，请稍候'
  : create.uncertain.value ? '请核对原创建操作' : '');

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
      <div class="header-title-group">
        <h1>题库</h1>
        <span
          v-if="allowed"
          class="catalog-count font-mono-num"
        >{{ grouped.length }} 题</span>
      </div>
      <div
        v-if="allowed"
        class="header-actions"
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
          :title="currentTitle"
        >
          <span
            class="ui-status-dot"
            data-tone="accent"
          />
          <span class="current-chip-name">{{ currentTitle }}</span>
          <span class="current-chip-action">继续</span>
          <Play
            :size="12"
            aria-hidden="true"
          />
        </RouterLink>
        <RouterLink
          class="ui-button ui-button-ghost ui-button-small history-link"
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
      class="catalog-state"
    >
      <StatePanel
        kind="loading"
        title="正在确认身份"
        description="请稍候。"
      />
    </div>
    <div
      v-else-if="!allowed"
      class="catalog-state"
    >
      <StatePanel
        kind="error"
        title="需要有效登录"
        description="请登录并完成账号初始化。"
      >
        <RouterLink :to="{ name: routes.account.name }">
          前往账号页
        </RouterLink>
      </StatePanel>
    </div>
    <template v-else>
      <div class="catalog-toolbar">
        <div class="toolbar-filters">
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
          <SegmentedTabs
            :model-value="languageFilter"
            :items="languageItems"
            label="语言筛选"
            size="compact"
            @update:model-value="(val) => (languageFilter = val as 'ALL' | 'JAVA')"
          />
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
      </div>

      <div
        v-if="favorites.isError.value || favorite.error.value || favorite.uncertain.value || create.error.value || create.uncertain.value"
        class="catalog-alerts"
      >
        <p
          v-if="favorites.isError.value"
          role="alert"
          class="inline-alert"
        >
          <span>收藏读取失败。</span>
          <Button
            variant="ghost"
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
          <span>{{ favorite.error.value }}</span>
        </p>
        <div
          v-if="favorite.uncertain.value"
          class="inline-alert"
        >
          <span>收藏状态待确认。</span>
          <Button
            size="small"
            :disabled="favorite.pending.value"
            @click="favorite.retry()"
          >
            核对并重试原收藏操作
          </Button>
        </div>
        <p
          v-if="create.error.value"
          role="alert"
          class="inline-alert"
        >
          <span>{{ create.error.value }}</span>
        </p>
        <div
          v-if="create.uncertain.value"
          class="inline-alert"
        >
          <span>创建结果待确认。</span>
          <Button
            size="small"
            :disabled="create.pending.value"
            @click="create.retry()"
          >
            核对并重试原创建
          </Button>
        </div>
      </div>

      <div
        v-if="catalogue.isPending.value"
        class="catalog-state"
      >
        <StatePanel
          kind="loading"
          title="正在加载题库"
          description="请稍候。"
        />
      </div>
      <div
        v-else-if="catalogue.isError.value"
        class="catalog-state"
      >
        <StatePanel
          kind="error"
          title="题库加载失败"
          description="请重试以获取最新题目。"
        >
          <Button @click="catalogue.refetch()">
            重试
          </Button>
        </StatePanel>
      </div>
      <div
        v-else-if="!grouped.length"
        class="catalog-state"
      >
        <StatePanel
          title="没有符合筛选的题目"
          description="调整搜索词或取消仅收藏后重试。"
        />
      </div>
      <section
        v-else
        class="catalog-list-section"
        aria-label="已发布题目"
      >
        <div class="problem-list ui-divide-list">
          <div
            v-for="group in visibleGroups"
            :key="group.key"
            class="problem-row"
            :class="{ 'is-selected': drawerOpen && activeGroup?.key === group.key }"
          >
            <Tooltip :text="group.title">
              <button
                type="button"
                class="problem-choice"
                @click="openGroup(group)"
              >
                <span class="choice-main">
                  <strong class="choice-title">{{ group.title }}</strong>
                  <span class="choice-meta font-mono-num">
                    <span>Java</span>
                    <span>{{ group.versions.length > 1 ? `${group.versions.length} 版本` : `v${group.versions[0]?.version}` }}</span>
                    <span>{{ (group.versions[0]?.durationSeconds ?? 0) / 60 }} 分钟</span>
                    <span
                      v-if="!group.versions.some(v => v.executionAvailable)"
                      class="choice-warn"
                    >
                      <span
                        class="ui-status-dot"
                        data-tone="warning"
                      />
                      环境不可用
                    </span>
                  </span>
                </span>
                <ChevronRight
                  :size="15"
                  class="choice-chevron"
                  aria-hidden="true"
                />
              </button>
            </Tooltip>
            <Button
              v-if="group.problemId"
              variant="ghost"
              size="icon"
              class="row-fav-btn"
              :aria-label="saved.has(group.problemId) ? '取消收藏' : '收藏题目'"
              :aria-pressed="saved.has(group.problemId)"
              :disabled="favorite.pending.value || favorite.uncertain.value || favorites.isError.value || favorites.isPending.value"
              @click="toggle(group.problemId)"
            >
              <Star
                :size="15"
                :fill="saved.has(group.problemId) ? 'currentColor' : 'none'"
              />
            </Button>
          </div>
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
          <h2>已下架收藏</h2>
          <div class="withdrawn-list ui-divide-list">
            <div
              v-for="entry in withdrawn"
              :key="entry.problemId"
              class="withdrawn-row"
            >
              <span class="withdrawn-title">{{ entry.title }}</span>
              <span class="withdrawn-badge">不可开始</span>
              <Button
                variant="ghost"
                size="small"
                :disabled="favorite.pending.value || favorite.uncertain.value"
                @click="toggle(entry.problemId)"
              >
                取消收藏
              </Button>
            </div>
          </div>
        </section>
      </section>
    </template>

    <Dialog
      :open="drawerOpen && !!detail"
      variant="drawer"
      width="wide"
      :title="detail?.title ?? '题目详情'"
      @update:open="(val) => (drawerOpen = val)"
    >
      <template
        v-if="detail"
        #header-extra
      >
        <div class="drawer-meta-bar">
          <div class="drawer-meta-left font-mono-num">
            <span class="drawer-tag">Java</span>
            <SegmentedTabs
              v-if="activeGroup && activeGroup.versions.length > 1"
              v-model="selectedVersionId"
              :items="versionItems"
              label="切换题目版本"
              size="compact"
            />
            <span
              v-else
              class="drawer-version"
            >v{{ detail.version }}</span>
            <span class="drawer-duration">时限 {{ detail.durationSeconds / 60 }} 分钟</span>
          </div>
          <Button
            v-if="detail.problemId"
            variant="ghost"
            size="small"
            class="drawer-fav-btn"
            :aria-pressed="saved.has(detail.problemId)"
            :disabled="favorite.pending.value || favorite.uncertain.value || favorites.isError.value || favorites.isPending.value"
            @click="toggle(detail.problemId)"
          >
            <Star
              :size="14"
              :fill="saved.has(detail.problemId) ? 'currentColor' : 'none'"
            />
            <span>{{ saved.has(detail.problemId) ? '已收藏' : '收藏' }}</span>
          </Button>
        </div>
      </template>

      <ProblemRequirements
        v-if="detail"
        :source="detail.requirements"
        :problem-title="detail.title"
      />

      <template
        v-if="detail"
        #footer
      >
        <div class="drawer-footer-bar">
          <div class="drawer-footer-status">
            <span
              class="ui-status-dot"
              :data-tone="!detail.executionAvailable ? 'warning' : current.data.value ? 'muted' : 'accent'"
            />
            <span
              class="status-label"
              :class="{ 'is-warning': !detail.executionAvailable }"
              :role="!detail.executionAvailable ? 'alert' : undefined"
            >
              {{ !detail.executionAvailable ? '该版本执行环境不可用' : current.data.value ? '已有进行中的演练' : '就绪' }}
            </span>
          </div>
          <div class="drawer-footer-actions">
            <Button
              v-if="create.uncertain.value"
              variant="secondary"
              size="small"
              :disabled="create.pending.value"
              @click="create.retry()"
            >
              核对并重试
            </Button>
            <Tooltip :text="startReason || '准备并检查运行环境'">
              <span :tabindex="startReason ? 0 : undefined">
                <Button
                  variant="primary"
                  :disabled="!!startReason"
                  @click="start"
                >
                  {{ create.pending.value ? '正在创建…' : '准备演练' }}
                </Button>
              </span>
            </Tooltip>
          </div>
        </div>
      </template>
    </Dialog>
  </main>
</template>

<style scoped>
.catalog-page {
  max-width: 920px;
  margin: 0 auto;
  padding: 28px 24px 56px;
  min-width: 0;
}

.catalog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.header-title-group {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.header-title-group h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.catalog-count {
  font-size: 12px;
  color: var(--oc-fg-dim);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex-wrap: wrap;
}

.current-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  max-width: 280px;
  min-height: 30px;
  padding: 0 10px;
  border-radius: 999px;
  border: 1px solid var(--oc-border-strong);
  background: var(--oc-surface);
  color: var(--oc-fg);
  font-size: 12.5px;
  text-decoration: none;
}

.current-chip-active:hover {
  border-color: var(--oc-accent-border);
  background: var(--oc-accent-bg);
}

.current-chip-error {
  border-color: var(--oc-danger-border);
  background: var(--oc-danger-bg);
  color: var(--oc-danger);
}

.current-chip-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.current-chip-action {
  color: var(--oc-accent);
  font-weight: 500;
  flex-shrink: 0;
}

.history-link {
  text-decoration: none;
}

.catalog-toolbar {
  margin: 18px 0 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.toolbar-filters {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 240px;
  flex-wrap: wrap;
}

.search-box {
  position: relative;
  flex: 1;
  min-width: 190px;
  max-width: 300px;
}

.search-icon {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--oc-fg-dim);
  pointer-events: none;
}

.search-box input {
  width: 100%;
  min-height: 32px;
  padding: 5px 10px 5px 30px;
  font-size: 13px;
}

.favorite-filter {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  color: var(--oc-fg-muted);
  cursor: pointer;
  flex-shrink: 0;
}

.favorite-filter input {
  min-height: 0;
}

.catalog-alerts {
  display: grid;
  gap: 8px;
  margin-bottom: 14px;
}

.inline-alert {
  margin: 0;
  padding: 8px 12px;
  border-radius: var(--radius-control);
  background: var(--oc-danger-bg);
  border: 1px solid var(--oc-danger-border);
  color: var(--oc-danger);
  font-size: 12.5px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.catalog-state {
  margin-top: 24px;
}

.problem-list {
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: 10px;
  overflow: hidden;
}

.problem-row {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  transition: background 140ms var(--oc-ease);
}

.problem-row:hover,
.problem-row.is-selected {
  background: rgba(255, 255, 255, 0.035);
}

.problem-choice {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 12px 14px 16px;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.problem-choice:focus-visible {
  outline: none;
  box-shadow: inset 0 0 0 1px var(--oc-focus-ring);
}

.choice-main {
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.choice-title {
  min-width: 0;
  font-size: 14px;
  font-weight: 550;
  color: var(--oc-fg);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

.choice-meta {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.choice-warn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--oc-warning);
}

.choice-chevron {
  flex-shrink: 0;
  color: var(--oc-fg-dim);
}

.row-fav-btn {
  flex-shrink: 0;
  margin-right: 10px;
  color: var(--oc-fg-dim);
}

.row-fav-btn[aria-pressed="true"] {
  color: var(--oc-warning);
}

.catalog-pagination {
  margin-top: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.page-indicator {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.withdrawn-section {
  margin-top: 24px;
}

.withdrawn-section h2 {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 500;
  color: var(--oc-fg-dim);
}

.withdrawn-list {
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: 10px;
}

.withdrawn-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  min-width: 0;
  font-size: 13px;
}

.withdrawn-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--oc-fg-muted);
}

.withdrawn-badge {
  font-size: 12px;
  color: var(--oc-fg-dim);
  flex-shrink: 0;
}

.drawer-meta-bar {
  margin-top: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.drawer-meta-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.drawer-tag {
  padding: 2px 7px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--oc-fg);
}

.drawer-fav-btn {
  flex-shrink: 0;
}

.drawer-fav-btn[aria-pressed="true"] {
  color: var(--oc-warning);
}

.drawer-footer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.drawer-footer-status {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.status-label.is-warning {
  color: var(--oc-warning);
}

.drawer-footer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

@media (max-width: 640px) {
  .catalog-page {
    padding: 18px 16px 40px;
  }

  .choice-main {
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }
}
</style>
