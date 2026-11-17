<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { Check, FileText, Zap } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import { listSkills, skillError } from '../skills/api';
import { snapshotPackage, taskSkills } from './skillApi';
import { commandPolicy } from './errors';

const props = defineProps<{ owner: string; taskId: string | null; disabled: boolean; modelValue: string[] }>();
const emit = defineEmits<{ 'update:modelValue': [ids: string[]]; unauthorized: [] }>();
const originals = useQuery({
  queryKey: computed(() => ['skills', props.owner]),
  queryFn: ({ signal }) => listSkills(props.owner, signal),
  retry: false,
  gcTime: 0,
});
const available = computed(() => originals.data.value?.filter(item => item.enabled) ?? []);
const snapshots = useQuery({
  queryKey: computed(() => ['copilot-skills', props.owner, props.taskId]),
  queryFn: ({ signal }) => taskSkills(props.owner, props.taskId!, signal),
  enabled: computed(() => !!props.taskId),
  retry: false,
  gcTime: 0,
  refetchInterval: 5000,
});
const viewing = ref<string | null>(null);
watch([() => props.owner, () => props.taskId], () => { viewing.value = null; });
const contents = useQuery({
  queryKey: computed(() => ['copilot-skill-package', props.owner, props.taskId, viewing.value]),
  queryFn: ({ signal }) => snapshotPackage(props.owner, props.taskId!, viewing.value!, signal),
  enabled: computed(() => !!props.taskId && !!viewing.value),
  retry: false,
  gcTime: 0,
});
watch([originals.error, snapshots.error, contents.error], errors => { if (errors.some(commandPolicy.identityFailure)) emit('unauthorized'); });

function toggleSkill(id: string) {
  if (props.disabled) return;
  const active = props.modelValue.includes(id);
  emit('update:modelValue', active ? props.modelValue.filter(value => value !== id) : [...new Set([...props.modelValue, id])]);
}
</script>

<template>
  <section
    class="skills-picker"
    aria-label="任务 Skill"
  >
    <p
      v-if="originals.isPending.value"
      role="status"
      class="picker-note"
    >
      正在读取可用 Skill…
    </p>
    <div
      v-else-if="originals.isError.value"
      role="alert"
      class="picker-error"
    >
      <span>{{ skillError(originals.error.value) }}</span>
      <Button
        variant="ghost"
        size="small"
        @click="originals.refetch()"
      >
        重试
      </Button>
    </div>
    <p
      v-else-if="!available.length"
      class="picker-note"
    >
      暂无已启用 Skill，可在 AI 配置中创建或启用。
    </p>
    <div
      v-else
      class="skill-options"
    >
      <button
        v-for="item in available"
        :key="item.id"
        type="button"
        class="skill-option-row"
        :class="{ 'is-selected': modelValue.includes(item.id) }"
        :disabled="disabled"
        :aria-pressed="modelValue.includes(item.id)"
        @click="toggleSkill(item.id)"
      >
        <Zap
          :size="13"
          class="skill-icon"
          aria-hidden="true"
        />
        <span class="skill-copy">
          <strong class="font-mono-num">{{ item.name }}</strong>
          <small>{{ item.description }}</small>
        </span>
        <Check
          v-if="modelValue.includes(item.id)"
          :size="13"
          class="skill-check"
          aria-hidden="true"
        />
      </button>
    </div>

    <details
      v-if="taskId && snapshots.data.value?.length"
      class="snapshot-drawer"
    >
      <summary>当前任务已固定快照 ({{ snapshots.data.value.length }})</summary>
      <ul class="snapshot-list">
        <li
          v-for="item in snapshots.data.value"
          :key="item.skillId"
        >
          <span class="snapshot-info">
            <strong class="font-mono-num">{{ item.name }}</strong>
            <small>{{ item.explicitLoaded ? '已显式加载' : item.explicitlySelected ? '等待派发加载' : '按需读取' }}</small>
          </span>
          <Button
            variant="ghost"
            size="small"
            @click="viewing = viewing === item.skillId ? null : item.skillId"
          >
            <FileText
              :size="12"
              aria-hidden="true"
            />
            <span>{{ viewing === item.skillId ? '收起' : '查看原文' }}</span>
          </Button>
        </li>
      </ul>
      <section
        v-if="viewing"
        aria-label="私有 Skill 留存原文"
        class="snapshot-viewer"
      >
        <p
          v-if="contents.isPending.value"
          role="status"
          class="picker-note"
        >
          正在读取…
        </p>
        <p
          v-else-if="contents.isError.value"
          role="alert"
          class="picker-error"
        >
          {{ skillError(contents.error.value) }}
        </p>
        <template v-else-if="contents.data.value">
          <details
            v-for="file in contents.data.value.files"
            :key="file.path"
            open
          >
            <summary class="font-mono-num">{{ file.path }}</summary>
            <pre>{{ file.content }}</pre>
          </details>
        </template>
      </section>
    </details>
  </section>
</template>

<style scoped>
.skills-picker {
  display: grid;
  gap: 6px;
}

.picker-note {
  margin: 0;
  padding: 6px 8px;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.picker-error {
  padding: 6px 8px;
  font-size: 12px;
  color: var(--oc-danger);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.skill-options {
  display: grid;
  gap: 2px;
  max-height: 200px;
  overflow-y: auto;
}

.skill-option-row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--oc-fg);
  text-align: left;
  cursor: pointer;
}

.skill-option-row:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.06);
}

.skill-option-row.is-selected {
  background: var(--oc-selected);
}

.skill-option-row:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.skill-icon {
  color: var(--oc-warning);
  flex-shrink: 0;
}

.skill-copy {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 1px;
}

.skill-copy strong {
  font-size: 12px;
  font-weight: 550;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-copy small {
  font-size: 11px;
  color: var(--oc-fg-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-check {
  color: var(--oc-accent);
  flex-shrink: 0;
}

.snapshot-drawer {
  margin-top: 4px;
  padding-top: 6px;
  border-top: 1px solid var(--oc-border);
  font-size: 11.5px;
  color: var(--oc-fg-muted);
}

.snapshot-drawer summary {
  cursor: pointer;
  padding: 2px 4px;
}

.snapshot-list {
  list-style: none;
  margin: 4px 0 0;
  padding: 0;
  display: grid;
  gap: 4px;
}

.snapshot-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 6px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.03);
}

.snapshot-info {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.snapshot-info strong {
  font-size: 11.5px;
  color: var(--oc-fg);
}

.snapshot-info small {
  font-size: 11px;
  color: var(--oc-fg-dim);
}

.snapshot-viewer {
  margin-top: 6px;
  display: grid;
  gap: 6px;
}

.snapshot-viewer pre {
  margin: 4px 0 0;
  padding: 8px;
  max-height: 160px;
  overflow: auto;
  border-radius: 6px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  font-size: 11.5px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
