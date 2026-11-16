<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
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
    <h2>已结束演练</h2>
    <p>按真实到期日保留。达到数量上限后先删除旧演练，不会自动淘汰未到期记录。</p>
    <div class="history-filters">
      <label>筛选本页演练 <input
        v-model="search"
        type="search"
        maxlength="128"
      ></label><label>状态 <select v-model="status"><option value="all">全部</option><option value="retained">保留中</option><option value="cleanup">清理中 / 失败</option></select></label>
    </div>
    <p
      v-if="query.isPending.value"
      role="status"
    >
      正在读取历史演练…
    </p>
    <p
      v-else-if="query.isError.value"
      role="alert"
    >
      无法读取历史演练，请重试；不会因此清除草稿。
    </p>
    <p v-else-if="!entries.length">
      没有符合条件的历史演练。
    </p>
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
            </th><th scope="col">
              结束时间
            </th><th scope="col">
              保留至
            </th><th scope="col">
              状态
            </th><th scope="col">
              查看 / 清理
            </th>
          </tr>
        </thead><tbody>
          <tr
            v-for="entry in entries"
            :key="`${owner}:${entry.exerciseId}`"
          >
            <td class="identity-cell">
              <code>{{ entry.exerciseId }}</code>
            </td>
            <td>{{ new Date(entry.endedAt).toLocaleString() }}</td><td>{{ new Date(entry.retainUntil).toLocaleString() }}</td>
            <td>{{ entry.deletionState === null ? '保留中' : entry.deletionState === 'FAILED' ? '清理失败' : entry.deletionState === 'SUCCEEDED' ? '已删除' : '清理中' }}</td>
            <td>
              <Button
                v-if="entry.deletionState === null"
                variant="secondary"
                @click="emit('select', entry.exerciseId)"
              >
                查看演练
              </Button><details>
                <summary>删除与清理详情</summary><ExerciseRetentionEntry
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
    <nav aria-label="历史分页">
      <Button
        v-if="query.isError.value"
        @click="query.refetch()"
      >
        重试读取历史
      </Button><Button
        v-if="after"
        variant="secondary"
        @click="after = undefined"
      >
        返回第一页
      </Button><Button
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
.history-filters { display: flex; gap: 16px; flex-wrap: wrap; margin: 20px 0; }
input, select { color: var(--foreground); background: var(--background); border: 1px solid var(--control-border); padding: 8px; border-radius: var(--radius-control); max-width: 100%; }
.history-scroll { overflow-x: auto; } table { border-collapse: collapse; width: 100%; min-width: 780px; text-align: left; }
th, td { padding: 12px; border-bottom: 1px solid var(--border); vertical-align: top; } th { color: var(--muted); font-size: 12px; }
.identity-cell { width: 240px; overflow-wrap: anywhere; } td:nth-child(2), td:nth-child(3) { width: 160px; } td:nth-child(4) { width: 90px; }
summary { cursor: pointer; margin: 10px 0; } nav { display: flex; gap: 12px; margin-top: 16px; }
</style>
