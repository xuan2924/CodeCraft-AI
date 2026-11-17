<script setup lang="ts">
import { computed, reactive } from 'vue';
import { ChevronDown, ChevronRight, FileCode2, Folder, FolderOpen } from '@lucide/vue';

const props = defineProps<{
  paths: string[];
  selected: string;
}>();

const emit = defineEmits<{
  select: [path: string];
}>();

interface TreeBuilder {
  dirs: Map<string, TreeBuilder>;
  files: { name: string; path: string }[];
}

interface FlatRow {
  key: string;
  kind: 'dir' | 'file';
  name: string;
  path: string;
  depth: number;
}

const collapsed = reactive(new Set<string>());

function toggleDir(path: string) {
  if (collapsed.has(path)) collapsed.delete(path);
  else collapsed.add(path);
}

const rows = computed<FlatRow[]>(() => {
  const root: TreeBuilder = { dirs: new Map(), files: [] };
  for (const fullPath of [...props.paths].sort()) {
    const parts = fullPath.split('/').filter(Boolean);
    if (!parts.length) continue;
    let cursor = root;
    for (let i = 0; i < parts.length - 1; i++) {
      const segment = parts[i]!;
      let next = cursor.dirs.get(segment);
      if (!next) {
        next = { dirs: new Map(), files: [] };
        cursor.dirs.set(segment, next);
      }
      cursor = next;
    }
    cursor.files.push({ name: parts[parts.length - 1]!, path: fullPath });
  }

  const output: FlatRow[] = [];

  function walk(node: TreeBuilder, prefix: string, depth: number) {
    const dirNames = [...node.dirs.keys()].sort();
    for (const dirName of dirNames) {
      let label = dirName;
      let dirPath = prefix ? `${prefix}/${dirName}` : dirName;
      let target = node.dirs.get(dirName)!;
      // Compact single-child directory chains like src/main/java/interview
      while (target.dirs.size === 1 && target.files.length === 0) {
        const [childName, childNode] = [...target.dirs.entries()][0]!;
        label = `${label}/${childName}`;
        dirPath = `${dirPath}/${childName}`;
        target = childNode;
      }
      output.push({ key: `dir:${dirPath}`, kind: 'dir', name: label, path: dirPath, depth });
      if (!collapsed.has(dirPath)) {
        walk(target, dirPath, depth + 1);
      }
    }
    const files = [...node.files].sort((a, b) => a.name.localeCompare(b.name));
    for (const file of files) {
      output.push({ key: `file:${file.path}`, kind: 'file', name: file.name, path: file.path, depth });
    }
  }

  walk(root, '', 0);
  return output;
});
</script>

<template>
  <ul
    v-if="rows.length"
    class="file-tree"
    role="tree"
    aria-label="工作区文件树"
  >
    <li
      v-for="row in rows"
      :key="row.key"
      role="none"
    >
      <button
        v-if="row.kind === 'dir'"
        type="button"
        class="tree-row tree-dir"
        :style="{ paddingLeft: `${8 + row.depth * 12}px` }"
        :aria-expanded="!collapsed.has(row.path)"
        :title="row.path"
        @click="toggleDir(row.path)"
      >
        <component
          :is="collapsed.has(row.path) ? ChevronRight : ChevronDown"
          :size="13"
          class="tree-caret"
          aria-hidden="true"
        />
        <component
          :is="collapsed.has(row.path) ? Folder : FolderOpen"
          :size="13"
          class="tree-folder-icon"
          aria-hidden="true"
        />
        <span class="tree-label">{{ row.name }}</span>
      </button>
      <button
        v-else
        type="button"
        data-testid="workspace-file"
        class="tree-row tree-file"
        :style="{ paddingLeft: `${22 + row.depth * 12}px` }"
        :aria-pressed="selected === row.path"
        :title="row.path"
        @click="emit('select', row.path)"
      >
        <FileCode2
          :size="13"
          class="tree-file-icon"
          aria-hidden="true"
        />
        <span class="tree-label">{{ row.name }}</span>
      </button>
    </li>
  </ul>
</template>

<style scoped>
.file-tree {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 1px;
}

.tree-row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 27px;
  padding-top: 3px;
  padding-bottom: 3px;
  padding-right: 8px;
  text-align: left;
  border: 0;
  border-left: 2px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: var(--oc-fg-muted);
  font-family: var(--oc-font-mono);
  font-size: 12px;
  line-height: 1.35;
  cursor: pointer;
  min-width: 0;
  transition: background-color 120ms var(--oc-ease), color 120ms var(--oc-ease);
}

.tree-row:hover {
  background: rgba(255, 255, 255, 0.04);
  color: var(--oc-fg);
}

.tree-file[aria-pressed="true"] {
  background: var(--oc-surface-2);
  border-left-color: var(--oc-accent);
  color: var(--oc-fg);
  font-weight: 550;
}

.tree-file[aria-pressed="true"] .tree-file-icon {
  color: var(--oc-accent);
}

.tree-caret,
.tree-folder-icon,
.tree-file-icon {
  flex-shrink: 0;
}

.tree-folder-icon {
  color: var(--oc-fg-dim);
}

.tree-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
</style>
