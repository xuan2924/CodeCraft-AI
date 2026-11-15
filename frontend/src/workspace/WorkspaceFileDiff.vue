<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue';
import type { FileDifference } from './fileDifference';
const props = withDefaults(defineProps<{ files: FileDifference[]; scope: string; label: string; emptyMessage?: string }>(), {
  emptyMessage: '受管理文件没有变化。',
});
const CodeDiff = defineAsyncComponent(() => import('../components/CodeDiff.vue'));
const chosenFile = ref('');
watch(() => props.scope, () => { chosenFile.value = props.files[0]?.path ?? ''; }, { flush: 'sync' });
watch(() => props.files, files => {
  if (!files.some(file => file.path === chosenFile.value)) chosenFile.value = files[0]?.path ?? '';
}, { immediate: true });
const file = computed(() => props.files.find(value => value.path === chosenFile.value) ?? props.files[0]);
</script>

<template>
  <div class="file-diff">
    <p v-if="files.length === 0">
      {{ emptyMessage }}
    </p>
    <label v-if="file">比较文件<select
      v-model="chosenFile"
      :aria-label="label"
    >
      <option
        v-for="change in files"
        :key="change.path"
        :value="change.path"
      >{{ change.kind }} · {{ change.path }}</option>
    </select></label>
    <CodeDiff
      v-if="file"
      :key="`${scope}/${file.path}`"
      :path="file.path"
      :before="file.previousContent"
      :after="file.content"
    />
  </div>
</template>

<style scoped>
.file-diff { display: grid; gap: 8px; min-width: 0; }
p { margin: 0; }
label { display: grid; gap: 6px; }
select { box-sizing: border-box; width: 100%; padding: 6px; font: inherit; color: inherit; background: transparent; }
</style>
