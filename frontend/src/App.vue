<script setup lang="ts">
import { RouterView } from 'vue-router';
import { useAccount } from './auth/useAccount';
import AppShell from './shell/AppShell.vue';
const { query, identity } = useAccount();
</script>
<template>
  <AppShell
    :identity="identity"
    :pending="query.isPending.value"
    :failed="query.isError.value"
    @retry="query.refetch()"
  >
    <RouterView />
  </AppShell>
</template>
