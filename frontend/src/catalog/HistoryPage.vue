<script setup lang="ts">
import { computed, ref } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { RouterLink, useRouter } from 'vue-router';
import { session } from '../auth/api';
import StatePanel from '../components/ui/StatePanel.vue';
import WorkspaceRetention from '../workspace/WorkspaceRetention.vue';
import { confirmExerciseDeletion } from '../workspace/drafts';
import { routes } from '../routes';
const account = useQuery({ queryKey: ['identity-session'], queryFn: ({ signal }) => session(signal), retry: false });
const owner = computed(() => account.data.value?.id ?? '');
const router = useRouter();
const error = ref('');
async function deleted(id: string, user: string) {
  if (user !== owner.value) return;
  try { await confirmExerciseDeletion(user, id); }
  catch { if (user === owner.value) error.value = '服务端访问已撤销，但本地草稿清理失败。请刷新后重试清理或清除本站浏览器数据。'; }
}
</script>
<template>
  <main class="history-page">
    <header><p>CODECRAFT / HISTORY</p><h1>历史演练</h1><p>查看已结束的练习、冻结结果及真实保留状态。</p><RouterLink :to="{ name: routes.catalog.name }">返回题库</RouterLink></header>
    <StatePanel
      v-if="account.isPending.value"
      kind="loading"
      title="正在确认身份"
      description="请稍候。"
    />
    <StatePanel
      v-else-if="!owner || account.isError.value || account.data.value?.passwordChangeRequired"
      kind="error"
      title="需要有效登录"
      description="请前往账号页登录或完成改密。"
    />
    <WorkspaceRetention
      v-else
      :key="owner"
      :owner="owner"
      @deleted="deleted"
      @select="(id) => router.push({ name: routes.workspace.name, query: { exercise: id } })"
    />
    <p
      v-if="error"
      role="alert"
    >
      {{ error }}
    </p>
  </main>
</template>
<style scoped>
.history-page { max-width: 1320px; margin: auto; padding: 24px; min-width: 0; }
header { margin-bottom: 32px; } header p { color: var(--muted); } h1 { font-size: 32px; }
@media (max-width: 600px) { .history-page { padding: 16px; } }
</style>
