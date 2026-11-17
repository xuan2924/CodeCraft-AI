<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { ArrowLeft, Download, FileText, HelpCircle, Plus, Trash2, Upload } from '@lucide/vue';
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

function closeEditor() {
  current.value = null;
  files.value = [];
  error.value = '';
}
</script>

<template>
  <section
    class="skill-workspace"
    aria-label="个人 Skill 管理"
  >
    <div class="section-head">
      <div class="section-title-row">
        <h2>个人 Skill</h2>
        <Tooltip text="启停和内容变更从下一任务生效">
          <button
            type="button"
            class="help-icon-btn"
            aria-label="查看生效说明"
          >
            <HelpCircle :size="13" />
          </button>
        </Tooltip>
        <span class="quota-text font-mono-num">{{ Math.ceil(totalBytes / 1024) }} / 64 KiB</span>
      </div>
      <div class="section-actions">
        <Button
          size="small"
          variant="ghost"
          :disabled="busy"
          @click="error = ''; importOpen = true"
        >
          <Upload :size="14" />
          <span>导入 JSON 文本包</span>
        </Button>
        <Button
          size="small"
          variant="primary"
          data-test="new-skill"
          :disabled="busy"
          @click="fresh"
        >
          <Plus :size="14" />
          <span>新建 Skill</span>
        </Button>
      </div>
    </div>

    <p
      v-if="error && !files.length && !importOpen"
      role="alert"
      class="inline-alert"
    >
      {{ error }}
    </p>
    <p
      v-else-if="notice"
      role="status"
      class="inline-notice"
    >
      {{ notice }}
    </p>

    <p
      v-if="listing.isPending.value"
      role="status"
      class="inline-status"
    >
      正在加载 Skill…
    </p>
    <StatePanel
      v-else-if="listing.isError.value"
      kind="error"
      title="Skill 加载失败"
      :description="error || '无法获取个人 Skill 列表'"
    >
      <Button
        size="small"
        @click="listing.refetch()"
      >
        重试
      </Button>
    </StatePanel>
    <div
      v-else-if="!listing.data.value?.length"
      class="empty-row"
    >
      <span>尚无 Skill。</span>
      <Button
        size="small"
        variant="ghost"
        :disabled="busy"
        @click="fresh"
      >
        创建第一个 Skill
      </Button>
    </div>
    <ul
      v-else
      class="ui-divide-list skills"
    >
      <li
        v-for="skill in listing.data.value"
        :key="skill.id"
        class="ui-divide-row skill-row"
      >
        <div class="skill-row-main">
          <div class="skill-title-line">
            <span class="skill-name">{{ skill.name }}</span>
            <span class="skill-meta font-mono-num">版本 {{ skill.version }}，{{ Math.max(1, Math.ceil(skill.contentBytes / 1024)) }} KiB</span>
          </div>
          <p
            v-if="skill.description"
            class="skill-desc"
          >
            {{ skill.description }}
          </p>
        </div>
        <div class="skill-row-actions">
          <button
            type="button"
            class="skill-switch"
            role="switch"
            :aria-checked="skill.enabled"
            :aria-label="skill.enabled ? `停用 ${skill.name}` : `启用 ${skill.name}`"
            :disabled="busy"
            @click="change(skill, false)"
          >
            <span class="switch-track">
              <span class="switch-thumb" />
            </span>
          </button>
          <Button
            size="small"
            variant="ghost"
            :aria-label="`编辑 ${skill.name}`"
            :disabled="busy"
            @click="open(skill)"
          >
            编辑
          </Button>
          <Button
            size="small"
            variant="ghost"
            class="delete-ghost"
            :aria-label="`删除 ${skill.name}`"
            :disabled="busy"
            @click="change(skill, true)"
          >
            <Trash2 :size="14" />
          </Button>
        </div>
      </li>
    </ul>

    <div
      v-if="files.length"
      class="skill-drawer-backdrop"
      @click.self="closeEditor"
    >
      <form
        class="skill-editor-workbench skill-drawer-panel"
        @submit.prevent="save"
      >
        <header class="workbench-head">
          <div class="workbench-title-group">
            <Button
              size="small"
              variant="ghost"
              :disabled="busy"
              @click="closeEditor"
            >
              <ArrowLeft :size="14" />
              <span>返回</span>
            </Button>
            <h2>{{ current ? current.name : '新建 Skill' }}</h2>
          </div>
          <div class="workbench-actions">
            <Button
              size="small"
              variant="ghost"
              :disabled="busy"
              @click="exportOpen = true"
            >
              <Download :size="14" />
              <span>导出 JSON</span>
            </Button>
            <Button
              type="submit"
              size="small"
              variant="primary"
              :disabled="busy"
            >
              {{ busy ? '保存中…' : '保存整个文本包' }}
            </Button>
          </div>
        </header>

        <p
          v-if="error"
          role="alert"
          class="drawer-alert"
        >
          {{ error }}
        </p>

        <div class="editor-split">
          <aside class="file-sidebar">
            <ul class="sidebar-files">
              <li
                v-for="(file, index) in files"
                :key="file.path"
                class="sidebar-file-row"
              >
                <button
                  type="button"
                  class="sidebar-file-btn font-mono"
                  :aria-pressed="activePath === file.path"
                  @click="activePath = file.path"
                >
                  <FileText :size="13" />
                  <span>{{ file.path }}</span>
                </button>
                <button
                  v-if="file.path !== 'SKILL.md'"
                  type="button"
                  class="sidebar-remove-btn"
                  :aria-label="`移除 ${file.path}`"
                  @click="removeFile(index)"
                >
                  <Trash2 :size="12" />
                </button>
              </li>
            </ul>

            <div class="ref-add-box">
              <input
                v-model="path"
                type="text"
                class="font-mono"
                aria-label="新增附属文件路径"
                placeholder="references/notes.md"
              >
              <Button
                size="small"
                variant="ghost"
                :disabled="busy"
                @click="addFile"
              >
                <Plus :size="13" />
                <span>添加文件</span>
              </Button>
            </div>
          </aside>

          <div class="editor-main">
            <div
              v-if="activeFile?.path === 'SKILL.md'"
              class="frontmatter-bar"
            >
              <label class="fm-field">
                <span>名称</span>
                <input
                  v-model="skillName"
                  type="text"
                  class="font-mono"
                  placeholder="my-skill"
                >
              </label>
              <label class="fm-field">
                <span>适用场景</span>
                <input
                  v-model="skillDescription"
                  type="text"
                  placeholder="描述触发场景"
                >
              </label>
            </div>

            <div class="editor-subbar">
              <span class="active-path font-mono">{{ activeFile?.path }}</span>
              <div class="ui-segmented ui-segmented-compact">
                <button
                  type="button"
                  :aria-pressed="editorTab === 'edit'"
                  @click="editorTab = 'edit'"
                >
                  原文
                </button>
                <button
                  type="button"
                  :aria-pressed="editorTab === 'preview'"
                  @click="editorTab = 'preview'"
                >
                  预览
                </button>
              </div>
            </div>

            <textarea
              v-if="editorTab === 'edit' && activeFile"
              v-model="activeFile.content"
              class="code-textarea font-mono"
              :aria-label="`${activeFile.path} 原文`"
              rows="18"
            />
            <div
              v-else
              class="markdown-preview"
            >
              <MarkdownContent :source="previewBody" />
            </div>
          </div>
        </div>
      </form>
    </div>

    <Dialog
      v-model:open="importOpen"
      title="导入 JSON 文本包"
      width="wide"
    >
      <div class="ui-dialog-body import-dialog-body">
        <p
          v-if="error && importOpen"
          role="alert"
          class="inline-alert"
        >
          {{ error }}
        </p>
        <div
          class="import-dropzone"
          @dragover.prevent
          @drop.prevent="fileDropped"
        >
          <span class="drop-hint">拖拽 `.json` 文件到此处，或选择本地文件</span>
          <input
            type="file"
            accept=".json,application/json"
            aria-label="选择 JSON 文件"
            @change="fileSelected"
          >
        </div>
        <label class="import-field">
          <span class="field-label">粘贴完整 JSON（至多 2 MiB）</span>
          <textarea
            v-model="imported"
            rows="8"
            class="font-mono"
            placeholder="{&quot;formatVersion&quot;:1,&quot;files&quot;:[...]}"
          />
        </label>
      </div>
      <template #footer>
        <div class="ui-dialog-footer">
          <Button
            size="small"
            variant="ghost"
            @click="importOpen = false"
          >
            取消
          </Button>
          <Button
            size="small"
            variant="primary"
            :disabled="busy || !imported.trim()"
            @click="importing"
          >
            {{ busy ? '导入中…' : '原子导入新 Skill' }}
          </Button>
        </div>
      </template>
    </Dialog>

    <Dialog
      v-model:open="exportOpen"
      title="导出 JSON 文本包"
      width="wide"
    >
      <div class="ui-dialog-body">
        <pre class="export-pre font-mono"><code>{{ JSON.stringify({ formatVersion: 1, files }, null, 2) }}</code></pre>
      </div>
      <template #footer>
        <div class="ui-dialog-footer">
          <Button
            size="small"
            variant="primary"
            @click="exportOpen = false"
          >
            关闭
          </Button>
        </div>
      </template>
    </Dialog>
  </section>
</template>

<style scoped>
.skill-workspace {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.section-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.section-title-row h2 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--oc-fg-muted);
}

.quota-text {
  font-size: 12px;
  color: var(--oc-fg-dim);
}

.section-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.inline-alert {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-danger-bg);
  color: var(--oc-danger);
  font-size: 13px;
}

.inline-notice {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-accent-bg);
  color: var(--oc-fg);
  font-size: 13px;
}

.inline-status {
  margin: 0;
  color: var(--oc-fg-muted);
  font-size: 13px;
}

.empty-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 16px;
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  font-size: 13px;
  color: var(--oc-fg-muted);
}

.skills {
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  overflow: hidden;
}

.skill-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 13px 16px;
  min-width: 0;
}

.skill-row-main {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 3px;
}

.skill-title-line {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
  flex-wrap: wrap;
}

.skill-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--oc-fg);
}

.skill-meta {
  font-size: 12px;
  color: var(--oc-fg-dim);
}

.skill-desc {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-row-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.help-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 2px;
  border: 0;
  background: transparent;
  color: var(--oc-fg-dim);
  cursor: pointer;
}

.help-icon-btn:hover {
  color: var(--oc-fg-muted);
}

.skill-switch {
  border: 0;
  padding: 4px;
  background: transparent;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
}

.skill-switch:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.switch-track {
  width: 32px;
  height: 18px;
  border-radius: 999px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border-strong);
  padding: 2px;
  display: inline-flex;
  align-items: center;
  transition: background var(--dur-base) var(--ease-standard), border-color var(--dur-base) var(--ease-standard);
}

.skill-switch[aria-checked="true"] .switch-track {
  background: var(--oc-accent);
  border-color: var(--oc-accent);
}

.switch-thumb {
  width: 12px;
  height: 12px;
  border-radius: 999px;
  background: var(--oc-fg);
  transform: translateX(0);
  transition: transform var(--dur-base) var(--ease-standard);
}

.skill-switch[aria-checked="true"] .switch-thumb {
  transform: translateX(14px);
}

.delete-ghost {
  color: var(--oc-danger);
}

/* Slide-over Editor Sheet */
.skill-drawer-backdrop {
  position: fixed;
  inset: 0;
  z-index: 45;
  background: rgba(9, 9, 11, 0.7);
  backdrop-filter: blur(4px);
  display: flex;
  justify-content: flex-end;
}

.skill-drawer-panel {
  width: min(860px, 100vw);
  height: 100dvh;
  background: var(--oc-surface);
  border-left: 1px solid var(--oc-border-strong);
  box-shadow: var(--oc-shadow-lg);
}

.skill-editor-workbench {
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.workbench-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 12px 16px;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
}

.workbench-title-group,
.workbench-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.workbench-title-group h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.drawer-alert {
  margin: 10px 16px 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-danger-bg);
  color: var(--oc-danger);
  font-size: 13px;
}

.editor-split {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
}

.file-sidebar {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
  padding: 10px;
  gap: 10px;
  min-height: 0;
  overflow-y: auto;
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
  background: rgba(255, 255, 255, 0.04);
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
  grid-template-columns: 180px minmax(0, 1fr);
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
  font-weight: 500;
  color: var(--oc-fg-muted);
}

.fm-field input {
  width: 100%;
  padding: 5px 9px;
  font-size: 12.5px;
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
  min-height: 300px;
  padding: 16px;
  border: 0 !important;
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

.import-dialog-body,
.import-field {
  display: grid;
  gap: 10px;
}

.field-label,
.drop-hint {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.import-dropzone {
  padding: 14px;
  border: 1px dashed var(--oc-border-strong);
  border-radius: 8px;
  background: var(--oc-bg-subtle);
  display: grid;
  gap: 8px;
}

.import-field textarea,
.export-pre {
  width: 100%;
  font-size: 12px;
  line-height: 1.55;
}

.export-pre {
  margin: 0;
  max-height: 55vh;
  overflow: auto;
  padding: 12px;
  border-radius: 8px;
  background: var(--oc-bg);
  border: 1px solid var(--oc-border);
}

@media (max-width: 720px) {
  .editor-split,
  .frontmatter-bar {
    grid-template-columns: 1fr;
  }
  .file-sidebar {
    border-right: 0;
    border-bottom: 1px solid var(--oc-border);
    max-height: 180px;
  }
}
</style>
