<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { useIntervalFn, useTimestamp } from '@vueuse/core';
import { session } from '../auth/api';
import { RouterLink } from 'vue-router';
import { routes } from '../routes';
import Button from '../components/ui/Button.vue';
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
const listing = useQuery({
  queryKey: computed(() => ['feedback', owner.value, admin.value, after.value]),
  gcTime: 0,
  retry: false,
  enabled: allowed,
  queryFn: ({ signal }) => api.list(owner.value, admin.value, after.value, signal),
});
</script>

<template>
  <main class="feedback-page">
    <header class="feedback-header">
      <h1>{{ admin ? '授权反馈处理' : '历史演练' }}</h1>
      <nav
        v-if="allowed && !admin"
        class="ui-underline-tabs"
        aria-label="历史与反馈切换"
      >
        <RouterLink
          class="ui-underline-tab"
          :to="{ name: routes.history.name }"
        >
          已结束演练
        </RouterLink>
        <RouterLink
          class="ui-underline-tab is-active"
          aria-current="page"
          :to="{ name: routes.feedback.name }"
        >
          授权反馈
        </RouterLink>
      </nav>
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
      description="请登录并完成密码更新。"
    />
    <section
      v-else
      class="feedback-stream"
    >
      <div class="feedback-toolbar">
        <h2>反馈记录</h2>
        <Button
          variant="ghost"
          size="small"
          :disabled="listing.isFetching.value"
          @click="listing.refetch()"
        >
          刷新
        </Button>
      </div>

      <p
        v-if="listing.isPending.value"
        role="status"
        class="inline-status"
      >
        正在读取反馈…
      </p>
      <StatePanel
        v-else-if="listing.isError.value"
        kind="error"
        title="无法读取反馈"
        :description="message(listing.error.value)"
      />
      <template v-else>
        <div
          v-if="!listing.data.value?.length"
          class="empty-surface"
        >
          <p>尚无反馈记录。</p>
          <RouterLink
            v-if="!admin"
            class="ui-button ui-button-secondary ui-button-small"
            :to="{ name: routes.history.name }"
          >查看历史演练</RouterLink>
        </div>
        <div
          v-else
          class="feedback-entries"
        >
          <FeedbackEntry
            v-for="item in listing.data.value"
            :key="`${owner}/${item.id}`"
            :owner="owner"
            :admin="admin"
            :value="item"
          />
        </div>
      </template>

      <div
        v-if="after || listing.data.value?.length === 20"
        class="pagination"
      >
        <Button
          variant="ghost"
          size="small"
          :disabled="!after || listing.isFetching.value"
          @click="after = null"
        >
          返回第一页
        </Button>
        <Button
          variant="ghost"
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
  max-width: 768px;
  padding: 32px 24px 64px;
  margin: 0 auto;
  min-width: 0;
}

.feedback-header {
  display: grid;
  gap: 12px;
  margin-bottom: 24px;
}

h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.ui-underline-tabs a {
  text-decoration: none;
}

.feedback-stream {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.feedback-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.feedback-toolbar h2 {
  font-size: 13px;
  font-weight: 600;
  color: var(--oc-fg-muted);
  margin: 0;
}

.inline-status {
  margin: 0;
  font-size: 13px;
  color: var(--oc-fg-muted);
}

.empty-surface {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 16px;
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  font-size: 13px;
  color: var(--oc-fg-muted);
}

.empty-surface p {
  margin: 0;
}

.empty-surface a {
  text-decoration: none;
}

.feedback-entries {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.pagination {
  display: flex;
  gap: 8px;
}

@media (max-width: 640px) {
  .feedback-page {
    padding: 20px 16px 40px;
  }
}
</style>
