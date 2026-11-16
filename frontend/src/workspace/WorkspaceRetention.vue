<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import StatePanel from '../components/ui/StatePanel.vue';
import { RouterLink } from 'vue-router';
import { routes } from '../routes';
import ExerciseRetentionEntry from './ExerciseRetentionEntry.vue';
import { history } from './retentionApi';
const props = defineProps<{ owner: string }>();
const emit = defineEmits<{ deleted: [id: string, owner: string]; select: [id: string] }>();
const after = ref<string>();
const search = ref('');
const status = ref('all');
const query = useQuery({ queryKey: ['workspace', computed(() => props.owner), 'history', after],
  queryFn: ({ signal }) => history(signal, after.value, props.owner), retry: false, refetchInterval: 5000 });
watch(() => props.owner, () => { after.value = undefined; search.value = ''; status.value = 'all'; });
const entries = computed(() => (query.data.value ?? []).filter((entry) => entry.exerciseId.includes(search.value)
  && (status.value === 'all' || status.value === 'retained' && entry.deletionState === null || status.value === 'cleanup' && entry.deletionState !== null)));
</script>
<template>
  <section
    class="retention-panel"
    aria-label="历史演练与删除"
  >
    <div class="history-heading">
      <h2>已结束演练</h2>
      <details class="retention-rule">
        <summary>保留规则</summary>
        <p>按真实到期日保留。达到数量上限后先删除旧演练，不会自动淘汰未到期记录。</p>
      </details>
    </div>
    <div class="history-filters">
      <label>
        <span>筛选本页演练</span>
        <input
          v-model="search"
          type="search"
          placeholder="输入演练标识…"
          maxlength="128"
        >
      </label>
      <label>
        <span>状态</span>
        <select v-model="status">
          <option value="all">全部</option>
          <option value="retained">保留中</option>
          <option value="cleanup">清理中 / 失败</option>
        </select>
      </label>
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
        class="ui-button ui-button-secondary"
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
              查看 / 清理
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
            <td class="tabular">
              {{ new Date(entry.endedAt).toLocaleString() }}
            </td>
            <td class="tabular">
              {{ new Date(entry.retainUntil).toLocaleString() }}
            </td>
            <td>
              <span
                class="retention-badge"
                :data-state="entry.deletionState ?? 'RETAINED'"
              >
                {{ entry.deletionState === null ? '保留中' : entry.deletionState === 'FAILED' ? '清理失败' : entry.deletionState === 'SUCCEEDED' ? '已删除' : '清理中' }}
              </span>
            </td>
            <td class="action-cell">
              <Button
                v-if="entry.deletionState === null"
                variant="secondary"
                size="small"
                @click="emit('select', entry.exerciseId)"
              >
                查看演练
              </Button>
              <details>
                <summary>删除与清理详情</summary>
                <ExerciseRetentionEntry
                  :owner="owner"
                  :entry="entry"
                  @deleted="(id, user) => emit('deleted', id, user)"
                  @select="emit('select', $event)"
                />
              </details>
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
        @click="query.refetch()"
      >
        重试读取历史
      </Button>
      <Button
        v-if="after"
        variant="secondary"
        @click="after = undefined"
      >
        返回第一页
      </Button>
      <Button
        v-if="query.data.value?.length === 50"
        variant="secondary"
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
  box-shadow: var(--oc-shadow-sm), var(--oc-hairline-top);
  padding: 24px;
}

.history-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 16px;
}

.history-heading h2 {
  font-size: 17px;
  font-weight: 600;
  margin: 0;
}

.retention-rule {
  font-size: 12.5px;
  color: var(--oc-fg-muted);
  max-width: 55ch;
}

.retention-rule summary {
  cursor: pointer;
}

.retention-rule p {
  margin: 8px 0 0;
  line-height: 1.6;
}

.history-filters {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  padding: 18px 0;
  margin: 18px 0 18px;
  border-top: 1px solid var(--oc-border);
  border-bottom: 1px solid var(--oc-border);
}

.history-filters label {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 13px;
  color: var(--oc-fg-muted);
}

a {
  text-decoration: none;
}

input,
select {
  color: var(--oc-fg);
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border-input);
  padding: 8px 11px;
  border-radius: var(--radius-control);
  max-width: 100%;
  transition: border-color 180ms var(--oc-ease), box-shadow 180ms var(--oc-ease);
}

input:focus-visible,
select:focus-visible {
  border-color: var(--oc-accent);
  box-shadow: 0 0 0 3px var(--oc-accent-glow);
}

.history-scroll {
  overflow-x: auto;
  border: 1px solid var(--oc-border);
  border-radius: 10px;
}

table {
  border-collapse: collapse;
  width: 100%;
  min-width: 780px;
  text-align: left;
}

th,
td {
  padding: 14px 16px;
  border-bottom: 1px solid var(--oc-border);
  vertical-align: top;
  font-size: 13.5px;
}

th {
  color: var(--oc-fg-muted);
  font-size: 12px;
  font-weight: 550;
  background: rgba(255, 255, 255, 0.02);
}

tbody tr:last-child td {
  border-bottom: 0;
}

tbody tr {
  transition: background-color 150ms var(--oc-ease);
}

tbody tr:hover {
  background: rgba(255, 255, 255, 0.02);
}

.identity-cell {
  width: 240px;
  overflow-wrap: anywhere;
}

.identity-cell code {
  padding: 3px 7px;
  border-radius: 6px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  font-size: 12px;
}

.tabular {
  font-variant-numeric: tabular-nums;
}

td:nth-child(2),
td:nth-child(3) {
  width: 170px;
}

td:nth-child(4) {
  width: 110px;
}

.retention-badge {
  display: inline-flex;
  align-items: center;
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 550;
  background: var(--oc-accent-soft);
  border: 1px solid var(--oc-border-accent);
  color: var(--oc-accent);
  white-space: nowrap;
}

.retention-badge[data-state="FAILED"] {
  background: var(--oc-danger-bg);
  border-color: var(--oc-danger-border);
  color: var(--oc-danger);
}

.retention-badge[data-state="SUCCEEDED"] {
  background: var(--oc-surface-2);
  border-color: var(--oc-border);
  color: var(--oc-fg-muted);
}

.action-cell summary {
  cursor: pointer;
  margin-top: 8px;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.history-pagination {
  display: flex;
  gap: 12px;
  margin-top: 18px;
}

.inline-status {
  color: var(--oc-fg-muted);
  font-size: 13.5px;
}

.inline-alert {
  color: var(--oc-danger);
  font-size: 13.5px;
}

@media (max-width: 600px) {
  .retention-panel {
    padding: 16px;
  }
}
</style>
