<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useIntervalFn, useTimestamp } from '@vueuse/core';
import { useQuery } from '@tanstack/vue-query';
import { RouterLink, useRouter } from 'vue-router';
import { ArrowLeft } from '@lucide/vue';
import { session } from '../auth/api';
import StatePanel from '../components/ui/StatePanel.vue';
import WorkspaceRetention from '../workspace/WorkspaceRetention.vue';
import { confirmExerciseDeletion } from '../workspace/drafts';
import { routes } from '../routes';
const account = useQuery({ queryKey: ['identity-session'], queryFn: ({ signal }) => session(signal), retry: false });
const owner = computed(() => account.data.value?.id ?? '');
const now = useTimestamp({ scheduler: callback => useIntervalFn(callback, 1000) });
const allowed = computed(() => account.isFetchedAfterMount.value && !!owner.value && account.data.value?.role === 'USER'
  && !account.data.value.passwordChangeRequired && !account.isError.value && Date.parse(account.data.value.expiresAt) > now.value);
const router = useRouter();
const error = ref('');
watch(owner, () => { error.value = ''; });
async function deleted(id: string, user: string) {
  if (user !== owner.value) return;
  try { await confirmExerciseDeletion(user, id); }
  catch { if (user === owner.value) error.value = '服务端访问已撤销，但本地草稿清理失败。请刷新后重试清理或清除本站浏览器数据。'; }
}
</script>
<template>
  <main class="history-page">
    <header class="history-hero">
      <div>
        <p class="eyebrow">
          CODECRAFT / HISTORY &amp; RETENTION
        </p>
        <h1>历史演练</h1>
        <p>查看已结束的练习、冻结结果及真实保留状态。</p>
      </div>
      <RouterLink
        class="ui-button ui-button-secondary"
        :to="{ name: routes.catalog.name }"
      >
        <ArrowLeft
          :size="15"
          aria-hidden="true"
        />
        <span>前往题库</span>
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
      class="history-error"
    >
      {{ error }}
    </p>
  </main>
</template>
<style scoped>
.history-page {
  max-width: 1220px;
  margin: 0 auto;
  padding: 36px 32px 64px;
  min-width: 0;
}

.history-hero {
  margin-bottom: 28px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 20px;
}

.eyebrow {
  margin: 0 0 8px;
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.1em;
  color: var(--oc-accent);
}

.history-hero h1 {
  margin: 0 0 8px;
  font-size: clamp(28px, 3vw, 34px);
  font-weight: 650;
  letter-spacing: -0.03em;
}

.history-hero p:not(.eyebrow) {
  margin: 0;
  color: var(--oc-fg-muted);
  font-size: 14px;
  line-height: 1.65;
}

.history-hero a {
  text-decoration: none;
}

.history-error {
  margin-top: 16px;
  padding: 12px 16px;
  border-radius: 10px;
  background: var(--oc-danger-bg);
  border: 1px solid var(--oc-danger-border);
  color: var(--oc-danger);
  font-size: 13.5px;
}

@media (max-width: 600px) {
  .history-page {
    padding: 20px 16px 40px;
  }
}
</style>
