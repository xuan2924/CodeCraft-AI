<script setup lang="ts">
import { AlertCircle, Inbox, LoaderCircle } from '@lucide/vue';
withDefaults(defineProps<{ kind?: 'loading' | 'empty' | 'error'; title: string; description: string }>(), { kind: 'empty' });
</script>

<template>
  <div
    class="ui-state"
    :data-kind="kind"
    :role="kind === 'error' ? 'alert' : 'status'"
    :aria-busy="kind === 'loading' || undefined"
  >
    <div class="ui-state-icon">
      <component
        :is="kind === 'loading' ? LoaderCircle : kind === 'error' ? AlertCircle : Inbox"
        :class="{ 'ui-spinner': kind === 'loading' }"
        :size="22"
        aria-hidden="true"
      />
    </div>
    <strong>{{ title }}</strong><p>{{ description }}</p><slot />
  </div>
</template>
