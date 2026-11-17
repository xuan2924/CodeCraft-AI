<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { RouterLink } from 'vue-router';
import { session } from '../auth/api';
import { routes } from '../routes';
import ModelSettingsPanel from './ModelSettingsPanel.vue';

const client = useQueryClient();
const account = useQuery({ queryKey: ['identity-session'], queryFn: ({ signal }) => session(signal), retry: false, gcTime: 0, staleTime: 0, refetchInterval: 30000 });
const now = ref(Date.now());
const timer = globalThis.setInterval(() => { now.value = Date.now(); }, 1000);
onScopeDispose(() => globalThis.clearInterval(timer));
const allowed = computed(() => account.isFetchedAfterMount.value && !account.isError.value && account.data.value?.role === 'USER'
  && !account.data.value.passwordChangeRequired && Date.parse(account.data.value.expiresAt) > now.value);
function unauthorized() {
  client.setQueryData(['identity-session'], null);
  void client.cancelQueries({ queryKey: ['model-access'] });
  client.removeQueries({ queryKey: ['model-access'] });
  void account.refetch();
}
</script>

<template>
  <main class="settings-page">
    <header class="settings-header">
      <div class="header-top-row">
        <h1>AI 配置</h1>
        <span
          v-if="allowed && account.data.value"
          class="owner-pill font-mono-num"
        >{{ account.data.value.username }}</span>
      </div>
      <nav
        v-if="allowed"
        class="ui-underline-tabs"
        aria-label="AI 配置切换"
      >
        <RouterLink
          class="ui-underline-tab is-active"
          aria-current="page"
          :to="{ name: routes.models.name }"
        >
          模型接入
        </RouterLink>
        <RouterLink
          class="ui-underline-tab"
          :to="{ name: routes.skills.name }"
        >
          个人 Skill
        </RouterLink>
      </nav>
    </header>

    <p
      v-if="account.isPending.value || !account.isFetchedAfterMount.value"
      role="status"
      class="inline-status"
    >
      正在确认当前账号…
    </p>
    <div
      v-else-if="account.isError.value"
      role="alert"
      class="settings-alert"
    >
      <p>无法确认当前账号，已隐藏个人配置。</p>
      <Button
        size="small"
        @click="account.refetch()"
      >
        重试确认
      </Button>
    </div>
    <div
      v-else-if="!allowed"
      class="settings-denied"
    >
      <p>请使用已修改临时密码的普通用户账号登录；管理员不能配置个人模型。</p>
      <RouterLink
        class="ui-button ui-button-secondary ui-button-small"
        :to="{ name: 'account' }"
      >前往账号页</RouterLink>
    </div>
    <ModelSettingsPanel
      v-else-if="account.data.value"
      :key="account.data.value.id"
      :owner="account.data.value.id"
      @unauthorized="unauthorized"
    />
  </main>
</template>

<style scoped>
.settings-page {
  max-width: 768px;
  margin: 0 auto;
  padding: 32px 24px 64px;
  min-width: 0;
}

.settings-header {
  display: grid;
  gap: 12px;
  margin-bottom: 24px;
}

.header-top-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.settings-header h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.owner-pill {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.ui-underline-tabs a {
  text-decoration: none;
}

.inline-status {
  color: var(--oc-fg-muted);
  font-size: 13px;
}

.settings-alert,
.settings-denied {
  padding: 16px;
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 10px;
  font-size: 13px;
}

.settings-alert p,
.settings-denied p {
  margin: 0;
}

.settings-denied a {
  width: fit-content;
  text-decoration: none;
}

@media (max-width: 640px) {
  .settings-page {
    padding: 20px 16px 40px;
  }
}
</style>
