<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import NButton from '../components/ui/Button.vue';
import { RouterLink } from 'vue-router';
import { session } from '../auth/api';
import ModelSettingsPanel from './ModelSettingsPanel.vue';
const theme = { primaryColor: 'var(--accent)' };
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
    :style="{ '--primary': theme.primaryColor }"
  >
    <header>
      <nav aria-label="页面导航">
        <RouterLink :to="{ name: 'account' }">账号</RouterLink><RouterLink :to="{ name: 'workspace' }">工作区</RouterLink>
      </nav>
      <h1>个人模型连接</h1>
      <p>你的连接、Key 与模型配置仅供本人使用。保存与检查分开进行。</p>
    </header>
    <p
      v-if="account.isPending.value || !account.isFetchedAfterMount.value"
      role="status"
    >
      正在确认当前账号…
    </p>
    <div
      v-else-if="account.isError.value"
      role="alert"
    >
      <p>无法确认当前账号，已隐藏个人配置。</p><NButton @click="account.refetch()">
        重新确认账号
      </NButton>
    </div>
    <template v-else-if="!allowed">
      <p>请使用已修改临时密码的普通用户账号登录；管理员不能配置个人模型。</p><RouterLink :to="{ name: 'account' }">前往账号页</RouterLink>
    </template>
    <template v-else-if="account.data.value">
      <p class="owner">
        当前账号：{{ account.data.value.username }}
      </p>
      <ModelSettingsPanel
        :key="account.data.value.id"
        :owner="account.data.value.id"
        @unauthorized="unauthorized"
      />
    </template>
    <footer>离开页面不保证取消已提交的保存或删除，请重新读取确认。当前页面不自动修改演练的模型选择；演练内 Copilot 选模尚未接入。</footer>
  </main>
</template>
<style scoped>
.settings-page { max-width: 1160px; margin: 36px auto; padding: 0 24px; overflow-wrap: anywhere; }
nav { display: flex; gap: 20px; } a { color: var(--primary); }
header { margin-bottom: 24px; }
.owner { color: var(--muted); }
@media (max-width: 480px) { .settings-page { margin: 24px auto; padding: 0 16px; } }
</style>
