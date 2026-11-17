<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { session } from '../auth/api';
import SkillPanel from './SkillPanel.vue';
const client = useQueryClient();
const account = useQuery({ queryKey: ['identity-session'], queryFn: ({ signal }) => session(signal), retry: false, gcTime: 0, staleTime: 0, refetchInterval: 30000 });
const now = ref(Date.now());
const timer = globalThis.setInterval(() => { now.value = Date.now(); }, 1000);
onScopeDispose(() => globalThis.clearInterval(timer));
const allowed = computed(() => account.isFetchedAfterMount.value && !account.isError.value && account.data.value?.role === 'USER'
  && !account.data.value.passwordChangeRequired && Date.parse(account.data.value.expiresAt) > now.value);
function unauthorized() {
  client.setQueryData(['identity-session'], null);
  void client.cancelQueries({ queryKey: ['skills'] }); client.removeQueries({ queryKey: ['skills'] });
  void account.refetch();
}
</script>
<template>
  <main class="skill-page">
    <header class="skill-hero">
      <h1>个人 Skill</h1>
      <p>为 Copilot 保存可复用的工作指导，按任务显式选择。</p>
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
      class="skill-alert"
    >
      <p>无法确认账号，已隐藏个人文本。</p>
      <Button @click="account.refetch()">
        重新确认账号
      </Button>
    </div>
    <p
      v-else-if="!allowed"
      class="inline-status"
    >
      请使用已修改临时密码的普通用户账号登录；管理员不能管理个人 Skill。
    </p>
    <template v-else-if="account.data.value">
      <SkillPanel
        :key="account.data.value.id"
        :owner="account.data.value.id"
        @unauthorized="unauthorized"
      />
    </template>
  </main>
</template>
<style scoped>
.skill-page {
  max-width: 1220px;
  margin: 0 auto;
  padding: 36px 32px 64px;
  overflow-wrap: anywhere;
}

.skill-hero {
  margin-bottom: 20px;
}

.skill-hero h1 {
  margin: 0 0 6px;
  font-size: clamp(22px, 2.4vw, 26px);
  font-weight: 650;
  letter-spacing: -0.025em;
}

.skill-hero p {
  margin: 0;
  color: var(--oc-fg-muted);
  line-height: 1.55;
  font-size: 13.5px;
}

.inline-status {
  color: var(--oc-fg-muted);
  font-size: 14px;
}

.skill-alert {
  padding: 18px;
  border-radius: var(--radius-panel);
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 12px;
  max-width: 480px;
}

.skill-alert p {
  margin: 0;
}

@media (max-width: 600px) {
  .skill-page {
    padding: 20px 16px 40px;
  }
}
</style>
