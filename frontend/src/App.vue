<script setup lang="ts">
import { darkTheme, NConfigProvider } from 'naive-ui';
import { RouterView } from 'vue-router';
import { useAccount } from './auth/useAccount';
import { legacyTheme } from './components/ui/legacyTheme';
import AppShell from './shell/AppShell.vue';

const { query, identity } = useAccount();
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
      <div class="legacy-content">
        <RouterView />
      </div>
    </AppShell>
  </NConfigProvider>
</template>
