<script setup lang="ts">
import { computed } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { useIntervalFn, useTimestamp } from '@vueuse/core';
import { RouterLink, useRoute } from 'vue-router';
import { session } from '../auth/api';
import { routes } from '../routes';
import StatePanel from '../components/ui/StatePanel.vue';
import ReportPanel from './ReportPanel.vue';
const route = useRoute();
const exercise = computed(() => typeof route.query.exercise === 'string' ? route.query.exercise : '');
const account = useQuery({ queryKey: ['identity-session'], queryFn: ({ signal }) => session(signal), retry: false });
const now = useTimestamp({ scheduler: callback => useIntervalFn(callback, 1000) });
const owner = computed(() => account.data.value?.id ?? '');
const allowed = computed(() => account.isFetchedAfterMount.value && !account.isError.value && account.data.value?.role === 'USER' && !account.data.value.passwordChangeRequired && Date.parse(account.data.value.expiresAt) > now.value);
</script>
<template>
  <main class="report-page">
    <nav
      aria-label="报告导航"
      class="report-nav"
    >
      <RouterLink
        class="nav-pill"
        :to="{ name: routes.history.name }"
      >← 历史演练</RouterLink>
      <RouterLink
        v-if="exercise"
        class="nav-pill"
        :to="{ name: routes.workspace.name, query: { exercise } }"
      >查看原工作区</RouterLink>
    </nav>
    <header class="report-page-header">
      <h1>回顾与评价</h1>
    </header>
    <StatePanel
      v-if="account.isPending.value || !account.isFetchedAfterMount.value"
      kind="loading"
      title="正在确认身份"
      description="报告仅限本人读取。"
    />
    <StatePanel
      v-else-if="!allowed || !exercise"
      kind="error"
      title="无法打开报告"
      description="需要有效的个人账号和演练身份。请登录后从历史演练进入。"
    />
    <ReportPanel
      v-else
      :key="`${owner}/${exercise}`"
      :owner="owner"
      :exercise-id="exercise"
    />
  </main>
</template>
<style scoped>
.report-page {
  max-width: 1260px;
  margin: 0 auto;
  padding: 32px 32px 64px;
}

.report-nav {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 18px;
}

.nav-pill {
  display: inline-flex;
  align-items: center;
  padding: 6px 12px;
  border-radius: 999px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  color: var(--oc-fg-muted);
  text-decoration: none;
  font-size: 12.5px;
  font-weight: 500;
  transition: color 160ms var(--oc-ease), border-color 160ms var(--oc-ease), background-color 160ms var(--oc-ease);
}

.nav-pill:hover {
  color: var(--oc-fg);
  border-color: var(--oc-border-strong);
  background: var(--oc-surface-2);
}

.report-page-header {
  margin-bottom: 18px;
}

h1 {
  margin: 0;
  font-size: clamp(22px, 2.4vw, 26px);
  font-weight: 650;
  letter-spacing: -0.025em;
}

@media (max-width: 600px) {
  .report-page {
    padding: 20px 16px 40px;
  }
}
</style>
