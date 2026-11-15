<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
import { ApiError } from '../api/request';
import { changeSkill, importSkill, listSkills, saveSkill, skillError, skillPackage, type Skill, type SkillFile } from './api';
const props = defineProps<{ owner: string }>();
const emit = defineEmits<{ unauthorized: [] }>();
const client = useQueryClient();
const listing = useQuery({ queryKey: computed(() => ['skills', props.owner]), queryFn: ({ signal }) => listSkills(props.owner, signal), retry: false, gcTime: 0 });
const current = ref<Skill | null>(null);
const files = ref<SkillFile[]>([]);
const busy = ref(false);
const error = ref('');
const notice = ref('');
const imported = ref('');
const path = ref('references/notes.md');
let generation = 0;
let command = new globalThis.AbortController();
function reset() { generation++; command.abort(); command = new globalThis.AbortController(); current.value = null; files.value = []; imported.value = ''; busy.value = false; error.value = ''; notice.value = ''; }
watch(() => props.owner, reset);
onScopeDispose(() => { generation++; command.abort(); });
function failure(value: unknown) {
  if (value instanceof ApiError && ['UNAUTHENTICATED', 'PASSWORD_CHANGE_REQUIRED', 'FORBIDDEN'].includes(value.code)) emit('unauthorized');
  error.value = skillError(value);
}
watch(listing.error, value => { if (value) failure(value); });
async function run(work: (signal: globalThis.AbortSignal, owner: string) => Promise<void>) {
  if (busy.value) return;
  busy.value = true; error.value = ''; notice.value = '';
  const epoch = generation; const owner = props.owner; const signal = command.signal;
  try { await work(signal, owner); }
  catch (value) { if (epoch === generation && !signal.aborted) failure(value); }
  finally { if (epoch === generation) busy.value = false; }
}
function fresh() {
  current.value = null;
  files.value = [{ path: 'SKILL.md', content: '---\nname: my-skill\ndescription: 描述适用场景\n---\n在此编写任务指导。\n' }];
  error.value = ''; notice.value = '';
}
async function open(skill: Skill) {
  await run(async (signal, owner) => {
    const data = await skillPackage(owner, skill.id, signal);
    if (signal.aborted || owner !== props.owner) return;
    current.value = skill; files.value = data.files;
  });
}
async function refreshed(signal: globalThis.AbortSignal, owner: string) {
  if (signal.aborted || owner !== props.owner) return;
  current.value = null; files.value = []; imported.value = ''; notice.value = '已保存；启停和内容变更从下一任务生效。';
  await client.invalidateQueries({ queryKey: ['skills', owner] });
}
async function save() {
  await run(async (signal, owner) => {
    await saveSkill(owner, current.value, { formatVersion: 1, files: files.value }, globalThis.crypto.randomUUID(), signal);
    await refreshed(signal, owner);
  });
}
async function importing() {
  await run(async (signal, owner) => {
    await importSkill(owner, imported.value, globalThis.crypto.randomUUID(), signal);
    await refreshed(signal, owner);
  });
}
async function change(skill: Skill, deleting: boolean) {
  if (deleting && !globalThis.confirm(`删除 ${skill.name} 的个人原件？已启动任务的快照仍会保留。`)) return;
  await run(async (signal, owner) => {
    await changeSkill(owner, skill, deleting, globalThis.crypto.randomUUID(), signal);
    await refreshed(signal, owner);
  });
}
function addFile() {
  if (files.value.length >= 256) { error.value = '文件数量已达前端保护上限。'; return; }
  if (files.value.some(file => file.path.toLowerCase() === path.value.toLowerCase())) { error.value = '路径重复。'; return; }
  files.value.push({ path: path.value, content: '' });
}
</script>

<template>
  <section aria-label="个人 Skill 管理">
    <p>Skill 仅是文本指导，不授予工具权限。修改、启停和删除影响下一任务；停用不会抹掉历史对话中的影响。</p>
    <p>默认额度：50 个（含停用）、单包 256 KiB、主文档 64 KiB、所有原件合计 5 MiB。额度可由部署配置调整，以服务端校验为准。</p>
    <p>个人原件独立于演练保留。新建和导入默认停用，请检查内容后启用。</p>
    <p
      v-if="error"
      role="alert"
    >
      {{ error }}
    </p>
    <p
      v-if="notice"
      role="status"
    >
      {{ notice }}
    </p>
    <div class="actions">
      <NButton
        data-test="new-skill"
        :disabled="busy"
        @click="fresh"
      >
        新建 Skill
      </NButton>
      <NButton
        :disabled="busy || listing.isFetching.value"
        @click="listing.refetch()"
      >
        重新读取列表
      </NButton>
    </div>
    <p
      v-if="listing.isPending.value"
      role="status"
    >
      正在读取 Skill…
    </p>
    <p
      v-else-if="listing.isError.value"
      role="alert"
    >
      无法读取个人 Skill，请重试。
    </p>
    <p v-else-if="!listing.data.value?.length">
      尚无 Skill。新建文本指导，或导入 JSON 文本包。
    </p>
    <ul
      v-else
      class="skills"
    >
      <li
        v-for="skill in listing.data.value"
        :key="skill.id"
      >
        <h3>{{ skill.name }} <small>{{ skill.enabled ? '已启用' : '已停用' }}</small></h3>
        <p>{{ skill.description }}</p>
        <p>版本 {{ skill.version }}，原文 {{ skill.contentBytes }} 字节</p>
        <div class="actions">
          <NButton
            :disabled="busy"
            @click="open(skill)"
          >
            编辑 {{ skill.name }}
          </NButton>
          <NButton
            :disabled="busy"
            @click="change(skill, false)"
          >
            {{ skill.enabled ? '停用' : '启用' }} {{ skill.name }}
          </NButton>
          <NButton
            :disabled="busy"
            @click="change(skill, true)"
          >
            删除 {{ skill.name }}
          </NButton>
        </div>
      </li>
    </ul>
    <form
      v-if="files.length"
      @submit.prevent="save"
    >
      <h2>{{ current ? `编辑 ${current.name}` : '新建文本包' }}</h2>
      <p>名称与描述在 SKILL.md 的 YAML frontmatter 中编辑；参考文件只支持 references/ 下的 .md 和 .txt。</p>
      <details>
        <summary>导出当前编辑文本</summary>
        <label for="skill-export">复制完整 JSON 文本包（含尚未保存的修改）</label>
        <textarea
          id="skill-export"
          :value="JSON.stringify({ formatVersion: 1, files }, null, 2)"
          readonly
          rows="8"
        />
      </details>
      <fieldset :disabled="busy">
        <legend>原始文本文件</legend>
        <div
          v-for="(file, index) in files"
          :key="file.path"
          class="file-editor"
        >
          <label :for="`skill-file-${index}`">{{ file.path }}</label>
          <textarea
            :id="`skill-file-${index}`"
            v-model="file.content"
            :aria-label="`${file.path} 原文`"
            rows="12"
            spellcheck="false"
          />
          <NButton
            v-if="file.path !== 'SKILL.md'"
            @click="files.splice(index, 1)"
          >
            移除 {{ file.path }}
          </NButton>
        </div>
        <label for="skill-reference-path">参考文件相对路径</label>
        <input
          id="skill-reference-path"
          v-model="path"
          maxlength="256"
        >
        <NButton @click="addFile">
          添加参考文件
        </NButton>
      </fieldset>
      <div class="actions">
        <NButton
          attr-type="submit"
          :loading="busy"
          :disabled="busy"
        >
          保存整个文本包
        </NButton>
        <NButton
          :disabled="busy"
          @click="files = []; current = null"
        >
          取消编辑
        </NButton>
      </div>
    </form>
    <details>
      <summary>导入 JSON 文本包</summary>
      <p>格式：{"formatVersion":1,"files":[{"path":"SKILL.md","content":"原文"}]}。不支持 ZIP、URL、Git 或脚本安装。</p>
      <label for="skill-import">粘贴完整 JSON（至多 2 MiB）</label>
      <textarea
        id="skill-import"
        v-model="imported"
        rows="8"
        :disabled="busy"
      />
      <NButton
        :disabled="busy || !imported.trim()"
        @click="importing"
      >
        原子导入新 Skill
      </NButton>
    </details>
  </section>
</template>
<style scoped>
.actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0; }
.skills { list-style: none; padding: 0; }
.skills li { padding: 16px 0; border-bottom: 1px solid var(--line); }
h3 { margin: 0; } small { font-size: 0.85rem; font-weight: normal; }
.file-editor { margin-bottom: 16px; }
label, textarea { display: block; }
textarea { width: 100%; box-sizing: border-box; font-family: Consolas, monospace; line-height: 1.6; resize: vertical; }
fieldset { min-width: 0; margin-bottom: 12px; }
details { margin-top: 24px; } summary { cursor: pointer; }
</style>
