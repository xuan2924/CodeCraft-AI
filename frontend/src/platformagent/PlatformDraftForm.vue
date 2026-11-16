<script setup lang="ts">
import { reactive, ref, useId, watch } from 'vue';
import Button from '../components/ui/Button.vue';
import TextField from '../components/ui/TextField.vue';
import Tooltip from '../components/ui/Tooltip.vue';
import { protocols, presets } from '../modelaccess/api';
import { parsePackage } from '../skills/api';
import type { Draft, Version } from './api';
const props = defineProps<{ disabled: boolean; seed?: Version | null }>();
const emit = defineEmits<{ submit: [input: Draft] }>();
const fieldPrefix = useId();
const key = ref('');
const skillJson = ref('[]');
const error = ref('');
const form = reactive<Draft>({ role: 'INTERVIEWER', instructions: '', skills: [],
  connection: { name: '平台角色', baseUrl: 'https://api.deepseek.com', protocol: 'OPENAI_CHAT', preset: 'DEEPSEEK', apiKey: null, expectedVersion: null },
  model: { name: '平台角色', modelId: 'deepseek-chat', contextWindow: 128000, maxOutputTokens: 4096, thinkingMode: 'OFF', reasoningEffort: null, thinkingBudgetTokens: null, temperature: null, expectedVersion: null } });
watch(() => props.seed, (seed) => {
  key.value = ''; error.value = '';
  if (!seed) return;
  form.role = seed.role; form.instructions = seed.definition.instructions;
  const connection = seed.definition.connection, model = seed.definition.model;
  form.connection = { name: connection.name, baseUrl: connection.baseUrl, protocol: connection.protocol, preset: connection.preset, apiKey: null, expectedVersion: null };
  form.model = { name: model.name, modelId: model.modelId, contextWindow: model.contextWindow, maxOutputTokens: model.maxOutputTokens,
    thinkingMode: model.thinkingMode, reasoningEffort: model.reasoningEffort, thinkingBudgetTokens: model.thinkingBudgetTokens, temperature: model.temperature, expectedVersion: null };
  skillJson.value = JSON.stringify(seed.definition.skills, null, 2);
});
function submit() {
  if (props.disabled) return;
  error.value = '';
  try {
    const packages: unknown = JSON.parse(skillJson.value);
    if (!Array.isArray(packages) || packages.length > 10) throw new Error('Invalid packages');
    const input: Draft = { role: form.role, instructions: form.instructions, skills: packages.map(parsePackage),
      connection: { ...form.connection, apiKey: key.value }, model: { ...form.model,
        reasoningEffort: form.model.thinkingMode === 'ON' ? form.model.reasoningEffort || null : null,
        thinkingBudgetTokens: form.model.thinkingMode === 'ON' && form.connection.protocol === 'ANTHROPIC' && form.model.thinkingBudgetTokens ? Number(form.model.thinkingBudgetTokens) : null,
        temperature: form.model.temperature === null || String(form.model.temperature) === '' ? null : Number(form.model.temperature) } };
    key.value = ''; emit('submit', input);
  } catch { error.value = 'Skill 必须是最多 10 个 formatVersion/files 文本包组成的 JSON 数组。'; }
}
</script>
<template>
  <form
    class="platform-form"
    @submit.prevent="submit"
  >
    <h2>保存新草稿</h2><p>不会覆盖已发布版本。保存不代表检查或发布；新版本需重新填写凭证。</p>
    <fieldset :disabled="disabled">
      <div class="form-grid">
        <label><span :id="`${fieldPrefix}-role`">平台角色</span><select
          v-model="form.role"
          :aria-labelledby="`${fieldPrefix}-role`"
        ><option value="INTERVIEWER">面试官</option><option value="EVALUATOR">评测者</option></select></label>
        <TextField
          v-model="form.connection.name"
          label="配置名称"
          required
          maxlength="128"
        />
        <label><span :id="`${fieldPrefix}-preset`">供应商预设</span><select
          v-model="form.connection.preset"
          :aria-labelledby="`${fieldPrefix}-preset`"
        ><option
          v-for="preset in presets"
          :key="preset"
          :value="preset"
        >{{ preset }}</option></select></label>
        <label><span :id="`${fieldPrefix}-protocol`">请求协议</span><select
          v-model="form.connection.protocol"
          :aria-labelledby="`${fieldPrefix}-protocol`"
        ><option
          v-for="protocol in protocols"
          :key="protocol"
          :value="protocol"
        >{{ protocol }}</option></select></label>
        <TextField
          v-model="form.connection.baseUrl"
          label="HTTPS Base URL"
          type="url"
          required
          maxlength="2048"
          help="地址运营方将接收凭证与请求内容，不跟随重定向。"
        />
        <TextField
          v-model="key"
          label="平台 API Key"
          type="password"
          autocomplete="new-password"
          required
          maxlength="4096"
          help="仅提交到服务端加密保存，不回显，不发送给沙箱。"
        />
        <TextField
          v-model="form.model.name"
          label="模型配置名称"
          required
          maxlength="128"
        />
        <TextField
          v-model="form.model.modelId"
          label="模型 ID"
          required
          maxlength="256"
        />
        <label>上下文窗口<input
          v-model.number="form.model.contextWindow"
          type="number"
          min="2"
          max="2147483647"
          required
        ></label>
        <label>输出 Token 上限<input
          v-model.number="form.model.maxOutputTokens"
          type="number"
          min="1"
          max="16384"
          required
        ></label>
        <label><span :id="`${fieldPrefix}-thinking`">思考模式</span><select
          v-model="form.model.thinkingMode"
          :aria-labelledby="`${fieldPrefix}-thinking`"
        ><option value="OFF">关闭</option><option value="ON">开启</option></select></label>
        <label>Temperature（可留空）<input
          v-model.number="form.model.temperature"
          type="number"
          min="0"
          max="2"
          step="0.1"
        ></label>
        <label v-if="form.model.thinkingMode === 'ON'">Reasoning effort（可留空）<input
          v-model="form.model.reasoningEffort"
          maxlength="32"
        ></label>
        <label v-if="form.model.thinkingMode === 'ON' && form.connection.protocol === 'ANTHROPIC'">Thinking budget<input
          v-model.number="form.model.thinkingBudgetTokens"
          type="number"
          min="1024"
          :max="form.model.maxOutputTokens - 1"
        ></label>
      </div>
      <label>角色指令<textarea
        v-model="form.instructions"
        rows="6"
        required
        maxlength="65536"
      /></label>
      <label>平台 Skill 文本包 JSON 数组<textarea
        v-model="skillJson"
        rows="5"
        maxlength="300000"
        spellcheck="false"
      /></label>
      <p>Skill 使用现有 formatVersion: 1 / files 包格式；只能包含 SKILL.md 与 references 文本。指令与 Skill 合计最多 64 KiB，不能覆盖 Java 权限和阶段逻辑。</p>
    </fieldset>
    <p
      v-if="error"
      role="alert"
    >
      {{ error }}
    </p>
    <Tooltip :text="disabled ? '请先完成或核对当前管理操作' : '只保存草稿，不发起收费检查'">
      <span :tabindex="disabled ? 0 : undefined"><Button
        variant="primary"
        type="submit"
        :disabled="disabled"
      >保存新草稿</Button></span>
    </Tooltip>
  </form>
</template>
<style scoped>
.platform-form { padding: 24px; background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-panel); }
fieldset { border: 0; margin: 0; padding: 0; min-width: 0; } .form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
label { display: grid; gap: 6px; margin-bottom: 16px; font-size: 14px; }
input, select, textarea { width: 100%; min-width: 0; background: var(--background); color: var(--foreground); border: 1px solid var(--control-border); border-radius: var(--radius-control); padding: 10px; font: inherit; }
textarea { resize: vertical; } p { color: var(--muted); font-size: 13px; line-height: 1.7; } h2 { margin-top: 0; }
@media (max-width: 850px) { .form-grid { grid-template-columns: minmax(0, 1fr); } .platform-form { padding: 16px; } }
</style>
