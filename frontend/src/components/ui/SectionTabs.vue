<script setup lang="ts">
import { RouterLink } from 'vue-router';

export interface SectionTab {
  name: string;
  label: string;
}

defineProps<{
  label: string;
  active: string;
  items: SectionTab[];
}>();
</script>

<template>
  <nav
    class="section-tabs ui-segmented"
    :aria-label="label"
  >
    <RouterLink
      v-for="item in items"
      :key="item.name"
      :to="{ name: item.name }"
      class="ui-segmented-btn"
      :class="{ 'is-active': active === item.name }"
      :aria-current="active === item.name ? 'page' : undefined"
    >
      {{ item.label }}
    </RouterLink>
  </nav>
</template>

<style scoped>
.section-tabs {
  margin-bottom: 16px;
}
</style>
