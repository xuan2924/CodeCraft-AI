<script setup lang="ts">
export interface SegmentedItem {
  id: string;
  label: string;
  badge?: string | number;
  disabled?: boolean;
}

defineOptions({ name: 'UiSegmentedTabs' });
defineProps<{
  label: string;
  items: SegmentedItem[];
  size?: 'default' | 'compact';
}>();

const active = defineModel<string>({ required: true });
</script>

<template>
  <div
    class="ui-segmented"
    :class="{ 'ui-segmented-compact': size === 'compact' }"
    role="tablist"
    :aria-label="label"
  >
    <button
      v-for="item in items"
      :key="item.id"
      type="button"
      role="tab"
      class="ui-segmented-btn"
      :aria-selected="active === item.id"
      :disabled="item.disabled"
      @click="active = item.id"
    >
      <span>{{ item.label }}</span>
      <span
        v-if="item.badge !== undefined && item.badge !== ''"
        class="ui-segmented-badge font-mono-num"
      >{{ item.badge }}</span>
    </button>
  </div>
</template>
