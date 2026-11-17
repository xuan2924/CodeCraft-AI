<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import Dialog from '../components/ui/Dialog.vue';
import StatePanel from '../components/ui/StatePanel.vue';
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
const importOpen = ref(false);
const totalBytes = computed(() => listing.data.value?.reduce((sum, skill) => sum + skill.contentBytes, 0) ?? 0);
async function loadFile(file?: globalThis.File) {
  if (!file || busy.value) return;
  if (!file.name.toLowerCase().endsWith('.json') || file.size > 2 * 1024 * 1024) { error.value = '请选择不超过 2 MiB 的 .json 文件。'; return; }
  const epoch = generation;
  try { const text = await file.text(); if (epoch === generation) imported.value = text; }
  catch { if (epoch === generation) error.value = '文件读取失败，请重新选择或粘贴 JSON。'; }
}
function fileSelected(event: globalThis.Event) { void loadFile((event.target as globalThis.HTMLInputElement).files?.[0]); }
function fileDropped(event: globalThis.DragEvent) { void loadFile(event.dataTransfer?.files[0]); }
const path = ref('references/notes.md');
let generation = 0;
let command = new globalThis.AbortController();
function reset() { generation++; command.abort(); command = new globalThis.AbortController(); current.value = null; files.value = []; imported.value = ''; importOpen.value = false; busy.value = false; error.value = ''; notice.value = ''; }
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
    if (!signal.aborted && owner === props.owner) importOpen.value = false;
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
  <section
    class="skill-workspace"
    aria-label="个人 Skill 管理"
  >
    <div class="skill-summary">
      <p>新建与导入默认停用，检查内容后再启用。</p>
      <div
        class="quota"
        aria-label="Skill 用量"
      >
        <span>{{ listing.isError.value || listing.isPending.value ? '数量未知' : `已用 ${listing.data.value?.length ?? 0} 个` }}</span>
        <span>{{ listing.isError.value || listing.isPending.value ? '容量未知' : `${(totalBytes / 1024).toFixed(1)} KiB` }}</span>
        <span>单包默认 256 KiB</span>
      </div>
    </div>
    <details class="skill-rules">
      <summary>配额与使用规则</summary>
      <p>默认上限 50 个（含停用），主文档 64 KiB，所有原件共 5 MiB；部署可调整，以服务端校验为准。Skill 不授予工具权限；变更从下一任务生效，不抹去历史影响。</p>
    </details>
    <p
      v-if="error"
      role="alert"
      class="skill-alert"
    >
      {{ error }}
    </p>
    <p
      v-if="notice"
      role="status"
      class="skill-notice"
    >
      {{ notice }}
    </p>
    <div class="actions">
      <Button
        data-test="new-skill"
        variant="primary"
        :disabled="busy"
        @click="fresh"
      >
        新建 Skill
      </Button>
      <Button
        variant="secondary"
        :disabled="busy"
        @click="importOpen = true"
      >
        导入 JSON
      </Button>
      <Button
        :disabled="busy || listing.isFetching.value"
        @click="listing.refetch()"
      >
        重新读取列表
      </Button>
    </div>
    <p
      v-if="listing.isPending.value"
      role="status"
      class="skill-status"
    >
      正在读取 Skill…
    </p>
    <p
      v-else-if="listing.isError.value"
      role="alert"
      class="skill-alert"
    >
      无法读取个人 Skill，请重试。
    </p>
    <StatePanel
      v-else-if="!listing.data.value?.length"
      title="尚无 Skill · 创建你的第一份工作指导"
      description="保存代码规范、检查清单或领域知识，在下一次任务中使用。"
    />
    <ul
      v-else
      class="skills"
    >
      <li
        v-for="skill in listing.data.value"
        :key="skill.id"
      >
        <div class="skill-card-head">
          <h3>{{ skill.name }}</h3>
          <small :data-enabled="skill.enabled">{{ skill.enabled ? '已启用' : '已停用' }}</small>
        </div>
        <p class="skill-desc">
          {{ skill.description }}
        </p>
        <p class="skill-meta">
          版本 {{ skill.version }} · 原文 {{ skill.contentBytes }} 字节
        </p>
        <div class="actions skill-card-actions">
          <Button
            size="small"
            :disabled="busy"
            @click="open(skill)"
          >
            编辑 {{ skill.name }}
          </Button>
          <Button
            size="small"
            :disabled="busy"
            variant="secondary"
            role="switch"
            :aria-checked="skill.enabled"
            @click="change(skill, false)"
          >
            {{ skill.enabled ? '停用' : '启用' }} {{ skill.name }}
          </Button>
          <Button
            size="small"
            :disabled="busy"
            variant="danger"
            @click="change(skill, true)"
          >
            删除 {{ skill.name }}
          </Button>
        </div>
      </li>
    </ul>
    <form
      v-if="files.length"
      class="skill-editor-form"
      @submit.prevent="save"
    >
      <h2>{{ current ? `编辑 ${current.name}` : '新建文本包' }}</h2>
      <p class="skill-desc">
        名称与描述在 SKILL.md 的 YAML frontmatter 中编辑；参考文件只支持 references/ 下的 .md 和 .txt。
      </p>
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
          <Button
            v-if="file.path !== 'SKILL.md'"
            variant="danger"
            size="small"
            @click="files.splice(index, 1)"
          >
            移除 {{ file.path }}
          </Button>
        </div>
        <label for="skill-reference-path">参考文件相对路径</label>
        <div class="ref-add-row">
          <input
            id="skill-reference-path"
            v-model="path"
            maxlength="256"
          >
          <Button
            size="small"
            @click="addFile"
          >
            添加参考文件
          </Button>
        </div>
      </fieldset>
      <div class="actions">
        <Button
          type="submit"
          variant="primary"
          :loading="busy"
          :disabled="busy"
        >
          保存整个文本包
        </Button>
        <Button
          :disabled="busy"
          @click="files = []; current = null"
        >
          取消编辑
        </Button>
      </div>
    </form>
    <Dialog
      v-model:open="importOpen"
      title="导入 JSON 文本包"
      description="粘贴 JSON 或选择 .json 文件；导入后默认停用。"
      :busy="busy"
    >
      <div
        class="import-dropzone"
        @dragover.prevent
        @drop.prevent="fileDropped"
      >
        <label for="skill-json-file">拖拽 .json 到此处，或选择文件（至多 2 MiB）</label>
        <input
          id="skill-json-file"
          type="file"
          accept=".json,application/json"
          :disabled="busy"
          @change="fileSelected"
        >
      </div>
      <p
        v-if="error"
        role="alert"
        class="skill-alert"
      >
        {{ error }}
      </p>
      <label for="skill-import">粘贴完整 JSON（至多 2 MiB）</label>
      <textarea
        id="skill-import"
        v-model="imported"
        rows="8"
        :disabled="busy"
      />
      <Button
        variant="primary"
        :disabled="busy || !imported.trim()"
        @click="importing"
      >
        导入新 Skill
      </Button>
    </Dialog>
  </section>
</template>
<style scoped>
.skill-workspace {
  display: grid;
  gap: 18px;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.skill-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding: 16px 20px;
  border-radius: var(--radius-panel);
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  box-shadow: var(--oc-hairline-top), var(--oc-shadow-xs);
}

.skill-summary p {
  margin: 0;
  color: var(--oc-fg-muted);
  font-size: 13.5px;
}

.quota {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.quota span {
  padding: 4px 10px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  border-radius: 999px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--oc-fg-muted);
}

.skill-rules {
  color: var(--oc-fg-muted);
  font-size: 13px;
}

.skill-rules summary {
  cursor: pointer;
}

.skill-rules p {
  margin: 8px 0 0;
  line-height: 1.6;
}

.skill-alert {
  margin: 0;
  padding: 10px 14px;
  border-radius: 8px;
  background: var(--oc-danger-bg);
  border: 1px solid var(--oc-danger-border);
  color: var(--oc-danger);
  font-size: 13px;
}

.skill-notice {
  margin: 0;
  padding: 10px 14px;
  border-radius: 8px;
  background: var(--oc-accent-soft);
  border: 1px solid var(--oc-border-accent);
  color: var(--oc-accent);
  font-size: 13px;
}

.skill-status {
  margin: 0;
  font-size: 13.5px;
  color: var(--oc-fg-muted);
}

.skills {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 300px), 1fr));
  gap: 16px;
}

.skills li {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px;
  border: 1px solid var(--oc-border);
  background: var(--oc-surface);
  border-radius: var(--radius-panel);
  box-shadow: var(--oc-hairline-top), var(--oc-shadow-xs);
  transition: border-color 150ms var(--oc-ease), background-color 150ms var(--oc-ease);
}

.skills li:hover {
  border-color: var(--oc-border-strong);
  background: var(--oc-surface-2);
}

.skill-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.skill-card-head h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.skill-card-head small {
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 550;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  color: var(--oc-fg-muted);
}

.skill-card-head small[data-enabled="true"] {
  background: var(--oc-accent-soft);
  border-color: var(--oc-border-accent);
  color: var(--oc-accent);
}

.skill-desc {
  margin: 0;
  color: var(--oc-fg-muted);
  font-size: 13.5px;
  line-height: 1.6;
}

.skill-meta {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-dim);
  font-variant-numeric: tabular-nums;
}

.skill-card-actions {
  margin-top: auto;
  padding-top: 8px;
}

.skill-editor-form {
  padding: 24px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-panel);
  box-shadow: var(--oc-shadow-sm), var(--oc-hairline-top);
  display: grid;
  gap: 14px;
}

.skill-editor-form h2 {
  margin: 0;
  font-size: 18px;
}

.import-dropzone {
  padding: 20px;
  margin-bottom: 16px;
  border: 1px dashed var(--oc-border-strong);
  border-radius: 10px;
  background: var(--oc-bg-subtle);
  display: grid;
  gap: 10px;
}

.import-dropzone input {
  max-width: 100%;
}

textarea,
input {
  color: var(--oc-fg);
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border-input);
  padding: 10px 12px;
  border-radius: 8px;
}

.file-editor {
  display: grid;
  gap: 8px;
  margin-bottom: 16px;
}

label,
textarea {
  display: block;
}

label {
  font-size: 13px;
  font-weight: 550;
}

textarea {
  width: 100%;
  box-sizing: border-box;
  font-family: var(--oc-font-mono);
  font-size: 13px;
  line-height: 1.6;
  resize: vertical;
}

fieldset {
  min-width: 0;
  border: 1px solid var(--oc-border);
  border-radius: 10px;
  padding: 16px;
}

legend {
  padding: 0 6px;
  font-size: 13px;
  color: var(--oc-fg-muted);
}

.ref-add-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-top: 6px;
}

.ref-add-row input {
  flex: 1 1 240px;
}
</style>
