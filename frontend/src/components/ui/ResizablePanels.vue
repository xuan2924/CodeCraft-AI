<script setup lang="ts">
import { SplitterGroup, SplitterPanel, SplitterResizeHandle } from 'reka-ui';
withDefaults(defineProps<{
  firstLabel: string; secondLabel: string; resizeLabel: string;
  direction?: 'horizontal' | 'vertical'; firstSize?: number; minSize?: number;
  firstHidden?: boolean; secondHidden?: boolean;
}>(), { direction: 'horizontal', firstSize: 40, minSize: 20 });
</script>

<template>
  <SplitterGroup
    :direction="direction"
    class="ui-splitter"
  >
    <SplitterPanel
      v-show="!firstHidden"
      class="ui-splitter-panel"
      :default-size="firstSize"
      :min-size="minSize"
      :aria-label="firstLabel"
      role="region"
    >
      <slot name="first" />
    </SplitterPanel>
    <SplitterResizeHandle
      v-show="!firstHidden && !secondHidden"
      class="ui-splitter-handle"
      :aria-label="resizeLabel"
    />
    <SplitterPanel
      v-show="!secondHidden"
      class="ui-splitter-panel"
      :default-size="100 - firstSize"
      :min-size="minSize"
      :aria-label="secondLabel"
      role="region"
    >
      <slot name="second" />
    </SplitterPanel>
  </SplitterGroup>
</template>
