<script setup lang="ts">
import { computed } from 'vue';
import MarkdownContent from '../components/ui/MarkdownContent.vue';

const props = defineProps<{ source: string; problemTitle?: string }>();
// Maintainer-only publishing/validation rules (headings or initial/reference sections)
// are separated into a collapsed disclosure so candidate problem text stays focused.
const sections = computed(() => {
  const lines = props.source.split('\n');
  const primary: string[] = [], authoring: string[] = [];
  let level = 0;
  let authoringBlock = false;
  let fence = '';
  let skippedDuplicateTitle = false;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!;
    if (!skippedDuplicateTitle && !primary.length && !line.trim()) continue;
    if (!skippedDuplicateTitle && !primary.length && props.problemTitle) {
      const h1 = line.match(/^#\s+(.+)$/);
      if (h1 && h1[1]!.trim().startsWith(props.problemTitle.trim())) {
        skippedDuplicateTitle = true;
        continue;
      }
    }
    const marker = line.match(/^\s*(`{3,}|~{3,})/);
    if (marker) fence = fence ? '' : marker[1]![0]!;
    const heading = !fence && line.match(/^(#{1,6})\s+(.+)$/);
    if (heading) {
      if (level && heading[1]!.length <= level) level = 0;
      authoringBlock = false;
      if (/维护者|出题人|出题仓库|校验脚本|发布校验|本地校验/.test(heading[2]!)) {
        level = heading[1]!.length;
      }
    } else if (!fence && !level && !authoringBlock) {
      if (/`?initial\/`?\s*是|`?reference\/`?\s*只用于维护者|不分发给作答者|不打入执行镜像|仓库根目录运行：|scripts\/problem-contract\.test\.mjs|scripts\/validate-problem\.mjs|problems\/\.validation\/|docs\/design\/problem-package\.md/.test(line)) {
        authoringBlock = true;
      }
    }
    (level || authoringBlock ? authoring : primary).push(line);
  }
  return { primary: primary.join('\n').trim(), authoring: authoring.join('\n').trim() };
});
</script>

<template>
  <div class="problem-requirements">
    <MarkdownContent :source="sections.primary" />
    <details
      v-if="sections.authoring"
      class="authoring-notes"
    >
      <summary>环境与目录说明</summary>
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
  font-size: 12px;
  font-weight: 500;
  color: var(--oc-fg-dim);
  margin-bottom: 8px;
}

summary:hover {
  color: var(--oc-fg-muted);
}
</style>
