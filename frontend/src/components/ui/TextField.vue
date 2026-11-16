<script setup lang="ts">
import { computed, useId } from 'vue';

defineOptions({ inheritAttrs: false });
const props = defineProps<{ id?: string; label: string; help?: string; error?: string }>();
const model = defineModel<string>({ default: '' });
const generatedId = useId();
const inputId = computed(() => props.id ?? `field-${generatedId}`);
const describedBy = computed(() => [props.help && `${inputId.value}-help`, props.error && `${inputId.value}-error`].filter(Boolean).join(' ') || undefined);
</script>

<template>
  <div class="ui-field">
    <label
      class="ui-field-label"
      :for="inputId"
    >{{ label }}</label>
    <input
      :id="inputId"
      v-model="model"
      class="ui-input"
      v-bind="$attrs"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="describedBy"
    >
    <p
      v-if="help"
      :id="`${inputId}-help`"
      class="ui-field-help"
    >
      {{ help }}
    </p>
    <p
      v-if="error"
      :id="`${inputId}-error`"
      class="ui-field-error"
      role="alert"
    >
      {{ error }}
    </p>
  </div>
</template>
