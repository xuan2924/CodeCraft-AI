<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
import { listSkills, skillError } from '../skills/api';
import { snapshotPackage, taskSkills } from './skillApi';
import { commandPolicy } from './errors';
const props = defineProps<{ owner: string; taskId: string | null; disabled: boolean; modelValue: string[] }>();
const emit = defineEmits<{ 'update:modelValue': [ids: string[]]; unauthorized: [] }>();
const originals = useQuery({ queryKey: computed(() => ['skills', props.owner]), queryFn: ({ signal }) => listSkills(props.owner, signal), retry: false, gcTime: 0 });
const available = computed(() => originals.data.value?.filter(item => item.enabled) ?? []);
const snapshots = useQuery({ queryKey: computed(() => ['copilot-skills', props.owner, props.taskId]), queryFn: ({ signal }) => taskSkills(props.owner, props.taskId!, signal),
  enabled: computed(() => !!props.taskId), retry: false, gcTime: 0, refetchInterval: 5000 });
const viewing = ref<string | null>(null);
watch([() => props.owner, () => props.taskId], () => { viewing.value = null; });
const contents = useQuery({ queryKey: computed(() => ['copilot-skill-package', props.owner, props.taskId, viewing.value]),
  queryFn: ({ signal }) => snapshotPackage(props.owner, props.taskId!, viewing.value!, signal), enabled: computed(() => !!props.taskId && !!viewing.value), retry: false, gcTime: 0 });
watch([originals.error, snapshots.error, contents.error], errors => { if (errors.some(commandPolicy.identityFailure)) emit('unauthorized'); });
function select(id: string, event: globalThis.Event) {
  if (props.disabled || !(event.target instanceof globalThis.HTMLInputElement)) return;
  emit('update:modelValue', event.target.checked ? [...new Set([...props.modelValue, id])] : props.modelValue.filter(value => value !== id));
}
</script>
<template>
  <section aria-label="任务 Skill">
    <h3>Skill 指导</h3>
    <p>新任务固定当时所有已启用版本；清单不等于正文已加载。勾选后显式加载正文，其他指导由模型按需读取。steer 不更新快照。</p>
    <p
      v-if="originals.isPending.value"
      role="status"
    >
      正在读取可选择的 Skill…
    </p>
    <p
      v-else-if="originals.isError.value"
      role="alert"
    >
      {{ skillError(originals.error.value) }}
    </p>
    <fieldset
      v-else
      :disabled="disabled"
    >
      <legend>下一任务显式选择（可选）</legend>
      <p v-if="!available.length">
        暂无已启用 Skill，可前往账号页的个人 Skill 管理。
      </p>
      <label
        v-for="item in available"
        :key="item.id"
      >
        <input
          type="checkbox"
          :checked="modelValue.includes(item.id)"
          @change="select(item.id, $event)"
        >
        {{ item.name }} — {{ item.description }}
      </label>
    </fieldset>
    <NButton
      :disabled="originals.isFetching.value || disabled"
      @click="originals.refetch()"
    >
      重新读取可选 Skill
    </NButton>
    <template v-if="taskId">
      <h4>当前任务固定版本</h4>
      <p
        v-if="snapshots.isPending.value"
        role="status"
      >
        正在读取快照…
      </p>
      <p
        v-else-if="snapshots.isError.value"
        role="alert"
      >
        {{ skillError(snapshots.error.value) }}
      </p>
      <p v-else-if="!snapshots.data.value?.length">
        本任务没有 Skill 快照。
      </p>
      <ul v-else>
        <li
          v-for="item in snapshots.data.value"
          :key="item.skillId"
        >
          <p>{{ item.name }}：{{ item.explicitLoaded ? 'SKILL.md 已显式加载' : item.explicitlySelected ? '已显式选择，等待派发加载' : '未显式加载，可能按需读取' }}</p>
          <p>不可变版本 {{ item.versionId }}</p>
          <NButton @click="viewing = item.skillId">
            查看 {{ item.name }} 留存原文
          </NButton>
        </li>
      </ul>
      <p>模型按需加载的路径、版本、切片与内容见对话中的 load_skill 工具结果。查看留存原文仅供本人核对，不触发模型请求或加载记录。</p>
      <section
        v-if="viewing"
        aria-label="私有 Skill 留存原文"
      >
        <NButton @click="viewing = null">
          关闭原文
        </NButton>
        <p
          v-if="contents.isPending.value"
          role="status"
        >
          正在读取留存原文…
        </p>
        <p
          v-else-if="contents.isError.value"
          role="alert"
        >
          {{ skillError(contents.error.value) }}
        </p>
        <template v-else-if="contents.data.value">
          <details
            v-for="file in contents.data.value.files"
            :key="file.path"
          >
            <summary>{{ file.path }}</summary><pre>{{ file.content }}</pre>
          </details>
        </template>
      </section>
    </template>
  </section>
</template>
<style scoped>
label { display: block; margin: 8px 0; }
fieldset { margin-bottom: 12px; min-width: 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 32rem; overflow: auto; }
</style>
