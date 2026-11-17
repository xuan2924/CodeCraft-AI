<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { RouterLink } from 'vue-router';
import { Search, Trash2 } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import Dialog from '../components/ui/Dialog.vue';
import StatePanel from '../components/ui/StatePanel.vue';
import Tooltip from '../components/ui/Tooltip.vue';
import { routes } from '../routes';
import ExerciseRetentionEntry from './ExerciseRetentionEntry.vue';
import { history, type HistoryEntry } from './retentionApi';

const props = defineProps<{ owner: string }>();
const emit = defineEmits<{ deleted: [id: string, owner: string]; select: [id: string] }>();
const after = ref<string>();
const search = ref('');
const status = ref('all');
const cleanupTarget = ref<HistoryEntry | null>(null);

const query = useQuery({
  queryKey: ['workspace', computed(() => props.owner), 'history', after],
  queryFn: ({ signal }) => history(signal, after.value, props.owner),
  retry: false,
  refetchInterval: 5000,
});

watch(() => props.owner, () => {
  after.value = undefined;
  search.value = '';
  status.value = 'all';
  cleanupTarget.value = null;
});

const entries = computed(() => {
  const q = search.value.trim().toLowerCase();
  return (query.data.value ?? []).filter((entry) => {
    const matchesSearch = !q
      || entry.exerciseId.toLowerCase().includes(q)
      || (entry.problemTitle?.toLowerCase().includes(q) ?? false);
    const matchesStatus = status.value === 'all'
      || status.value === 'retained' && entry.deletionState === null
      || status.value === 'cleanup' && entry.deletionState !== null;
    return matchesSearch && matchesStatus;
  });
});

watch(entries, (list) => {
  if (!cleanupTarget.value) return;
  const fresh = list.find(e => e.exerciseId === cleanupTarget.value!.exerciseId);
  if (fresh) cleanupTarget.value = fresh;
});

const verificationLabels: Record<string, { label: string; tone?: string }> = {
  PASSED: { label: '验收通过', tone: 'accent' },
  FAILED: { label: '验收未通过', tone: 'danger' },
  TIMED_OUT: { label: '验收超时', tone: 'warning' },
  UNAVAILABLE: { label: '结果不可用', tone: 'warning' },
  PENDING: { label: '待核对' },
};
</script>

<template>
  <section
    class="retention-panel ui-surface-flat"
    aria-label="历史演练与删除"
  >
    <div class="history-toolbar">
      <div class="toolbar-title">
        <h2>已结束演练</h2>
        <Tooltip text="按真实到期日保留；达到数量上限后先删除旧演练，不会自动淘汰未到期记录。">
          <button
            type="button"
            class="rule-hint"
            aria-label="保留规则说明"
          >
            ?
          </button>
        </Tooltip>
      </div>
      <div class="history-filters">
        <label class="filter-search">
          <span class="sr-only">筛选本页演练</span>
          <Search
            :size="14"
            class="filter-search-icon"
            aria-hidden="true"
          />
          <input
            v-model="search"
            type="search"
            aria-label="筛选本页演练"
            placeholder="按题目或标识筛选…"
            maxlength="128"
          >
        </label>
        <label class="filter-select">
          <span>状态</span>
          <select v-model="status">
            <option value="all">全部</option>
            <option value="retained">保留中</option>
            <option value="cleanup">清理中 / 失败</option>
          </select>
        </label>
      </div>
    </div>

    <p
      v-if="query.isPending.value"
      role="status"
      class="inline-status"
    >
      正在读取历史演练…
    </p>
    <p
      v-else-if="query.isError.value"
      role="alert"
      class="inline-alert"
    >
      无法读取历史演练，请重试；不会因此清除草稿。
    </p>
    <StatePanel
      v-else-if="!entries.length"
      title="没有符合条件的历史演练。"
      description="完成一场演练后，可以在这里查看结果与报告。"
    >
      <RouterLink
        class="ui-button ui-button-secondary ui-button-small"
        :to="{ name: routes.catalog.name }"
      >前往题库开始演练</RouterLink>
    </StatePanel>
    <div
      v-else
      class="history-scroll"
      role="region"
      aria-label="历史演练表格"
      tabindex="0"
    >
      <table>
        <thead>
          <tr>
            <th scope="col">
              题目与标识
            </th>
            <th scope="col">
              验收结果
            </th>
            <th scope="col">
              结束时间
            </th>
            <th scope="col">
              保留至
            </th>
            <th scope="col">
              保留状态
            </th>
            <th scope="col">
              操作
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="entry in entries"
            :key="`${owner}:${entry.exerciseId}`"
          >
            <td class="identity-cell">
              <template v-if="entry.problemTitle">
                <div class="title-line">
                  <strong>{{ entry.problemTitle }}</strong>
                  <span
                    v-if="entry.problemVersion"
                    class="version-tag font-mono-num"
                  >v{{ entry.problemVersion }}</span>
                </div>
                <code
                  class="short-id font-mono-num"
                  :title="entry.exerciseId"
                >#{{ entry.exerciseId.slice(0, 8) }}</code>
              </template>
              <code
                v-else
                class="font-mono-num"
              >{{ entry.exerciseId }}</code>
            </td>
            <td class="verification-cell">
              <span
                v-if="entry.finalVerification"
                class="ui-badge"
                :data-tone="verificationLabels[entry.finalVerification]?.tone"
              >
                {{ verificationLabels[entry.finalVerification]?.label ?? entry.finalVerification }}
              </span>
              <span
                v-else
                class="muted-dash"
              >-</span>
            </td>
            <td class="time-cell font-mono-num">
              {{ new Date(entry.endedAt).toLocaleString() }}
            </td>
            <td class="time-cell font-mono-num">
              {{ new Date(entry.retainUntil).toLocaleString() }}
            </td>
            <td class="state-cell">
              <span
                class="ui-pill retention-badge"
                :data-state="entry.deletionState ?? 'RETAINED'"
                :data-tone="entry.deletionState === null ? 'accent' : entry.deletionState === 'FAILED' ? 'danger' : entry.deletionState === 'SUCCEEDED' ? 'muted' : 'warning'"
              >
                {{ entry.deletionState === null ? '保留中' : entry.deletionState === 'FAILED' ? '清理失败' : entry.deletionState === 'SUCCEEDED' ? '已删除' : '清理中' }}
              </span>
            </td>
            <td class="action-cell">
              <div class="action-row">
                <Button
                  v-if="entry.deletionState === null"
                  variant="ghost"
                  size="small"
                  @click="emit('select', entry.exerciseId)"
                >
                  查看演练
                </Button>
                <Button
                  variant="ghost"
                  size="small"
                  class="cleanup-trigger"
                  :aria-label="`删除或查看清理状态 ${entry.problemTitle ?? entry.exerciseId}`"
                  @click="cleanupTarget = entry"
                >
                  <Trash2
                    :size="13"
                    aria-hidden="true"
                  />
                  <span>{{ entry.deletionState ? '清理进度' : '删除' }}</span>
                </Button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <nav
      aria-label="历史分页"
      class="history-pagination"
    >
      <Button
        v-if="query.isError.value"
        size="small"
        @click="query.refetch()"
      >
        重试读取历史
      </Button>
      <Button
        v-if="after"
        variant="secondary"
        size="small"
        @click="after = undefined"
      >
        返回第一页
      </Button>
      <Button
        v-if="query.data.value?.length === 50"
        variant="secondary"
        size="small"
        @click="after = query.data.value.at(-1)?.exerciseId"
      >
        下一页
      </Button>
    </nav>

    <Dialog
      :open="!!cleanupTarget"
      :title="cleanupTarget?.problemTitle ? `管理演练：${cleanupTarget.problemTitle}` : '演练删除与清理'"
      description="删除将撤销服务端访问并清除此浏览器对应草稿，不可恢复。"
      @update:open="!$event && (cleanupTarget = null)"
    >
      <ExerciseRetentionEntry
        v-if="cleanupTarget"
        :key="`${owner}:${cleanupTarget.exerciseId}`"
        :owner="owner"
        :entry="cleanupTarget"
        @deleted="(id, user) => emit('deleted', id, user)"
        @select="(id) => { cleanupTarget = null; emit('select', id); }"
      />
    </Dialog>
  </section>
</template>

<style scoped>
.retention-panel {
  overflow: hidden;
}

.history-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px 18px;
  padding: 12px 18px;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
}

.toolbar-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.toolbar-title h2 {
  font-size: 14px;
  font-weight: 600;
  margin: 0;
}

.rule-hint {
  width: 20px;
  height: 20px;
  border-radius: 999px;
  border: 1px solid var(--oc-border);
  background: var(--oc-surface);
  color: var(--oc-fg-muted);
  font-size: 11px;
  cursor: help;
}

.history-filters {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.filter-search {
  position: relative;
  display: flex;
  align-items: center;
}

.filter-search-icon {
  position: absolute;
  left: 9px;
  color: var(--oc-fg-dim);
  pointer-events: none;
}

.filter-search input {
  width: 220px;
  padding: 5px 10px 5px 29px;
  font-size: 12.5px;
}

.filter-select {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.filter-select select {
  padding: 5px 10px;
  font-size: 12.5px;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
}

a {
  text-decoration: none;
}

.history-scroll {
  overflow-x: auto;
}

table {
  border-collapse: collapse;
  width: 100%;
  min-width: 780px;
  text-align: left;
  table-layout: fixed;
}

th,
td {
  padding: 12px 16px;
  border-bottom: 1px solid var(--oc-border);
  vertical-align: middle;
  font-size: 13px;
}

th {
  color: var(--oc-fg-muted);
  font-size: 11.5px;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.015);
}

tbody tr:last-child td {
  border-bottom: 0;
}

tbody tr {
  transition: background-color 120ms var(--oc-ease);
}

tbody tr:hover {
  background: var(--oc-surface-2);
}

.identity-cell {
  width: 240px;
  overflow-wrap: anywhere;
}

.title-line {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.title-line strong {
  font-size: 13.5px;
  font-weight: 550;
  color: var(--oc-fg);
}

.version-tag {
  font-size: 11px;
  color: var(--oc-fg-muted);
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--oc-bg-input);
}

.short-id {
  display: inline-block;
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--oc-fg-dim);
}

.verification-cell {
  width: 120px;
}

.muted-dash {
  color: var(--oc-fg-dim);
}

.time-cell {
  width: 165px;
  color: var(--oc-fg-muted);
  font-size: 12px;
}

.state-cell {
  width: 105px;
}

.action-cell {
  width: 170px;
}

.action-row {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

.cleanup-trigger {
  color: var(--oc-fg-muted);
}

.cleanup-trigger:hover {
  color: var(--oc-danger);
}

.history-pagination {
  display: flex;
  gap: 10px;
  padding: 12px 18px;
  border-top: 1px solid var(--oc-border);
}

.history-pagination:empty {
  display: none;
}

.inline-status {
  margin: 0;
  padding: 18px;
  color: var(--oc-fg-muted);
  font-size: 13px;
}

.inline-alert {
  margin: 0;
  padding: 18px;
  color: var(--oc-danger);
  font-size: 13px;
}

@media (max-width: 600px) {
  .history-toolbar {
    padding: 12px 14px;
  }
  .filter-search input {
    width: 100%;
  }
}
</style>
