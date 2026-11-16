<script setup lang="ts">
import { darkTheme, NConfigProvider } from 'naive-ui';
import { RouterView, useRoute } from 'vue-router';
import { useAccount } from './auth/useAccount';
import { legacyTheme } from './components/ui/legacyTheme';
import AppShell from './shell/AppShell.vue';

const { query, identity } = useAccount();
const route = useRoute();
</script>

<template>
  <NConfigProvider
    :theme="darkTheme"
    :theme-overrides="legacyTheme"
  >
    <AppShell
      :identity="identity"
      :pending="query.isPending.value"
      :failed="query.isError.value"
      @retry="query.refetch()"
    >
      <div :class="{ 'legacy-content': !route.meta.modern }">
        <RouterView />
      </div>
    </AppShell>
  </NConfigProvider>
</template>
