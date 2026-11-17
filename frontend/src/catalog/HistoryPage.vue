<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useIntervalFn, useTimestamp } from '@vueuse/core';
import { useQuery } from '@tanstack/vue-query';
import { RouterLink, useRouter } from 'vue-router';
import { ArrowLeft } from '@lucide/vue';
import { session } from '../auth/api';
import SectionTabs from '../components/ui/SectionTabs.vue';
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
      <div class="history-title-wrap">
        <h1>历史演练</h1>
        <SectionTabs
          v-if="allowed"
          label="历史与反馈切换"
          :active="routes.history.name"
          :items="[
            { name: routes.history.name, label: '已结束演练' },
            { name: routes.feedback.name, label: '授权反馈' },
          ]"
        />
      </div>
      <RouterLink
        class="ui-button ui-button-secondary ui-button-small"
        :to="{ name: routes.catalog.name }"
      >
        <ArrowLeft
          :size="14"
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
  max-width: 920px;
  margin: 0 auto;
  padding: 28px 24px 56px;
  min-width: 0;
}

.history-hero {
  margin-bottom: 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 14px;
}

.history-title-wrap {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.history-hero h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.history-hero a {
  text-decoration: none;
}

.history-error {
  margin-top: 14px;
  padding: 8px 12px;
  border-radius: var(--radius-control);
  background: var(--oc-danger-bg);
  border: 1px solid var(--oc-danger-border);
  color: var(--oc-danger);
  font-size: 12.5px;
}

@media (max-width: 640px) {
  .history-page {
    padding: 18px 16px 40px;
  }
}
</style>
