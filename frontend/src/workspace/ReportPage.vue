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
    <nav aria-label="报告导航">
      <RouterLink :to="{ name: routes.history.name }">历史演练</RouterLink><RouterLink
        v-if="exercise"
        :to="{ name: routes.workspace.name, query: { exercise } }"
      >查看原工作区</RouterLink>
    </nav>
    <h1>回顾与评价</h1>
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
.report-page { max-width:1280px; margin:auto; padding:32px 24px; } nav { display:flex; gap:24px; } h1 { margin:24px 0 32px; font-size:20px; color:var(--muted); } @media(max-width:600px) { .report-page { padding:16px; } }
</style>
