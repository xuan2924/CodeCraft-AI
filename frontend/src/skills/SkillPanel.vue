<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { ArrowLeft, Download, FileText, Plus, RefreshCw, Trash2, Upload } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import Dialog from '../components/ui/Dialog.vue';
import MarkdownContent from '../components/ui/MarkdownContent.vue';
import StatePanel from '../components/ui/StatePanel.vue';
import Tooltip from '../components/ui/Tooltip.vue';
import { ApiError } from '../api/request';
import { changeSkill, importSkill, listSkills, saveSkill, skillError, skillPackage, type Skill, type SkillFile } from './api';

const props = defineProps<{ owner: string }>();
const emit = defineEmits<{ unauthorized: [] }>();
const client = useQueryClient();

const listing = useQuery({
  queryKey: computed(() => ['skills', props.owner]),
  queryFn: ({ signal }) => listSkills(props.owner, signal),
  retry: false,
  gcTime: 0,
});

const current = ref<Skill | null>(null);
const files = ref<SkillFile[]>([]);
const activePath = ref('SKILL.md');
const editorTab = ref<'edit' | 'preview'>('edit');
const busy = ref(false);
const error = ref('');
const notice = ref('');
const imported = ref('');
const importOpen = ref(false);
const exportOpen = ref(false);
const path = ref('references/notes.md');

const totalBytes = computed(() => listing.data.value?.reduce((sum, skill) => sum + skill.contentBytes, 0) ?? 0);
const activeFile = computed(() => files.value.find(f => f.path === activePath.value) ?? files.value[0]);

function splitFrontmatter(raw: string): { hasFm: boolean; lines: string[]; body: string } {
  const match = raw.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/);
  if (!match) return { hasFm: false, lines: [], body: raw };
  return { hasFm: true, lines: match[1]!.split(/\r?\n/), body: match[2]! };
}

function readMetaField(raw: string, key: 'name' | 'description'): string {
  const { hasFm, lines } = splitFrontmatter(raw);
  if (!hasFm) return '';
  const line = lines.find(l => l.startsWith(`${key}:`));
  return line ? line.slice(key.length + 1).trim().replace(/^['"]|['"]$/g, '') : '';
}

function writeMetaField(raw: string, key: 'name' | 'description', value: string): string {
  const clean = value.replace(/\r?\n/g, ' ').trim();
  const { hasFm, lines, body } = splitFrontmatter(raw);
  if (!hasFm) {
    const fm = [key === 'name' ? `name: ${clean}` : 'name: my-skill', key === 'description' ? `description: ${clean}` : 'description: 描述适用场景'];
    return `---\n${fm.join('\n')}\n---\n${body}`;
  }
  const idx = lines.findIndex(l => l.startsWith(`${key}:`));
  const nextLines = [...lines];
  if (idx >= 0) nextLines[idx] = `${key}: ${clean}`;
  else nextLines.push(`${key}: ${clean}`);
  return `---\n${nextLines.join('\n')}\n---\n${body}`;
}

const skillName = computed({
  get: () => activeFile.value?.path === 'SKILL.md' ? readMetaField(activeFile.value.content, 'name') : '',
  set: (value: string) => {
    if (activeFile.value?.path === 'SKILL.md') {
      activeFile.value.content = writeMetaField(activeFile.value.content, 'name', value);
    }
  },
});

const skillDescription = computed({
  get: () => activeFile.value?.path === 'SKILL.md' ? readMetaField(activeFile.value.content, 'description') : '',
  set: (value: string) => {
    if (activeFile.value?.path === 'SKILL.md') {
      activeFile.value.content = writeMetaField(activeFile.value.content, 'description', value);
    }
  },
});

const previewBody = computed(() => {
  if (!activeFile.value) return '';
  return activeFile.value.path === 'SKILL.md' ? splitFrontmatter(activeFile.value.content).body : activeFile.value.content;
});

async function loadFile(file?: globalThis.File) {
  if (!file || busy.value) return;
  if (!file.name.toLowerCase().endsWith('.json') || file.size > 2 * 1024 * 1024) { error.value = '请选择不超过 2 MiB 的 .json 文件。'; return; }
  const epoch = generation;
  try { const text = await file.text(); if (epoch === generation) imported.value = text; }
  catch { if (epoch === generation) error.value = '文件读取失败，请重新选择或粘贴 JSON。'; }
}
function fileSelected(event: globalThis.Event) { void loadFile((event.target as globalThis.HTMLInputElement).files?.[0]); }
function fileDropped(event: globalThis.DragEvent) { void loadFile(event.dataTransfer?.files[0]); }

let generation = 0;
let command = new globalThis.AbortController();
function reset() {
  generation++;
  command.abort();
  command = new globalThis.AbortController();
  current.value = null;
  files.value = [];
  activePath.value = 'SKILL.md';
  editorTab.value = 'edit';
  imported.value = '';
  importOpen.value = false;
  exportOpen.value = false;
  busy.value = false;
  error.value = '';
  notice.value = '';
}
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
  activePath.value = 'SKILL.md';
  editorTab.value = 'edit';
  error.value = ''; notice.value = '';
}

async function open(skill: Skill) {
  await run(async (signal, owner) => {
    const data = await skillPackage(owner, skill.id, signal);
    if (signal.aborted || owner !== props.owner) return;
    current.value = skill;
    files.value = data.files;
    activePath.value = data.files[0]?.path ?? 'SKILL.md';
    editorTab.value = 'edit';
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
  const nextPath = path.value.trim();
  if (!nextPath) return;
  if (files.value.length >= 256) { error.value = '文件数量已达前端保护上限。'; return; }
  if (files.value.some(file => file.path.toLowerCase() === nextPath.toLowerCase())) { error.value = '路径重复。'; return; }
  files.value.push({ path: nextPath, content: '' });
  activePath.value = nextPath;
  editorTab.value = 'edit';
}

function removeFile(index: number) {
  const removed = files.value[index];
  if (!removed || removed.path === 'SKILL.md') return;
  files.value.splice(index, 1);
  if (activePath.value === removed.path) {
    activePath.value = files.value[0]?.path ?? 'SKILL.md';
  }
}
</script>

<template>
  <section
    class="skill-workspace"
    aria-label="个人 Skill 管理"
  >
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

    <!-- List / Overview Mode -->
    <template v-if="!files.length">
      <div class="skill-topbar">
        <div class="actions">
          <Button
            data-test="new-skill"
            variant="primary"
            size="small"
            :disabled="busy"
            @click="fresh"
          >
            <Plus
              :size="14"
              aria-hidden="true"
            />
            <span>新建 Skill</span>
          </Button>
          <Button
            variant="secondary"
            size="small"
            :disabled="busy"
            @click="importOpen = true"
          >
            <Upload
              :size="13"
              aria-hidden="true"
            />
            <span>导入 JSON</span>
          </Button>
          <Button
            variant="ghost"
            size="small"
            title="重新读取列表"
            :disabled="busy || listing.isFetching.value"
            @click="listing.refetch()"
          >
            <RefreshCw
              :size="13"
              aria-hidden="true"
            />
            <span class="sr-only-text">重新读取列表</span>
          </Button>
        </div>

        <div
          class="quota-bar font-mono-num"
          aria-label="Skill 用量"
        >
          <span>{{ listing.isError.value || listing.isPending.value ? '数量未知' : `${listing.data.value?.length ?? 0} 个` }}</span>
          <span>{{ listing.isError.value || listing.isPending.value ? '容量未知' : `${(totalBytes / 1024).toFixed(1)} KiB` }}</span>
          <span class="next-task-chip">下一任务生效</span>
          <Tooltip text="新建与导入默认停用；单包默认 256 KiB、上限 50 个、主文档 64 KiB、总计 5 MiB；Skill 不授予工具权限。">
            <button
              type="button"
              class="quota-help-btn"
              aria-label="配额与使用规则"
            >
              ?
            </button>
          </Tooltip>
        </div>
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
          class="skill-card ui-surface-flat"
        >
          <div class="skill-card-head">
            <h3 class="font-mono-num">
              {{ skill.name }}
            </h3>
            <button
              type="button"
              class="skill-switch"
              role="switch"
              :aria-checked="skill.enabled"
              :disabled="busy"
              @click="change(skill, false)"
            >
              {{ skill.enabled ? '已启用' : '已停用' }}
            </button>
          </div>
          <p class="skill-desc">
            {{ skill.description }}
          </p>
          <div class="skill-card-footer">
            <span class="skill-meta font-mono-num">v{{ skill.version }} · {{ skill.contentBytes }} B</span>
            <div class="card-btns">
              <Button
                variant="ghost"
                size="small"
                :disabled="busy"
                @click="open(skill)"
              >
                编辑 {{ skill.name }}
              </Button>
              <Button
                variant="ghost"
                size="small"
                class="delete-ghost"
                :disabled="busy"
                @click="change(skill, true)"
              >
                <Trash2
                  :size="13"
                  aria-hidden="true"
                />
                <span class="sr-only-text">删除 {{ skill.name }}</span>
              </Button>
            </div>
          </div>
        </li>
      </ul>
    </template>

    <!-- Dual-Pane Cursor Rules Editor Mode -->
    <form
      v-else
      class="skill-editor-workbench ui-surface-flat"
      @submit.prevent="save"
    >
      <header class="workbench-head">
        <div class="workbench-title-group">
          <Button
            variant="ghost"
            size="small"
            :disabled="busy"
            @click="files = []; current = null"
          >
            <ArrowLeft
              :size="14"
              aria-hidden="true"
            />
            <span>取消编辑</span>
          </Button>
          <h2>{{ current ? current.name : '新建 Skill' }}</h2>
        </div>
        <div class="actions">
          <Button
            variant="ghost"
            size="small"
            @click="exportOpen = true"
          >
            <Download
              :size="13"
              aria-hidden="true"
            />
            <span>导出 JSON</span>
          </Button>
          <Button
            type="submit"
            variant="primary"
            size="small"
            :loading="busy"
            :disabled="busy"
          >
            保存整个文本包
          </Button>
        </div>
      </header>

      <fieldset
        :disabled="busy"
        class="editor-split"
      >
        <aside
          class="file-sidebar"
          aria-label="Skill 文件目录"
        >
          <ul class="sidebar-files">
            <li
              v-for="(file, index) in files"
              :key="file.path"
              class="sidebar-file-row"
            >
              <button
                type="button"
                class="sidebar-file-btn font-mono-num"
                :aria-pressed="activeFile?.path === file.path"
                :title="file.path"
                @click="activePath = file.path"
              >
                <FileText
                  :size="13"
                  aria-hidden="true"
                />
                <span>{{ file.path }}</span>
              </button>
              <button
                v-if="file.path !== 'SKILL.md'"
                type="button"
                class="sidebar-remove-btn"
                :aria-label="`移除 ${file.path}`"
                @click="removeFile(index)"
              >
                <Trash2
                  :size="12"
                  aria-hidden="true"
                />
              </button>
            </li>
          </ul>
          <div class="ref-add-box">
            <label
              for="skill-reference-path"
              class="sr-only-text"
            >参考文件相对路径</label>
            <input
              id="skill-reference-path"
              v-model="path"
              class="font-mono-num"
              maxlength="256"
              placeholder="references/notes.md"
            >
            <Button
              variant="secondary"
              size="small"
              @click="addFile"
            >
              添加参考文件
            </Button>
          </div>
        </aside>

        <div
          v-if="activeFile"
          class="editor-main"
        >
          <div
            v-if="activeFile.path === 'SKILL.md'"
            class="frontmatter-bar"
          >
            <label class="fm-field">
              <span>名称</span>
              <input
                v-model="skillName"
                type="text"
                class="font-mono-num"
                maxlength="64"
                placeholder="my-skill"
              >
            </label>
            <label class="fm-field fm-desc">
              <span>适用场景</span>
              <input
                v-model="skillDescription"
                type="text"
                maxlength="2048"
                placeholder="描述此 Skill 适用的场景…"
              >
            </label>
          </div>

          <div class="editor-subbar">
            <span class="active-path font-mono-num">{{ activeFile.path }}</span>
            <div
              class="ui-segmented"
              role="tablist"
              aria-label="编辑与预览切换"
            >
              <button
                type="button"
                role="tab"
                class="ui-segmented-btn"
                :aria-selected="editorTab === 'edit'"
                @click="editorTab = 'edit'"
              >
                编辑
              </button>
              <button
                type="button"
                role="tab"
                class="ui-segmented-btn"
                :aria-selected="editorTab === 'preview'"
                @click="editorTab = 'preview'"
              >
                预览
              </button>
            </div>
          </div>

          <textarea
            v-show="editorTab === 'edit'"
            v-model="activeFile.content"
            :aria-label="`${activeFile.path} 原文`"
            class="code-textarea font-mono-num"
            rows="16"
            spellcheck="false"
          />
          <div
            v-if="editorTab === 'preview'"
            class="markdown-preview"
          >
            <MarkdownContent :source="previewBody" />
          </div>
        </div>
      </fieldset>
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

    <Dialog
      v-model:open="exportOpen"
      title="导出当前编辑文本"
      description="复制完整 JSON 文本包（含尚未保存的修改）。"
    >
      <textarea
        id="skill-export"
        :value="JSON.stringify({ formatVersion: 1, files }, null, 2)"
        readonly
        rows="12"
        class="font-mono-num"
      />
    </Dialog>
  </section>
</template>

<style scoped>
.skill-workspace {
  display: grid;
  gap: 16px;
}

.sr-only-text {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
}

.skill-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.quota-bar {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.next-task-chip {
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--oc-surface-2);
  font-size: 11px;
}

.quota-help-btn {
  width: 20px;
  height: 20px;
  border-radius: 999px;
  border: 1px solid var(--oc-border);
  background: var(--oc-surface);
  color: var(--oc-fg-muted);
  font-size: 11px;
  cursor: help;
}

.skill-alert {
  margin: 0;
  padding: 9px 12px;
  border-radius: var(--radius-control);
  background: var(--oc-danger-bg);
  color: var(--oc-danger);
  font-size: 13px;
}

.skill-notice {
  margin: 0;
  padding: 9px 12px;
  border-radius: var(--radius-control);
  background: var(--oc-accent-soft);
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
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 310px), 1fr));
  gap: 14px;
}

.skill-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px 18px;
  transition: border-color 140ms var(--oc-ease), background-color 140ms var(--oc-ease);
}

.skill-card:hover {
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
  font-size: 15px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.skill-switch {
  padding: 2px 9px;
  border-radius: 999px;
  border: 1px solid var(--oc-border);
  background: var(--oc-surface-2);
  color: var(--oc-fg-muted);
  font-size: 11.5px;
  font-weight: 550;
  cursor: pointer;
  white-space: nowrap;
}

.skill-switch[aria-checked="true"] {
  background: var(--oc-accent-soft);
  border-color: var(--oc-border-accent);
  color: var(--oc-accent);
}

.skill-desc {
  margin: 0;
  color: var(--oc-fg-muted);
  font-size: 13px;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.skill-card-footer {
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px solid var(--oc-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.skill-meta {
  font-size: 11.5px;
  color: var(--oc-fg-dim);
}

.card-btns {
  display: flex;
  align-items: center;
  gap: 4px;
}

.delete-ghost {
  color: var(--oc-danger);
}

/* Dual-Pane Editor Workbench */
.skill-editor-workbench {
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: min(620px, calc(100dvh - 180px));
}

.workbench-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 16px;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
}

.workbench-title-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

.workbench-title-group h2 {
  margin: 0;
  font-size: 14.5px;
  font-weight: 600;
}

.editor-split {
  flex: 1;
  min-height: 0;
  border: 0;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
}

.file-sidebar {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
  padding: 10px;
  gap: 10px;
}

.sidebar-files {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 2px;
}

.sidebar-file-row {
  display: flex;
  align-items: center;
  gap: 4px;
}

.sidebar-file-btn {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 6px 8px;
  border: 0;
  border-left: 2px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: var(--oc-fg-muted);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}

.sidebar-file-btn span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-file-btn:hover {
  color: var(--oc-fg);
  background: rgba(255, 255, 255, 0.03);
}

.sidebar-file-btn[aria-pressed="true"] {
  color: var(--oc-fg);
  background: var(--oc-surface-2);
  border-left-color: var(--oc-accent);
}

.sidebar-remove-btn {
  border: 0;
  background: transparent;
  color: var(--oc-fg-dim);
  padding: 4px;
  cursor: pointer;
  border-radius: 4px;
}

.sidebar-remove-btn:hover {
  color: var(--oc-danger);
}

.ref-add-box {
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px solid var(--oc-border);
  display: grid;
  gap: 6px;
}

.ref-add-box input {
  width: 100%;
  padding: 6px 8px;
  font-size: 12px;
}

.editor-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: var(--oc-bg);
}

.frontmatter-bar {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: 10px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-surface);
}

.fm-field {
  display: grid;
  gap: 4px;
}

.fm-field span {
  font-size: 11px;
  font-weight: 600;
  color: var(--oc-fg-muted);
}

.fm-field input {
  width: 100%;
  padding: 5px 9px;
  font-size: 12.5px;
  border: 1px solid var(--oc-border);
  background: var(--oc-bg-input);
}

.editor-subbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 16px;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
}

.active-path {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.code-textarea {
  flex: 1;
  width: 100%;
  min-height: 360px;
  padding: 16px;
  border: 0;
  border-radius: 0;
  background: var(--oc-bg);
  color: var(--oc-fg);
  font-size: 13px;
  line-height: 1.65;
  resize: none;
  outline: none;
}

.markdown-preview {
  flex: 1;
  padding: 18px 22px;
  overflow: auto;
}

.import-dropzone {
  padding: 18px;
  margin-bottom: 14px;
  border: 1px dashed var(--oc-border-strong);
  border-radius: var(--radius-control);
  background: var(--oc-bg-subtle);
  display: grid;
  gap: 8px;
}

textarea {
  width: 100%;
  font-family: var(--oc-font-mono);
  font-size: 12.5px;
  line-height: 1.55;
}

@media (max-width: 780px) {
  .editor-split,
  .frontmatter-bar {
    grid-template-columns: 1fr;
  }
  .file-sidebar {
    border-right: 0;
    border-bottom: 1px solid var(--oc-border);
  }
}
</style>
