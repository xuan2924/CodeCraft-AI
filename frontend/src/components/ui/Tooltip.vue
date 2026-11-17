<script setup lang="ts">
import { computed } from 'vue';
import { TooltipContent, TooltipPortal, TooltipProvider, TooltipRoot, TooltipTrigger } from 'reka-ui';

defineOptions({ name: 'UiTooltip' });
const props = defineProps<{ text?: string; content?: string }>();
const label = computed(() => props.text ?? props.content ?? '');
</script>
<template>
  <TooltipProvider :delay-duration="200">
    <TooltipRoot>
      <TooltipTrigger as-child>
        <slot />
      </TooltipTrigger>
      <TooltipPortal>
        <TooltipContent
          v-if="label"
          class="ui-tooltip"
          :side-offset="6"
        >
          {{ label }}
        </TooltipContent>
      </TooltipPortal>
    </TooltipRoot>
  </TooltipProvider>
</template>
<style>
.ui-tooltip {
  z-index: 60;
  max-width: min(360px, calc(100vw - 32px));
  padding: 6px 10px;
  border: 1px solid var(--oc-border-strong);
  border-radius: var(--radius-control);
  background: var(--oc-surface-2);
  color: var(--oc-fg);
  box-shadow: var(--oc-shadow-md), var(--oc-hairline-top);
  overflow-wrap: break-word;
  word-break: normal;
  font-size: 12px;
  line-height: 1.45;
}
</style>
