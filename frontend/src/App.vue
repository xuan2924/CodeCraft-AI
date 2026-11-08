<script setup lang="ts">
import { NConfigProvider, type GlobalThemeOverrides } from 'naive-ui';
import StatusPage from './status/StatusPage.vue';

const theme: GlobalThemeOverrides = {
  common: {
    primaryColor: '#225c91', primaryColorHover: '#184a77', primaryColorPressed: '#123a60',
    fontFamily: '"Segoe UI", "Microsoft YaHei", sans-serif',
    borderRadius: '4px',
  },
};
</script>

<template>
  <NConfigProvider :theme-overrides="theme">
    <StatusPage />
  </NConfigProvider>
</template>
