<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useIntervalFn, useTimestamp } from '@vueuse/core';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { RouterLink } from 'vue-router';
import { session } from '../auth/api';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import Button from '../components/ui/Button.vue';
import Dialog from '../components/ui/Dialog.vue';
import Tooltip from '../components/ui/Tooltip.vue';
import TextField from '../components/ui/TextField.vue';
import StatePanel from '../components/ui/StatePanel.vue';
import { routes } from '../routes';
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
const fee = ref(false);
const replacement = ref('');
const retireOpen = ref(false);
const notice = ref('');
const catalogue = useQuery({ queryKey: ['platform-agents', owner, after], queryFn: ({ signal }) => api.list(owner.value, signal, after.value), enabled: allowed, retry: false, refetchInterval: 10000 });
const selectionEnabled = computed(() => allowed.value && !!selected.value);
const detail = useQuery({ queryKey: ['platform-version', owner, selected], queryFn: ({ signal }) => api.get(owner.value, selected.value, signal), enabled: selectionEnabled, retry: false });
const latest = useQuery({ queryKey: ['platform-check', owner, selected], queryFn: ({ signal }) => api.latest(owner.value, selected.value, signal), enabled: selectionEnabled, retry: false, refetchInterval: 3000 });
watch(() => catalogue.data.value, (data) => { if (!selected.value && data?.versions[0]) selected.value = data.versions[0].id; });
watch(owner, () => { after.value = undefined; selected.value = ''; seed.value = null; notice.value = ''; retireOpen.value = false; });
watch([owner, selected], () => { fee.value = false; replacement.value = ''; });
const policy = { definitiveRejection: (failure: unknown) => failure instanceof ApiError && [400, 401, 403, 404, 409, 422, 429].includes(failure.status),
  errorMessage: api.message, identityFailure: (failure: unknown) => failure instanceof ApiError && [401, 403].includes(failure.status) };
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
    clearPrivateQueries(previous); replacement.value = ''; seed.value = null; fee.value = false; notice.value = ''; retireOpen.value = false;
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
  changed(version); selected.value = version.id;
  notice.value = `保存已确认：${version.id} · ${stateLabel(version.state)}。检查与发布需独立确认。`;
}, policy, refreshIdentity);
const action = useCommand<api.Version>(commandScope, (version) => { changed(version); notice.value = `管理操作已确认：${version.id} · ${stateLabel(version.state)}`; }, policy, refreshIdentity);
const check = useCommand<api.Check>(commandScope, (result) => { client.setQueryData(['platform-check', owner.value, result.versionId], result); notice.value = `检查回执：${result.versionId} · ${stateLabel(result.state)}。检查不等于发布。`; }, policy, refreshIdentity);
const busy = computed(() => create.pending.value || create.uncertain.value || action.pending.value || action.uncertain.value || check.pending.value || check.uncertain.value);
const currentCheck = computed(() => latest.data.value?.state === 'SUCCEEDED' && latest.data.value.credentialRevision === detail.data.value?.credentialRevision);
function save(input: api.Draft) { const user = owner.value; void create.submit((key, signal) => api.create(user, input, key, signal)); }
function change(kind: 'publish' | 'retire' | 'credential') {
  const version = detail.data.value; if (!version || busy.value) return;
  const pointer = catalogue.data.value?.roles.find(value => value.role === version.role);
  const input: api.Action = { expectedRevision: version.revision, expectedRoleRevision: pointer?.revision };
  if (kind === 'credential') { input.apiKey = replacement.value; replacement.value = ''; }
  const user = owner.value;
  void action.submit((key, signal) => api.change(user, version.id, kind, input, key, signal));
}
function startCheck() {
  const version = detail.data.value; if (!version || busy.value || !fee.value) return;
  const user = owner.value, input = { expectedRevision: version.revision, feeAcknowledged: true };
  void check.submit((key, signal) => api.check(user, version.id, input, key, signal));
}
function stateLabel(state: string) { return ({ DRAFT: '草稿', PUBLISHED: '已发布', RETIRED: '已退役', QUEUED: '检查排队中', RUNNING: '检查运行中', SUCCEEDED: '检查成功', FAILED: '检查失败' } as Record<string, string>)[state] ?? state; }
</script>
<template>
  <main class="platform-page">
    <header>
      <p class="eyebrow">
        PLATFORM / ROLE CONFIGURATION
      </p><h1>平台角色配置</h1><p>仅管理面试官与评测者，不提供用户私有模型、Skill、代码或对话浏览。检查与业务调用由平台付费，独立于 Copilot 用量。</p>
    </header>
    <StatePanel
      v-if="account.isPending.value || !account.isFetchedAfterMount.value"
      kind="loading"
      title="正在确认权限"
      description="请稍候。"
    />
    <StatePanel
      v-else-if="!allowed"
      kind="error"
      title="需要管理员身份"
      description="请登录管理员账号并完成改密。"
    >
      <RouterLink :to="{ name: routes.account.name }">账号页</RouterLink>
    </StatePanel>
    <template v-else>
      <p
        v-if="notice"
        role="status"
      >
        {{ notice }}
      </p>
      <section
        aria-label="角色当前发布版本"
        class="role-pointers"
      >
        <article
          v-for="pointer in catalogue.data.value?.roles"
          :key="pointer.role"
        >
          <h2>{{ pointer.role === 'INTERVIEWER' ? '面试官' : '评测者' }}</h2><p>{{ pointer.currentVersion ?? '尚未发布，新演练不可用' }}</p><small>发布指针修订 {{ pointer.revision }} · 新发布仅影响新演练</small><Button
            v-if="pointer.currentVersion"
            @click="selected = pointer.currentVersion"
          >
            查看当前发布版本
          </Button>
        </article>
      </section>
      <StatePanel
        v-if="catalogue.isError.value"
        kind="error"
        title="配置列表读取失败"
        description="不使用失效的列表推定发布状态。"
      >
        <Button @click="catalogue.refetch()">
          刷新配置
        </Button>
      </StatePanel>
      <StatePanel
        v-else-if="catalogue.isPending.value"
        kind="loading"
        title="正在读取配置"
        description="请稍候。"
      />
      <section
        v-else
        aria-label="平台版本列表"
        class="version-list"
      >
        <p v-if="!catalogue.data.value?.versions.length">
          暂无平台版本，请保存新草稿。
        </p><Button
          v-for="version in catalogue.data.value?.versions"
          :key="version.id"
          :aria-pressed="selected === version.id"
          @click="selected = version.id"
        >
          {{ version.role === 'INTERVIEWER' ? '面试官' : '评测者' }} · {{ stateLabel(version.state) }} · {{ version.id }}
        </Button><nav aria-label="平台配置分页">
          <Button
            v-if="after"
            @click="after = undefined"
          >
            返回第一页
          </Button><Button
            v-if="catalogue.data.value?.versions.length === 50"
            @click="after = catalogue.data.value.versions.at(-1)?.id"
          >
            下一页
          </Button>
        </nav>
      </section>
      <div class="platform-columns">
        <PlatformDraftForm
          :key="owner"
          :disabled="busy"
          :seed="seed"
          @submit="save"
        />
        <section
          class="version-detail"
          aria-label="平台版本详情"
        >
          <StatePanel
            v-if="!selected"
            title="选择一个版本"
            description="保存草稿后，可显式检查、发布或退役。"
          />
          <StatePanel
            v-else-if="detail.isPending.value"
            kind="loading"
            title="正在读取版本"
            description="请稍候。"
          />
          <StatePanel
            v-else-if="detail.isError.value"
            kind="error"
            title="版本读取失败"
            description="请刷新后操作。"
          >
            <Button @click="detail.refetch()">
              刷新版本
            </Button>
          </StatePanel>
          <template v-else-if="detail.data.value">
            <h2>{{ detail.data.value.definition.connection.name }}</h2><p class="version-id">
              {{ detail.data.value.id }}
            </p><p>{{ stateLabel(detail.data.value.state) }} · 管理修订 {{ detail.data.value.revision }} · 凭证修订 {{ detail.data.value.credentialRevision }}</p>
            <p>{{ detail.data.value.definition.connection.protocol }} · {{ detail.data.value.definition.model.modelId }} · {{ detail.data.value.definition.model.thinkingMode }} · 规则 {{ detail.data.value.definition.policyVersion }}</p><p>凭证：{{ detail.data.value.credentialAvailable ? '已配置，不回显' : '已回收或不可用' }}</p>
            <Button
              :disabled="busy"
              @click="seed = { ...detail.data.value }"
            >
              复制内容到新草稿
            </Button><Button
              :disabled="busy"
              @click="detail.refetch(); catalogue.refetch()"
            >
              刷新当前状态
            </Button>
            <details><summary>查看不可变指令与 Skill</summary><pre>{{ detail.data.value.definition.instructions }}</pre><pre>{{ JSON.stringify(detail.data.value.definition.skills, null, 2) }}</pre></details>
            <section aria-label="平台检查">
              <h3>验证当前版本</h3><p v-if="latest.isPending.value">
                正在读取检查…
              </p><p
                v-else-if="latest.isError.value"
                role="alert"
              >
                无法读取检查状态。<Button @click="latest.refetch()">
                  重试
                </Button>
              </p><p v-else-if="!latest.data.value">
                尚无检查。
              </p><template v-else>
                <p>{{ stateLabel(latest.data.value.state) }} · 检查凭证修订 {{ latest.data.value.credentialRevision }}</p><p v-if="latest.data.value.finishedAt">
                  完成于 {{ new Date(latest.data.value.finishedAt).toLocaleString() }}
                </p><p
                  v-if="latest.data.value.errorCode"
                  role="alert"
                >
                  {{ latest.data.value.errorCode }}
                </p><p v-if="latest.data.value.credentialRevision !== detail.data.value.credentialRevision">
                  这是旧凭证检查，不能认证当前配置。
                </p>
              </template>
              <label><input
                v-model="fee"
                type="checkbox"
              >我确认检查会向所填地址发送平台指令和 Skill，可能产生平台费用。</label><p>一次有界角色/结构检查，不自动重试。未返回 usage 时费用未知；历史成功不代表发布时仍在有效期。</p>
              <Tooltip text="需要确认费用、可用凭证，且当前没有未决操作">
                <span tabindex="0"><Button
                  :disabled="busy || !fee || !detail.data.value.credentialAvailable"
                  @click="startCheck"
                >发起检查</Button></span>
              </Tooltip>
              <Tooltip text="仅草稿可发布，需要当前凭证检查成功；有效期和角色指针由服务端复核">
                <span tabindex="0"><Button
                  :disabled="busy || !currentCheck || detail.data.value.state !== 'DRAFT' || catalogue.isError.value || latest.isError.value"
                  @click="change('publish')"
                >发布供新演练使用</Button></span>
              </Tooltip>
            </section>
            <section>
              <h3>更换失效凭证</h3><p>只替换此版本的 Key，不改变模型、指令或 Skill。后续请求使用新 Key，失败不回退；旧检查立即失效。</p><TextField
                v-model="replacement"
                label="替换平台 API Key"
                type="password"
                autocomplete="new-password"
                maxlength="4096"
              /><Button
                :disabled="busy || !replacement"
                @click="change('credential')"
              >
                保存替换凭证
              </Button>
            </section>
            <Dialog
              v-model:open="retireOpen"
              title="退役平台版本"
              description="禁止新绑定；已有演练与报告重试保持此版本，仍可更换失效 Key。最后引用解除后回收凭证。"
              :busy="action.pending.value"
            >
              <template #trigger>
                <Button
                  variant="danger"
                  :disabled="busy || detail.data.value.state === 'RETIRED'"
                >
                  退役版本
                </Button>
              </template><Button
                variant="danger"
                :disabled="busy"
                @click="change('retire')"
              >
                确认退役
              </Button>
            </Dialog>
          </template>
        </section>
      </div>
      <div
        v-for="(command, index) in [create, action, check]"
        :key="index"
      >
        <p
          v-if="command.pending.value"
          role="status"
        >
          正在提交管理操作…
        </p><p
          v-if="command.error.value"
          role="alert"
        >
          {{ command.error.value }}
        </p><Button
          v-if="command.uncertain.value"
          :disabled="command.pending.value"
          @click="command.retry()"
        >
          核对并重试原操作
        </Button>
      </div>
    </template>
  </main>
</template>
<style scoped>
.platform-page { padding: 24px; max-width: 1400px; margin: auto; } header { margin-bottom: 24px; } h1 { font-size: 30px; } .eyebrow { font-size: 12px; color: var(--accent); letter-spacing: .1em; }
p, small { line-height: 1.7; color: var(--muted); } .role-pointers { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-bottom: 24px; }
.role-pointers article, .version-detail { min-width: 0; padding: 20px; border: 1px solid var(--border); border-radius: var(--radius-panel); background: var(--surface); overflow-wrap: anywhere; }
.role-pointers article { display: flex; flex-direction: column; gap: 8px; } .role-pointers article button { margin-top: auto; }
.version-list { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 24px; } .version-list button { white-space: normal; overflow-wrap: anywhere; max-width: 100%; text-align: left; }
.platform-columns { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 24px; align-items: start; }.version-id { font-family: monospace; }
.version-detail section { border-top: 1px solid var(--border); margin-top: 24px; padding-top: 16px; } .version-detail button { margin: 6px 6px 6px 0; } summary { cursor: pointer; margin: 16px 0; } pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 400px; overflow: auto; }
@media (max-width: 1050px) { .platform-columns { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 600px) { .platform-page { padding: 16px; } .role-pointers { grid-template-columns: minmax(0, 1fr); } }
</style>
