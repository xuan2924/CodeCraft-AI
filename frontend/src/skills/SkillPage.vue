<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
import { RouterLink } from 'vue-router';
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
    <nav aria-label="页面导航">
      <RouterLink :to="{ name: 'account' }">账号</RouterLink>
      <RouterLink :to="{ name: 'workspace' }">工作区</RouterLink>
      <RouterLink :to="{ name: 'models' }">模型连接</RouterLink>
    </nav>
    <h1>个人 Skill 文本库</h1>
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
      <p>无法确认账号，已隐藏个人文本。</p><NButton @click="account.refetch()">
        重新确认账号
      </NButton>
    </div>
    <p v-else-if="!allowed">
      请使用已修改临时密码的普通用户账号登录；管理员不能管理个人 Skill。
    </p>
    <template v-else-if="account.data.value">
      <p>当前账号：{{ account.data.value.username }}</p>
      <SkillPanel
        :key="account.data.value.id"
        :owner="account.data.value.id"
        @unauthorized="unauthorized"
      />
    </template>
    <footer>离开页面不会撤销已提交的变更；结果不明时请重新读取确认。</footer>
  </main>
</template>
<style scoped>
.skill-page { max-width: 1000px; margin: 36px auto; padding: 0 24px; overflow-wrap: anywhere; }
nav { display: flex; gap: 20px; flex-wrap: wrap; }
footer { margin-top: 32px; }
@media (max-width: 480px) { .skill-page { margin: 24px auto; padding: 0 16px; } }
</style>
