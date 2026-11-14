<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { NButton } from 'naive-ui';
import * as api from './api';
import { useModelCommand } from './useModelCommand';
const props = defineProps<{ owner: string; connection: api.Connection; model: api.Model | null }>();
const emit = defineEmits<{ saved: [model: api.Model]; cancel: []; unauthorized: [] }>();
const scope = computed(() => `${props.owner}/${props.connection.id}/${props.connection.configurationVersion}/${props.model?.id ?? 'new'}`);
const draft = reactive({ name: '', modelId: '', contextWindow: '', maxOutputTokens: '4096', thinkingMode: 'OFF' as 'OFF' | 'ON', reasoningEffort: '', thinkingBudgetTokens: '', temperature: '' });
const version = ref<string | null>(null);
const validation = ref('');
watch(scope, () => {
  const model = props.model;
  Object.assign(draft, { name: model?.name ?? '', modelId: model?.modelId ?? '', contextWindow: String(model?.contextWindow ?? ''), maxOutputTokens: String(model?.maxOutputTokens ?? 4096),
    thinkingMode: model?.thinkingMode ?? 'OFF', reasoningEffort: model?.reasoningEffort ?? '', thinkingBudgetTokens: String(model?.thinkingBudgetTokens ?? ''), temperature: String(model?.temperature ?? '') });
  version.value = model?.version ?? null; validation.value = '';
}, { immediate: true, flush: 'sync' });
const command = useModelCommand<api.Model>(() => scope.value, (value) => emit('saved', value), () => emit('unauthorized'));
const locked = computed(() => command.pending.value || command.uncertain.value);
const prefix = computed(() => `model-${props.model?.id ?? 'new'}`);
function save() {
  if (locked.value || !props.owner) return;
  validation.value = '';
  const window = Number(draft.contextWindow); const output = Number(draft.maxOutputTokens);
  const temperature = draft.temperature === '' ? null : Number(draft.temperature);
  const budget = props.connection.protocol === 'ANTHROPIC' && draft.thinkingMode === 'ON' && draft.thinkingBudgetTokens !== '' ? Number(draft.thinkingBudgetTokens) : null;
  const effort = draft.thinkingMode === 'ON' && draft.reasoningEffort !== '' ? draft.reasoningEffort : null;
  if (!draft.name.trim() || draft.name.trim().length > 128 || !draft.modelId || draft.modelId.length > 256 || /\s/.test(draft.modelId) || Array.from(draft.modelId).some((character) => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127)) {
    validation.value = '请填写有效名称和不含空白的模型 ID。'; return;
  }
  if (!Number.isInteger(window) || window < 2 || window > 2147483647 || !Number.isInteger(output) || output < 1 || output > 16384 || output >= window) {
    validation.value = '窗口须为整数；输出须为 1–16384 且小于窗口。'; return;
  }
  if (temperature !== null && (!Number.isFinite(temperature) || temperature < 0 || temperature > 2) || effort !== null && !/^[a-z_]{1,32}$/.test(effort)
    || budget !== null && (!Number.isInteger(budget) || budget < 1024 || budget >= output) || props.connection.protocol === 'ANTHROPIC' && draft.thinkingMode === 'ON' && output <= 1024) {
    validation.value = '请检查温度与思考参数；Anthropic 思考预算至少 1024，且须小于输出上限。'; return;
  }
  const input: api.ModelInput = Object.freeze({ name: draft.name.trim(), modelId: draft.modelId, contextWindow: window, maxOutputTokens: output,
    thinkingMode: draft.thinkingMode, reasoningEffort: effort, thinkingBudgetTokens: budget, temperature, expectedVersion: version.value });
  const connectionId = props.connection.id; const id = props.model?.id ?? null; const owner = props.owner;
  void command.submit((key, signal) => api.saveModel(connectionId, id, input, key, signal, owner));
}
</script>
<template>
  <form @submit.prevent="save">
    <fieldset :disabled="locked">
      <legend>{{ model ? '编辑模型' : '新增模型' }}</legend>
      <label :for="`${prefix}-name`">显示名称</label><input
        :id="`${prefix}-name`"
        v-model="draft.name"
        required
        maxlength="128"
      >
      <label :for="`${prefix}-id`">供应商模型 ID</label><input
        :id="`${prefix}-id`"
        v-model="draft.modelId"
        required
        maxlength="256"
        autocomplete="off"
      >
      <label :for="`${prefix}-window`">上下文窗口（Token，用户提供）</label><input
        :id="`${prefix}-window`"
        v-model="draft.contextWindow"
        type="number"
        min="2"
        max="2147483647"
        required
      >
      <label :for="`${prefix}-output`">单次最大输出 Token</label><input
        :id="`${prefix}-output`"
        v-model="draft.maxOutputTokens"
        type="number"
        min="1"
        max="16384"
        required
      >
      <label :for="`${prefix}-thinking`">思考模式</label><select
        :id="`${prefix}-thinking`"
        v-model="draft.thinkingMode"
      >
        <option value="OFF">
          关闭
        </option><option value="ON">
          开启
        </option>
      </select>
      <template v-if="draft.thinkingMode === 'ON'">
        <label :for="`${prefix}-effort`">reasoning effort（可选，以供应商支持为准）</label><input
          :id="`${prefix}-effort`"
          v-model="draft.reasoningEffort"
          maxlength="32"
        >
        <template v-if="connection.protocol === 'ANTHROPIC'">
          <label :for="`${prefix}-budget`">思考预算 Token（可选，默认 1024）</label><input
            :id="`${prefix}-budget`"
            v-model="draft.thinkingBudgetTokens"
            type="number"
            min="1024"
          >
        </template>
      </template>
      <label :for="`${prefix}-temperature`">温度（可选，0–2）</label><input
        :id="`${prefix}-temperature`"
        v-model="draft.temperature"
        type="number"
        min="0"
        max="2"
        step="any"
      >
    </fieldset>
    <p class="help">
      窗口为你提供的容量声明，检查不会验证标称容量。不会自动调整协议或思考模式；部分供应商可能忽略温度。
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
      结果未确认；重试使用原参数和原幂等键。关闭不代表操作未执行。
    </p>
    <div class="actions">
      <NButton
        v-if="command.uncertain.value"
        :disabled="command.pending.value"
        :loading="command.pending.value"
        @click="command.retry"
      >
        用原请求重试
      </NButton>
      <NButton
        v-else
        attr-type="submit"
        type="primary"
        :disabled="command.pending.value"
        :loading="command.pending.value"
      >
        保存模型
      </NButton>
      <NButton
        :disabled="command.pending.value"
        @click="emit('cancel')"
      >
        关闭表单
      </NButton>
    </div>
  </form>
</template>
<style scoped>
fieldset { display: grid; gap: 10px; border: 1px solid var(--line); padding: 20px; min-width: 0; }
legend { padding: 0 8px; font-size: 18px; font-weight: 600; }
input, select { width: 100%; min-width: 0; padding: 10px; font: inherit; color: inherit; background: white; border: 1px solid var(--line); border-radius: 4px; }
.help { color: var(--muted); font-size: 14px; }
.actions { display: flex; flex-wrap: wrap; gap: 12px; }
</style>
