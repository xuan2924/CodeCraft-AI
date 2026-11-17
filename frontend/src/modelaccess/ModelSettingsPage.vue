<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { RouterLink } from 'vue-router';
import { session } from '../auth/api';
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
  <main
    class="settings-page"
  >
    <header class="settings-hero">
      <div>
        <h1>个人模型连接</h1>
        <p>你的连接、Key 与模型配置仅供本人使用。保存与检查分开进行。</p>
      </div>
      <p
        v-if="allowed && account.data.value"
        class="owner-badge"
      >
        当前账号：{{ account.data.value.username }}
      </p>
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
      <Button @click="account.refetch()">
        重新确认账号
      </Button>
    </div>
    <div
      v-else-if="!allowed"
      class="settings-denied"
    >
      <p>请使用已修改临时密码的普通用户账号登录；管理员不能配置个人模型。</p>
      <RouterLink
        class="ui-button ui-button-secondary"
        :to="{ name: 'account' }"
      >前往账号页</RouterLink>
    </div>
    <template v-else-if="account.data.value">
      <ModelSettingsPanel
        :key="account.data.value.id"
        :owner="account.data.value.id"
        @unauthorized="unauthorized"
      />
    </template>
  </main>
</template>
<style scoped>
.settings-page {
  max-width: 1220px;
  margin: 0 auto;
  padding: 36px 32px 64px;
  overflow-wrap: anywhere;
}

.settings-hero {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.settings-hero h1 {
  margin: 0 0 6px;
  font-size: clamp(22px, 2.4vw, 26px);
  font-weight: 650;
  letter-spacing: -0.025em;
}

.settings-hero p:not(.owner-badge) {
  margin: 0;
  color: var(--oc-fg-muted);
  font-size: 13.5px;
  line-height: 1.55;
}

.owner-badge {
  margin: 0;
  padding: 5px 12px;
  border-radius: 999px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.inline-status {
  color: var(--oc-fg-muted);
  font-size: 14px;
}

.settings-alert,
.settings-denied {
  padding: 20px;
  border-radius: var(--radius-panel);
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 12px;
  max-width: 520px;
}

.settings-alert p,
.settings-denied p {
  margin: 0;
}

.settings-denied a {
  width: fit-content;
  text-decoration: none;
}

@media (max-width: 600px) {
  .settings-page {
    padding: 20px 16px 40px;
  }
}
</style>
