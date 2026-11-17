<script setup lang="ts">
import { computed } from 'vue';
import MarkdownContent from '../components/ui/MarkdownContent.vue';

const props = defineProps<{ source: string }>();
// Only explicitly labelled authoring sections are folded; requirements are never
// inferred from code commands or file names (which may be part of the exercise).
const sections = computed(() => {
  const lines = props.source.split('\n');
  const primary: string[] = [], authoring: string[] = [];
  let level = 0;
  let fence = '';
  for (const line of lines) {
    const marker = line.match(/^\s*(`{3,}|~{3,})/);
    if (marker) fence = fence ? '' : marker[1]![0]!;
    const heading = !fence && line.match(/^(#{1,6})\s+(.+)$/);
    if (heading) {
      if (level && heading[1]!.length <= level) level = 0;
      if (/维护者|出题人|出题仓库|校验脚本|发布校验|本地校验/.test(heading[2]!)) level = heading[1]!.length;
    }
    (level ? authoring : primary).push(line);
  }
  return { primary: primary.join('\n'), authoring: authoring.join('\n') };
});
</script>

<template>
  <div class="problem-requirements">
    <MarkdownContent :source="sections.primary" />
    <details
      v-if="sections.authoring.trim()"
      class="authoring-notes"
    >
      <summary>题目维护与校验说明</summary>
      <MarkdownContent :source="sections.authoring" />
    </details>
  </div>
</template>

<style scoped>
.problem-requirements {
  min-width: 0;
}

.authoring-notes {
  margin-top: 18px;
  border-top: 1px solid var(--oc-border);
  padding-top: 12px;
  color: var(--oc-fg-muted);
}

summary {
  cursor: pointer;
  font-size: 12.5px;
  font-weight: 550;
  color: var(--oc-fg-muted);
  margin-bottom: 8px;
}

summary:hover {
  color: var(--oc-fg);
}
</style>
