<script setup lang="ts">
import { cva } from 'class-variance-authority';
import { LoaderCircle } from '@lucide/vue';
import { cn } from '../../lib/utils';

defineOptions({ name: 'UiButton' });
withDefaults(defineProps<{
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  size?: 'default' | 'small' | 'icon';
  type?: 'button' | 'submit' | 'reset';
  disabled?: boolean;
  loading?: boolean;
}>(), { variant: 'secondary', size: 'default', type: 'button' });
const emit = defineEmits<{ click: [event: globalThis.MouseEvent] }>();
const variants = cva('ui-button', {
  variants: {
    variant: { primary: 'ui-button-primary', secondary: 'ui-button-secondary', ghost: 'ui-button-ghost', danger: 'ui-button-danger' },
    size: { default: '', small: 'ui-button-small', icon: 'ui-button-icon' },
  },
});
</script>

<template>
  <button
    :type="type"
    :class="cn(variants({ variant, size }))"
    :disabled="disabled || loading"
    :aria-busy="loading || undefined"
    @click="!disabled && !loading && emit('click', $event)"
  >
    <LoaderCircle
      v-if="loading"
      class="ui-spinner"
      :size="16"
      aria-hidden="true"
    />
    <slot />
  </button>
</template>
