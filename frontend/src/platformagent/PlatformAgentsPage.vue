<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useIntervalFn, useTimestamp } from '@vueuse/core';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { RouterLink } from 'vue-router';
import { HelpCircle, Plus } from '@lucide/vue';
import { session } from '../auth/api';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import Button from '../components/ui/Button.vue';
import Dialog from '../components/ui/Dialog.vue';
import Tooltip from '../components/ui/Tooltip.vue';
import TextField from '../components/ui/TextField.vue';
import StatePanel from '../components/ui/StatePanel.vue';
import { formatShortTimestamp } from '../copilot/feeConsent';
import PlatformDraftForm from './PlatformDraftForm.vue';
import * as api from './api';

const client = useQueryClient();
const account = useQuery({ queryKey: ['identity-session'], queryFn: ({ signal }) => session(signal), retry: false, gcTime: 0, refetchInterval: 30000 });
const owner = computed(() => account.data.value?.id ?? '');
const now = useTimestamp({ scheduler: callback => useIntervalFn(callback, 1000) });
const allowed = computed(() => account.isFetchedAfterMount.value && !!owner.value && account.data.value?.role === 'ADMIN'
  && !account.data.value.passwordChangeRequired && !account.isError.value && Date.parse(account.data.value.expiresAt) > now.value);
const commandScope = () => allowed.value ? owner.value : '';
const after = ref<string>();
const selected = ref('');
const seed = ref<api.Version | null>(null);
const draftOpen = ref(false);
const fee = ref(false);
const replacement = ref('');
const retireOpen = ref(false);
const notice = ref('');
const catalogue = useQuery({ queryKey: ['platform-agents', owner, after], queryFn: ({ signal }) => api.list(owner.value, signal, after.value), enabled: allowed, retry: false, refetchInterval: 10000 });
const selectionEnabled = computed(() => allowed.value && !!selected.value);
const detail = useQuery({ queryKey: ['platform-version', owner, selected], queryFn: ({ signal }) => api.get(owner.value, selected.value, signal), enabled: selectionEnabled, retry: false });
const latest = useQuery({ queryKey: ['platform-check', owner, selected], queryFn: ({ signal }) => api.latest(owner.value, selected.value, signal), enabled: selectionEnabled, retry: false, refetchInterval: 3000 });

watch(() => catalogue.data.value, (data) => { if (!selected.value && data?.versions[0]) selected.value = data.versions[0].id; });
watch(owner, () => { after.value = undefined; selected.value = ''; seed.value = null; draftOpen.value = false; notice.value = ''; retireOpen.value = false; });
watch([owner, selected], () => { fee.value = false; replacement.value = ''; });

const policy = {
  definitiveRejection: (failure: unknown) => failure instanceof ApiError && [400, 401, 403, 404, 409, 422, 429].includes(failure.status),
  errorMessage: api.message,
  identityFailure: (failure: unknown) => failure instanceof ApiError && [401, 403].includes(failure.status),
};

function clearPrivateQueries(user: string) {
  for (const namespace of ['platform-agents', 'platform-version', 'platform-check']) {
    void client.cancelQueries({ queryKey: [namespace, user] });
    client.removeQueries({ queryKey: [namespace, user] });
  }
}

function refreshIdentity() {
  const user = owner.value;
  client.setQueryData(['identity-session'], null);
  clearPrivateQueries(user);
  void account.refetch();
}

watch([owner, allowed, () => account.data.value?.role, account.isFetchedAfterMount, account.isError], ([user, permit], [previous]) => {
  if (previous && (previous !== user || !permit)) {
    clearPrivateQueries(previous);
    replacement.value = '';
    seed.value = null;
    draftOpen.value = false;
    fee.value = false;
    notice.value = '';
    retireOpen.value = false;
  }
}, { flush: 'sync' });

watch([catalogue.error, detail.error, latest.error], (errors) => {
  if (errors.some(error => error instanceof ApiError && [401, 403].includes(error.status))) refreshIdentity();
});

function changed(version: api.Version) {
  client.setQueryData(['platform-version', owner.value, version.id], version);
  void client.invalidateQueries({ queryKey: ['platform-agents', owner.value] });
  void client.invalidateQueries({ queryKey: ['platform-check', owner.value, version.id] });
  retireOpen.value = false;
}

const create = useCommand<api.Version>(commandScope, (version) => {
  changed(version);
  selected.value = version.id;
  seed.value = null;
  draftOpen.value = false;
  notice.value = `保存已确认：${version.id} · ${stateLabel(version.state)}。`;
}, policy, refreshIdentity);

const action = useCommand<api.Version>(commandScope, (version) => {
  changed(version);
  notice.value = `管理操作已确认：${version.id} · ${stateLabel(version.state)}`;
}, policy, refreshIdentity);

const check = useCommand<api.Check>(commandScope, (result) => {
  client.setQueryData(['platform-check', owner.value, result.versionId], result);
  notice.value = `检查回执：${result.versionId} · ${stateLabel(result.state)}。`;
}, policy, refreshIdentity);

const busy = computed(() => create.pending.value || create.uncertain.value || action.pending.value || action.uncertain.value || check.pending.value || check.uncertain.value);
const currentCheck = computed(() => latest.data.value?.state === 'SUCCEEDED' && latest.data.value.credentialRevision === detail.data.value?.credentialRevision);

function save(input: api.Draft) {
  const user = owner.value;
  void create.submit((key, signal) => api.create(user, input, key, signal));
}

function change(kind: 'publish' | 'retire' | 'credential') {
  const version = detail.data.value;
  if (!version || busy.value) return;
  const pointer = catalogue.data.value?.roles.find(value => value.role === version.role);
  const input: api.Action = { expectedRevision: version.revision, expectedRoleRevision: pointer?.revision };
  if (kind === 'credential') {
    input.apiKey = replacement.value;
    replacement.value = '';
  }
  const user = owner.value;
  void action.submit((key, signal) => api.change(user, version.id, kind, input, key, signal));
}

function startCheck() {
  const version = detail.data.value;
  if (!version || busy.value || !fee.value) return;
  const user = owner.value;
  const input = { expectedRevision: version.revision, feeAcknowledged: true };
  void check.submit((key, signal) => api.check(user, version.id, input, key, signal));
}

function openDraft(template: api.Version | null = null) {
  seed.value = template;
  draftOpen.value = true;
}

function roleLabel(role: api.Role) {
  return role === 'INTERVIEWER' ? '面试官' : '评测者';
}

function stateLabel(state: string) {
  return ({
    DRAFT: '草稿',
    PUBLISHED: '已发布',
    RETIRED: '已退役',
    QUEUED: '检查排队中',
    RUNNING: '检查运行中',
    SUCCEEDED: '检查成功',
    FAILED: '检查失败',
  } as Record<string, string>)[state] ?? state;
}

const activeError = computed(() => create.error.value || action.error.value || check.error.value);
const versionsList = computed(() => catalogue.data.value?.versions ?? []);
const showDraftForm = computed(() => (catalogue.isFetched.value && !versionsList.value.length) || draftOpen.value || !!seed.value);
</script>

<template>
  <main class="platform-page">
    <header class="page-header">
      <div class="header-row">
        <h1>Agent 策略</h1>
        <span
          v-if="allowed && account.data.value"
          class="owner-pill font-mono-num"
        >{{ account.data.value.username }}</span>
      </div>
    </header>

    <p
      v-if="account.isPending.value || !account.isFetchedAfterMount.value"
      role="status"
      class="inline-status"
    >
      正在确认权限…
    </p>
    <div
      v-else-if="account.isError.value"
      role="alert"
      class="gate-card"
    >
      <p>无法确认管理员权限。</p>
      <Button
        size="small"
        @click="account.refetch()"
      >
        重试确认
      </Button>
    </div>
    <div
      v-else-if="!allowed"
      class="gate-card"
    >
      <p>需要管理员身份且已完成临时密码修改才能管理平台 Agent 策略。</p>
      <RouterLink
        class="ui-button ui-button-secondary ui-button-small"
        :to="{ name: 'account' }"
      >前往账号页</RouterLink>
    </div>

    <div
      v-else
      class="platform-stack"
    >
      <p
        v-if="activeError"
        role="alert"
        class="inline-alert"
      >
        {{ activeError }}
      </p>
      <p
        v-else-if="notice"
        role="status"
        class="inline-notice"
      >
        {{ notice }}
      </p>

      <div
        v-if="create.uncertain.value || action.uncertain.value || check.uncertain.value"
        role="status"
        class="uncertain-bar"
      >
        <span>请求结果未确认，请核对当前服务端状态后再继续。</span>
        <Button
          size="small"
          variant="ghost"
          @click="create.uncertain.value ? create.retry() : action.uncertain.value ? action.retry() : check.retry()"
        >
          原幂等键重试
        </Button>
      </div>

      <p
        v-if="catalogue.isPending.value"
        role="status"
        class="inline-status"
      >
        正在加载平台版本…
      </p>
      <StatePanel
        v-else-if="catalogue.isError.value"
        kind="error"
        title="平台版本加载失败"
        description="无法读取平台 Agent 版本目录"
      >
        <Button
          size="small"
          @click="catalogue.refetch()"
        >
          重试
        </Button>
      </StatePanel>

      <template v-else-if="catalogue.data.value">
        <section
          class="group-block"
          aria-label="角色当前发布版本"
        >
          <h2 class="section-heading">
            角色当前发布版本
          </h2>
          <ul class="ui-divide-list surface-list">
            <li
              v-for="pointer in catalogue.data.value.roles"
              :key="pointer.role"
              class="ui-divide-row role-row"
            >
              <span class="role-name">{{ roleLabel(pointer.role) }}</span>
              <div class="role-meta">
                <button
                  v-if="pointer.currentVersion"
                  type="button"
                  class="link-btn font-mono"
                  @click="selected = pointer.currentVersion"
                >
                  {{ pointer.currentVersion }}
                </button>
                <span
                  v-else
                  class="muted-text"
                >未发布</span>
                <span class="rev-text font-mono-num">r{{ pointer.revision }}</span>
              </div>
            </li>
          </ul>
        </section>

        <section
          class="group-block"
          aria-label="平台版本列表"
        >
          <div class="section-head">
            <h2 class="section-heading">
              平台版本
            </h2>
            <div class="section-actions">
              <Button
                v-if="after"
                size="small"
                variant="ghost"
                @click="after = undefined"
              >
                返回首页
              </Button>
              <Button
                v-if="versionsList.length >= 50"
                size="small"
                variant="ghost"
                @click="after = versionsList[versionsList.length - 1]?.id"
              >
                下一页
              </Button>
              <Button
                size="small"
                :variant="showDraftForm && versionsList.length ? 'ghost' : 'primary'"
                :disabled="busy"
                @click="showDraftForm && versionsList.length ? (draftOpen = false, seed = null) : openDraft(null)"
              >
                <Plus
                  v-if="!(showDraftForm && versionsList.length)"
                  :size="14"
                />
                <span>{{ showDraftForm && versionsList.length ? '收起草稿' : '新建草稿' }}</span>
              </Button>
            </div>
          </div>

          <p
            v-if="!versionsList.length"
            class="empty-line"
          >
            暂无平台版本，请保存新草稿。
          </p>
          <ul
            v-else
            class="ui-divide-list surface-list"
          >
            <li
              v-for="version in versionsList"
              :key="version.id"
              class="ui-divide-row"
            >
              <button
                type="button"
                class="version-row-btn"
                :class="{ 'is-selected': selected === version.id }"
                :aria-current="selected === version.id ? 'true' : undefined"
                @click="selected = version.id"
              >
                <div class="version-main">
                  <span class="version-role">{{ roleLabel(version.role) }}</span>
                  <span class="version-id font-mono">{{ version.id }}</span>
                </div>
                <div class="version-tail">
                  <span
                    class="state-pill"
                    :class="`state-${version.state.toLowerCase()}`"
                  >{{ stateLabel(version.state) }}</span>
                  <time
                    class="version-time font-mono-num"
                    :datetime="version.createdAt"
                  >{{ formatShortTimestamp(version.createdAt) }}</time>
                </div>
              </button>
            </li>
          </ul>
        </section>

        <section
          v-if="selected"
          class="detail-surface"
          aria-label="平台版本详情"
        >
          <p
            v-if="detail.isPending.value"
            role="status"
            class="inline-status"
          >
            正在加载版本详情…
          </p>
          <StatePanel
            v-else-if="detail.isError.value"
            kind="error"
            title="版本详情加载失败"
            description="无法读取该版本配置"
          />
          <template v-else-if="detail.data.value">
            <header class="detail-head">
              <div class="detail-title-group">
                <h2>{{ detail.data.value.definition.connection.name }}</h2>
                <span class="state-pill">{{ stateLabel(detail.data.value.state) }}</span>
                <span class="mono-dim font-mono-num">{{ roleLabel(detail.data.value.role) }} · {{ detail.data.value.definition.model.modelId }}</span>
              </div>
              <div class="detail-head-actions">
                <Button
                  size="small"
                  variant="ghost"
                  :disabled="busy"
                  @click="openDraft(detail.data.value)"
                >
                  复制为新草稿
                </Button>
                <Button
                  v-if="detail.data.value.state === 'DRAFT'"
                  size="small"
                  variant="primary"
                  :disabled="busy || !currentCheck"
                  @click="change('publish')"
                >
                  发布
                </Button>
                <Button
                  v-if="detail.data.value.state !== 'RETIRED'"
                  size="small"
                  variant="ghost"
                  class="danger-btn"
                  :disabled="busy"
                  @click="retireOpen = true"
                >
                  退役
                </Button>
              </div>
            </header>

            <dl class="detail-kv">
              <div>
                <dt>版本 ID</dt>
                <dd class="font-mono">
                  {{ detail.data.value.id }}
                </dd>
              </div>
              <div>
                <dt>接入地址</dt>
                <dd class="font-mono">
                  {{ detail.data.value.definition.connection.baseUrl }}
                </dd>
              </div>
              <div>
                <dt>凭证状态</dt>
                <dd>{{ detail.data.value.credentialAvailable ? '已配置' : '待补配' }}（cred v{{ detail.data.value.credentialRevision }}）</dd>
              </div>
              <div>
                <dt>Skill 包数</dt>
                <dd class="font-mono-num">
                  {{ detail.data.value.definition.skills.length }}
                </dd>
              </div>
            </dl>

            <div class="instructions-block">
              <span class="sub-label">角色指令</span>
              <pre class="instructions-pre">{{ detail.data.value.definition.instructions }}</pre>
            </div>

            <div class="key-rotate-row">
              <TextField
                v-model="replacement"
                label="补配 / 轮换平台 API Key"
                type="password"
                autocomplete="new-password"
                placeholder="输入新的平台 API Key"
              />
              <Button
                size="small"
                variant="secondary"
                :disabled="busy || !replacement.trim()"
                @click="change('credential')"
              >
                更新 Key
              </Button>
            </div>

            <div
              class="check-section"
              aria-label="平台检查"
            >
              <div class="check-head">
                <div class="check-title">
                  <span>模型可用性检查</span>
                  <Tooltip text="检查会产生真实模型计费；当前凭据检查通过后才允许发布。">
                    <button
                      type="button"
                      class="help-icon-btn"
                      aria-label="查看检查说明"
                    >
                      <HelpCircle :size="13" />
                    </button>
                  </Tooltip>
                </div>
                <span
                  v-if="latest.data.value"
                  class="check-state font-mono-num"
                >
                  {{ stateLabel(latest.data.value.state) }}{{ latest.data.value.errorCode ? ` · ${latest.data.value.errorCode}` : '' }}
                </span>
                <span
                  v-else
                  class="muted-text"
                >尚无检查记录</span>
              </div>

              <div class="check-controls">
                <label class="fee-check">
                  <input
                    v-model="fee"
                    type="checkbox"
                  >
                  <span>确认模型请求可能收费</span>
                </label>
                <Button
                  size="small"
                  variant="secondary"
                  :disabled="busy || !fee || !detail.data.value.credentialAvailable"
                  @click="startCheck"
                >
                  发起检查
                </Button>
              </div>
            </div>
          </template>
        </section>

        <section
          v-if="showDraftForm"
          class="draft-section"
        >
          <PlatformDraftForm
            :disabled="busy"
            :seed="seed"
            @submit="save"
          />
        </section>
      </template>

      <Dialog
        v-model:open="retireOpen"
        title="确认退役该平台版本"
      >
        <div class="ui-dialog-body">
          <p class="dialog-copy">
            退役后将不可再作为新任务的发布版本使用。
          </p>
        </div>
        <template #footer>
          <div class="ui-dialog-footer">
            <Button
              size="small"
              variant="ghost"
              @click="retireOpen = false"
            >
              取消
            </Button>
            <Button
              size="small"
              variant="danger"
              :disabled="busy"
              @click="change('retire')"
            >
              确认退役
            </Button>
          </div>
        </template>
      </Dialog>
    </div>
  </main>
</template>

<style scoped>
.platform-page {
  max-width: 768px;
  margin: 0 auto;
  padding: 32px 24px 64px;
  min-width: 0;
}

.page-header {
  margin-bottom: 24px;
}

.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.page-header h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.owner-pill {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.inline-status {
  margin: 0;
  color: var(--oc-fg-muted);
  font-size: 13px;
}

.gate-card {
  padding: 16px;
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 10px;
  font-size: 13px;
}

.gate-card p {
  margin: 0;
}

.gate-card a {
  width: fit-content;
  text-decoration: none;
}

.platform-stack {
  display: grid;
  gap: 24px;
  min-width: 0;
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

.uncertain-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border-strong);
  font-size: 12.5px;
}

.group-block {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.section-heading {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--oc-fg-muted);
}

.section-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.surface-list {
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  overflow: hidden;
}

.role-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 16px;
}

.role-name {
  font-size: 13.5px;
  font-weight: 500;
}

.role-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.link-btn {
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--oc-accent);
  font-size: 12px;
  cursor: pointer;
}

.link-btn:hover {
  text-decoration: underline;
}

.muted-text,
.rev-text,
.mono-dim {
  font-size: 12px;
  color: var(--oc-fg-dim);
}

.empty-line {
  margin: 0;
  padding: 16px;
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  font-size: 13px;
  color: var(--oc-fg-muted);
}

.version-row-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 16px;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  min-width: 0;
}

.version-row-btn:hover {
  background: rgba(255, 255, 255, 0.03);
}

.version-row-btn.is-selected {
  background: var(--oc-surface-2);
}

.version-main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.version-role {
  font-size: 13px;
  font-weight: 500;
  flex-shrink: 0;
}

.version-id {
  font-size: 12px;
  color: var(--oc-fg-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.version-tail {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.state-pill {
  font-size: 11px;
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--oc-surface-2);
  color: var(--oc-fg-muted);
  border: 1px solid var(--oc-border);
}

.version-time {
  font-size: 12px;
  color: var(--oc-fg-dim);
}

.detail-surface {
  padding: 18px;
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 16px;
  min-width: 0;
}

.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.detail-title-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}

.detail-title-group h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.detail-head-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.danger-btn {
  color: var(--oc-danger);
}

.detail-kv {
  margin: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 16px;
  padding: 12px;
  border-radius: 8px;
  background: var(--oc-bg-subtle);
}

.detail-kv > div {
  min-width: 0;
}

.detail-kv dt {
  font-size: 11px;
  color: var(--oc-fg-dim);
  margin-bottom: 2px;
}

.detail-kv dd {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.instructions-block {
  display: grid;
  gap: 6px;
}

.sub-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--oc-fg-muted);
}

.instructions-pre {
  margin: 0;
  max-height: 180px;
  overflow: auto;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-bg);
  border: 1px solid var(--oc-border);
  font-size: 12.5px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.key-rotate-row {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

.key-rotate-row > :first-child {
  flex: 1;
  min-width: 0;
}

.check-section {
  padding-top: 14px;
  border-top: 1px solid var(--oc-border);
  display: grid;
  gap: 10px;
}

.check-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.check-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
}

.help-icon-btn {
  display: inline-flex;
  align-items: center;
  border: 0;
  padding: 2px;
  background: transparent;
  color: var(--oc-fg-dim);
  cursor: pointer;
}

.check-state {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.check-controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.fee-check {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
  cursor: pointer;
}

.dialog-copy {
  margin: 0;
  font-size: 13px;
  color: var(--oc-fg-muted);
}

@media (max-width: 640px) {
  .platform-page {
    padding: 20px 16px 40px;
  }
  .detail-kv {
    grid-template-columns: 1fr;
  }
  .key-rotate-row {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
