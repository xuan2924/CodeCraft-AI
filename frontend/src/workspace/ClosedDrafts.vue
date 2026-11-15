<script setup lang="ts">
import { onScopeDispose, ref } from 'vue';
import { NButton } from 'naive-ui';
import { listAccountDrafts, type Draft } from './drafts';

const props = defineProps<{ owner: string; exerciseId: string; active?: boolean }>();
const drafts = ref<Draft[]>([]);
const loading = ref(false);
const failed = ref(false);
let disposed = false;
async function refresh() {
  if (loading.value || disposed) return;
  loading.value = true;
  failed.value = false;
  try {
    const values = await listAccountDrafts(props.owner);
    if (!disposed) drafts.value = values.filter((value) => value.exercise === props.exerciseId);
  } catch { if (!disposed) failed.value = true; }
  finally { if (!disposed) loading.value = false; }
}
void refresh();
onScopeDispose(() => { disposed = true; });
</script>

<template>
  <section :aria-label="active ? '本场本地草稿备份' : '结束后的本地草稿'">
    <h2>本地草稿（只读，可选择复制）</h2>
    <p>{{ active ? '仅此浏览器的未保存备份，不是正式代码；可选择复制，不会自动提交。' : '仅此浏览器的未保存备份，不属于最终冻结代码，不会自动提交。' }}</p>
    <p v-if="loading" role="status">正在读取本地备份…</p>
    <p v-else-if="failed" role="alert">无法读取本地草稿，请重试；不会因此删除备份。</p>
    <p v-else-if="!drafts.length">没有找到本场本地备份。</p>
    <NButton :disabled="loading" @click="refresh">刷新本地备份</NButton>
    <details v-for="draft in drafts" :key="draft.path + ':' + draft.id">
      <summary>{{ draft.path }} · {{ new Date(draft.updatedAt).toLocaleString() }}</summary>
      <pre tabindex="0">{{ draft.content }}</pre>
    </details>
  </section>
</template>

<style scoped>
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 45vh; overflow: auto; }
</style>
