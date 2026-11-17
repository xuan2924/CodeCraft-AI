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
    class="section-tabs"
    :aria-label="label"
  >
    <RouterLink
      v-for="item in items"
      :key="item.name"
      :to="{ name: item.name }"
      class="section-tab"
      :class="{ 'is-active': active === item.name }"
      :aria-current="active === item.name ? 'page' : undefined"
    >
      {{ item.label }}
    </RouterLink>
  </nav>
</template>

<style scoped>
.section-tabs {
  display: flex;
  align-items: center;
  gap: 20px;
  border-bottom: 1px solid var(--oc-border);
  min-width: 0;
  overflow-x: auto;
}

.section-tab {
  position: relative;
  display: inline-flex;
  align-items: center;
  padding: 9px 2px 10px;
  color: var(--oc-fg-muted);
  font-size: 13px;
  font-weight: 500;
  text-decoration: none;
  white-space: nowrap;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: color 140ms var(--oc-ease), border-color 140ms var(--oc-ease);
}

.section-tab:hover {
  color: var(--oc-fg);
}

.section-tab.is-active {
  color: var(--oc-fg);
  font-weight: 550;
  border-bottom-color: var(--oc-accent);
}
</style>
