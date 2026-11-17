<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { RouterLink } from 'vue-router';
import { Search } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import StatePanel from '../components/ui/StatePanel.vue';
import { routes } from '../routes';
import ExerciseRetentionEntry from './ExerciseRetentionEntry.vue';
import { history } from './retentionApi';

const props = defineProps<{ owner: string }>();
const emit = defineEmits<{ deleted: [id: string, owner: string]; select: [id: string] }>();
const after = ref<string>();
const search = ref('');
const status = ref('all');
const query = useQuery({
  queryKey: ['workspace', computed(() => props.owner), 'history', after],
  queryFn: ({ signal }) => history(signal, after.value, props.owner),
  retry: false,
  refetchInterval: 5000,
});
watch(() => props.owner, () => { after.value = undefined; search.value = ''; status.value = 'all'; });
const entries = computed(() => (query.data.value ?? []).filter((entry) => entry.exerciseId.includes(search.value)
  && (status.value === 'all' || status.value === 'retained' && entry.deletionState === null || status.value === 'cleanup' && entry.deletionState !== null)));
</script>

<template>
  <section
    class="retention-panel"
    aria-label="历史演练与删除"
  >
    <div class="history-toolbar">
      <div class="toolbar-title">
        <h2>已结束演练</h2>
        <details class="retention-rule">
          <summary>保留规则</summary>
          <p>按真实到期日保留。达到数量上限后先删除旧演练，不会自动淘汰未到期记录。</p>
        </details>
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
            placeholder="按演练标识筛选…"
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
              演练标识
            </th>
            <th scope="col">
              结束时间
            </th>
            <th scope="col">
              保留至
            </th>
            <th scope="col">
              状态
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
              <code>{{ entry.exerciseId }}</code>
            </td>
            <td class="time-cell tabular">
              {{ new Date(entry.endedAt).toLocaleString() }}
            </td>
            <td class="time-cell tabular">
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
                  variant="secondary"
                  size="small"
                  @click="emit('select', entry.exerciseId)"
                >
                  查看演练
                </Button>
                <details class="cleanup-details">
                  <summary>删除与清理详情</summary>
                  <ExerciseRetentionEntry
                    :owner="owner"
                    :entry="entry"
                    @deleted="(id, user) => emit('deleted', id, user)"
                    @select="emit('select', $event)"
                  />
                </details>
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
  </section>
</template>

<style scoped>
.retention-panel {
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-panel);
  box-shadow: var(--oc-shadow-xs), var(--oc-hairline-top);
  overflow: hidden;
}

.history-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px 20px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-surface-2);
}

.toolbar-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
  min-width: 0;
}

.toolbar-title h2 {
  font-size: 14.5px;
  font-weight: 600;
  margin: 0;
}

.retention-rule {
  position: relative;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.retention-rule summary {
  cursor: pointer;
  user-select: none;
}

.retention-rule summary:hover {
  color: var(--oc-fg);
}

.retention-rule p {
  margin: 6px 0 0;
  max-width: 52ch;
  line-height: 1.5;
  color: var(--oc-fg-muted);
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
  width: 210px;
  padding: 6px 10px 6px 29px;
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
  padding: 6px 10px;
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
  vertical-align: top;
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
  transition: background-color 140ms var(--oc-ease);
}

tbody tr:hover {
  background: var(--oc-surface-2);
}

.identity-cell {
  width: 240px;
  overflow-wrap: anywhere;
}

.identity-cell code {
  display: inline-block;
  padding: 2px 7px;
  border-radius: 5px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  font-size: 12px;
}

.tabular {
  font-variant-numeric: tabular-nums;
}

.time-cell {
  width: 170px;
  color: var(--oc-fg-muted);
}

.state-cell {
  width: 110px;
}

.action-cell {
  width: 260px;
}

.action-row {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

.cleanup-details {
  width: 100%;
}

.cleanup-details summary {
  cursor: pointer;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.cleanup-details summary:hover {
  color: var(--oc-fg);
}

.history-pagination {
  display: flex;
  gap: 10px;
  padding: 14px 18px;
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
