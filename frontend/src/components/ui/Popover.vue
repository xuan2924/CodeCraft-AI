<script setup lang="ts">
import { PopoverAnchor, PopoverContent, PopoverPortal, PopoverRoot, PopoverTrigger } from 'reka-ui';

defineOptions({ name: 'UiPopover' });
withDefaults(defineProps<{
  side?: 'top' | 'right' | 'bottom' | 'left';
  align?: 'start' | 'center' | 'end';
  sideOffset?: number;
  label?: string;
  anchorClass?: string;
  contentClass?: string;
}>(), {
  side: 'bottom',
  align: 'start',
  sideOffset: 6,
  label: undefined,
  anchorClass: undefined,
  contentClass: undefined,
});

const open = defineModel<boolean>('open', { default: false });
</script>

<template>
  <PopoverRoot v-model:open="open">
    <PopoverAnchor
      v-if="$slots.anchor"
      :class="anchorClass"
    >
      <slot name="anchor" />
    </PopoverAnchor>
    <PopoverTrigger
      v-if="$slots.trigger"
      as-child
    >
      <slot name="trigger" />
    </PopoverTrigger>
    <PopoverPortal>
      <PopoverContent
        :side="side"
        :align="align"
        :side-offset="sideOffset"
        :aria-label="label"
        :class="['ui-popover', contentClass]"
        @escape-key-down="open = false"
      >
        <slot />
      </PopoverContent>
    </PopoverPortal>
  </PopoverRoot>
</template>
