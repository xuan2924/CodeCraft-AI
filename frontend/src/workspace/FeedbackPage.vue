<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { useIntervalFn, useTimestamp } from '@vueuse/core';
import { session } from '../auth/api';
import { RouterLink } from 'vue-router';
import { routes } from '../routes';
import Button from '../components/ui/Button.vue';
import SectionTabs from '../components/ui/SectionTabs.vue';
import StatePanel from '../components/ui/StatePanel.vue';
import FeedbackEntry from './FeedbackEntry.vue';
import { message } from './api';
import * as api from './feedbackApi';
const account = useQuery({ queryKey: ['identity-session'], queryFn: ({ signal }) => session(signal), retry: false });
const owner = computed(() => account.data.value?.id ?? '');
const admin = computed(() => account.data.value?.role === 'ADMIN');
const now = useTimestamp({ scheduler: callback => useIntervalFn(callback, 1000) });
const allowed = computed(() => account.isFetchedAfterMount.value && !!owner.value && !account.isError.value && !account.data.value?.passwordChangeRequired && Date.parse(account.data.value!.expiresAt) > now.value);
const after = ref<string | null>(null);
watch(owner, () => { after.value = null; });
const listing = useQuery({ queryKey: computed(() => ['feedback', owner.value, admin.value, after.value]), gcTime: 0, retry: false, enabled: allowed,
  queryFn: ({ signal }) => api.list(owner.value, admin.value, after.value, signal) });
</script>
<template>
  <main class="feedback-page">
    <header class="feedback-hero">
      <h1>{{ admin ? '授权反馈处理' : '历史演练' }}</h1>
      <SectionTabs
        v-if="allowed && !admin"
        label="历史与反馈切换"
        :active="routes.feedback.name"
        :items="[
          { name: routes.history.name, label: '已结束演练' },
          { name: routes.feedback.name, label: '授权反馈' },
        ]"
      />
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
      description="请登录并完成必要的密码更新。"
    />
    <section
      v-else
      class="feedback-card"
    >
      <div class="feedback-toolbar">
        <h2>反馈记录</h2>
        <Button
          variant="secondary"
          size="small"
          @click="listing.refetch()"
        >
          刷新反馈
        </Button>
      </div>
      <StatePanel
        v-if="listing.isPending.value"
        kind="loading"
        title="正在读取反馈"
        description="只读取授权且仍在保留期内的快照。"
      />
      <StatePanel
        v-else-if="listing.isError.value"
        kind="error"
        title="无法读取反馈"
        :description="message(listing.error.value)"
      />
      <template v-else>
        <StatePanel
          v-if="!listing.data.value?.length"
          title="暂无反馈"
          description="可以从面试官问答或报告页发起授权反馈；已删除演练的反馈不会继续展示。"
        >
          <RouterLink
            v-if="!admin"
            class="ui-button ui-button-secondary"
            :to="{ name: routes.history.name }"
          >查看历史演练与报告</RouterLink>
        </StatePanel>
        <FeedbackEntry
          v-for="item in listing.data.value"
          :key="`${owner}/${item.id}`"
          :owner="owner"
          :admin="admin"
          :value="item"
        />
      </template>
      <div
        v-if="after || listing.data.value?.length === 20"
        class="pagination"
      >
        <Button
          variant="secondary"
          size="small"
          :disabled="!after || listing.isFetching.value"
          @click="after = null"
        >
          返回第一页
        </Button>
        <Button
          variant="secondary"
          size="small"
          :disabled="listing.data.value?.length !== 20 || listing.isFetching.value || listing.isError.value"
          @click="after = listing.data.value?.at(-1)?.id ?? null"
        >
          下一页
        </Button>
      </div>
    </section>
  </main>
</template>
<style scoped>
.feedback-page {
  max-width: 820px;
  padding: 28px 32px 56px;
  margin: 0 auto;
}

.feedback-hero {
  margin-bottom: 20px;
}

h1 {
  margin: 0 0 6px;
  font-size: clamp(22px, 2.4vw, 26px);
  font-weight: 650;
  letter-spacing: -0.025em;
}

.feedback-hero p {
  margin: 0;
  line-height: 1.55;
  color: var(--oc-fg-muted);
  font-size: 13.5px;
}

.feedback-card {
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-panel);
  box-shadow: var(--oc-shadow-sm), var(--oc-hairline-top);
  padding: 24px;
  display: grid;
  gap: 18px;
}

.feedback-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--oc-border);
}

.feedback-toolbar h2 {
  font-size: 16px;
  font-weight: 600;
  margin: 0;
}

a {
  text-decoration: none;
}

.pagination {
  display: flex;
  gap: 12px;
  margin-top: 8px;
}

@media (max-width: 600px) {
  .feedback-page {
    padding: 20px 16px 40px;
  }
  .feedback-card {
    padding: 16px;
  }
}
</style>
