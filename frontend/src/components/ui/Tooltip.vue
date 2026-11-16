<script setup lang="ts">
import { TooltipContent, TooltipPortal, TooltipProvider, TooltipRoot, TooltipTrigger } from 'reka-ui';
defineOptions({ name: 'UiTooltip' });
defineProps<{ text: string }>();
</script>
<template>
  <TooltipProvider :delay-duration="200">
    <TooltipRoot>
      <TooltipTrigger as-child>
        <slot />
      </TooltipTrigger>
      <TooltipPortal>
        <TooltipContent
          class="ui-tooltip"
          :side-offset="6"
        >
          {{ text }}
        </TooltipContent>
      </TooltipPortal>
    </TooltipRoot>
  </TooltipProvider>
</template>
<style>
.ui-tooltip { z-index: 60; max-width: min(400px, calc(100vw - 32px)); padding: 8px 12px; border: 1px solid var(--control-border); border-radius: var(--radius-control); background: var(--surface); color: var(--foreground); overflow-wrap: anywhere; font-size: 13px; }
</style>
