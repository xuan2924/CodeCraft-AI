<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue';
import Button from '../components/ui/Button.vue';
import * as api from './api';
import { useModelCommand } from './useModelCommand';

const providerPresets = api.presets;
const props = defineProps<{ owner: string; connection: api.Connection | null }>();
const emit = defineEmits<{ saved: [connection: api.Connection]; cancel: []; unauthorized: [] }>();
const scope = computed(() => `${props.owner}/${props.connection?.id ?? 'new'}`);
const draft = reactive({ name: '', baseUrl: '', protocol: 'OPENAI_CHAT' as api.Protocol, preset: 'CUSTOM' as api.Preset, apiKey: '' });
const expectedVersion = ref<string | null>(null);
const validation = ref('');
watch(scope, () => {
  Object.assign(draft, { name: props.connection?.name ?? '', baseUrl: props.connection?.baseUrl ?? '', protocol: props.connection?.protocol ?? 'OPENAI_CHAT',
    preset: props.connection?.preset ?? 'CUSTOM', apiKey: '' });
  expectedVersion.value = props.connection?.version ?? null;
  validation.value = '';
}, { immediate: true, flush: 'sync' });
// Same-resource background refetches must not overwrite an in-progress draft or its optimistic version.
const command = useModelCommand<api.Connection>(() => scope.value, (value) => { draft.apiKey = ''; emit('saved', value); }, () => emit('unauthorized'));
const locked = computed(() => command.pending.value || command.uncertain.value);
const prefix = computed(() => `connection-${props.connection?.id ?? 'new'}`);
onScopeDispose(() => { draft.apiKey = ''; });
async function save() {
  if (locked.value || !props.owner) return;
  validation.value = '';
  if (!draft.name.trim() || draft.name.trim().length > 128) { validation.value = '请输入不超过 128 字的连接名称。'; return; }
  try {
    const url = new globalThis.URL(draft.baseUrl);
    if (url.protocol !== 'https:' || url.username || url.password || url.hash || url.port === '0' || /\s/.test(draft.baseUrl) || draft.baseUrl.length > 2048) throw new Error('invalid');
  } catch { validation.value = '请输入完整 HTTPS 地址，不允许内嵌用户名、密码或片段。'; return; }
  if ((!props.connection || draft.apiKey) && !/^[!-~]{1,4096}$/.test(draft.apiKey)) {
    validation.value = '请输入有效 Key（不含空格，最多 4096 个 ASCII 字符）。'; return;
  }
  const input: api.ConnectionInput = Object.freeze({ name: draft.name.trim(), baseUrl: draft.baseUrl, protocol: draft.protocol,
    preset: draft.preset, apiKey: draft.apiKey || null, expectedVersion: expectedVersion.value });
  const id = props.connection?.id ?? null; const owner = props.owner;
  draft.apiKey = '';
  await command.submit((key, signal) => api.saveConnection(id, input, key, signal, owner));
}
</script>

<template>
  <form
    class="connection-form"
    @submit.prevent="save"
  >
    <fieldset :disabled="locked">
      <legend>{{ connection ? '编辑连接' : '新建连接' }}</legend>
      <div class="form-grid">
        <div class="field-item">
          <label :for="`${prefix}-name`">连接名称</label>
          <input
            :id="`${prefix}-name`"
            v-model="draft.name"
            required
            maxlength="128"
            autocomplete="off"
          >
        </div>
        <div class="field-item">
          <label :for="`${prefix}-protocol`">协议</label>
          <select
            :id="`${prefix}-protocol`"
            v-model="draft.protocol"
          >
            <option value="OPENAI_CHAT">
              OpenAI Chat Completions
            </option>
            <option value="OPENAI_RESPONSES">
              OpenAI Responses
            </option>
            <option value="ANTHROPIC">
              Anthropic Messages
            </option>
          </select>
        </div>
        <div class="field-item">
          <label :for="`${prefix}-preset`">供应商适配</label>
          <select
            :id="`${prefix}-preset`"
            v-model="draft.preset"
          >
            <option
              v-for="preset in providerPresets"
              :key="preset"
              :value="preset"
            >
              {{ preset === 'CUSTOM' ? '自定义兼容供应商' : preset }}
            </option>
          </select>
        </div>
        <div class="field-item">
          <label :for="`${prefix}-key`">{{ connection ? '替换 API Key（留空保留）' : 'API Key' }}</label>
          <input
            :id="`${prefix}-key`"
            v-model="draft.apiKey"
            type="password"
            :required="!connection"
            maxlength="4096"
            autocomplete="off"
            spellcheck="false"
            :aria-describedby="`${prefix}-key-help`"
          >
          <p
            :id="`${prefix}-key-help`"
            class="help"
          >
            {{ connection?.keyConfigured ? '已配置 Key，不提供回显。' : 'Key 由服务端加密保存。' }}提交后清空输入，不写入浏览器存储。
          </p>
        </div>
        <div class="field-item field-span">
          <label :for="`${prefix}-url`">Base URL（HTTPS）</label>
          <input
            :id="`${prefix}-url`"
            v-model="draft.baseUrl"
            required
            type="url"
            maxlength="2048"
            autocomplete="off"
            :aria-describedby="`${prefix}-url-help`"
          >
          <p
            :id="`${prefix}-url-help`"
            class="help"
          >
            按供应商文档填写完整基础路径。不会探测地址、切换协议或使用环境 Key。该地址的运营方会收到 Key 和请求内容；同名模型不保证实际模型或价格相同。
          </p>
        </div>
      </div>
    </fieldset>
    <p class="help">
      保存不会自动发起模型检查。实际配置变更后，旧检查结果不再有效。
    </p>
    <p
      v-if="validation || command.error.value"
      role="alert"
      class="failure"
    >
      {{ validation || command.error.value }}
    </p>
    <p
      v-if="command.uncertain.value"
      role="status"
    >
      提交结果尚未确认。只能用原请求核对并重试；关闭表单不代表操作未执行。
    </p>
    <div class="actions">
      <Button
        v-if="command.uncertain.value"
        :loading="command.pending.value"
        :disabled="command.pending.value"
        @click="command.retry"
      >
        用原请求重试
      </Button>
      <Button
        v-else
        type="submit"
        variant="primary"
        :loading="command.pending.value"
        :disabled="command.pending.value"
      >
        保存连接
      </Button>
      <Button
        :disabled="command.pending.value"
        @click="emit('cancel')"
      >
        关闭表单
      </Button>
    </div>
  </form>
</template>

<style scoped>
.connection-form { max-width: 720px; display: grid; gap: 12px; }
fieldset {
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-panel);
  background: var(--oc-bg-subtle);
  padding: 18px;
  min-width: 0;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 16px;
}
.field-item { display: grid; gap: 6px; align-content: start; min-width: 0; }
.field-span { grid-column: 1 / -1; }
legend { padding: 0 8px; font-size: 15px; font-weight: 600; }
label { font-size: 12.5px; font-weight: 550; }
input, select {
  width: 100%;
  min-width: 0;
  padding: 8px 10px;
}
.help { color: var(--oc-fg-muted); font-size: 12px; line-height: 1.5; margin: 0; }
.failure { color: var(--oc-danger); margin: 0; font-size: 12.5px; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; }
@media (max-width: 640px) {
  .form-grid { grid-template-columns: 1fr; }
}
</style>
