<script setup lang="ts">
import { onMounted, onScopeDispose, ref, watch } from 'vue';
import Button from '../components/ui/Button.vue';
import { captcha, errorMessage, type Captcha } from './api';

const props = defineProps<{ disabled: boolean }>();
const emit = defineEmits<{ change: [id: string, answer: string] }>();
const challenge = ref<Captcha | null>(null);
const answer = ref('');
const pending = ref(false);
const error = ref('');
let epoch = 0;
let controller: globalThis.AbortController | null = null;
let expiry: ReturnType<typeof globalThis.setTimeout> | undefined;
function clear() {
  challenge.value = null;
  answer.value = '';
  emit('change', '', '');
  globalThis.clearTimeout(expiry);
}
async function refresh() {
  if (props.disabled || pending.value) return;
  const request = ++epoch;
  controller?.abort();
  controller = new globalThis.AbortController();
  clear();
  pending.value = true;
  error.value = '';
  try {
    const result = await captcha(controller.signal);
    if (request !== epoch) return;
    const remaining = Date.parse(result.expiresAt) - Date.now();
    if (remaining <= 0) { error.value = '验证码已过期，请刷新图片。'; return; }
    challenge.value = result;
    expiry = globalThis.setTimeout(() => { clear(); error.value = '验证码已过期，请刷新图片。'; }, Math.min(remaining, 120_000));
  } catch (failure) {
    if (request === epoch) error.value = errorMessage(failure);
  } finally { if (request === epoch) pending.value = false; }
}
onMounted(refresh);
watch(() => props.disabled, (disabled) => {
  if (!disabled && !challenge.value && !error.value) void refresh();
});
onScopeDispose(() => { epoch++; controller?.abort(); globalThis.clearTimeout(expiry); });
</script>

<template>
  <div
    class="captcha-field"
    :aria-busy="pending"
  >
    <label for="login-captcha">图片验证码</label>
    <div class="captcha-row">
      <div class="captcha-frame">
        <img
          v-if="challenge"
          :src="challenge.image"
          alt="登录验证码图片，请输入图中字符"
          width="160"
          height="56"
        >
        <span
          v-else
          role="status"
          class="captcha-placeholder"
        >{{ pending ? '正在生成…' : '请刷新' }}</span>
      </div>
      <div class="captcha-input-group">
        <div class="captcha-controls">
          <input
            id="login-captcha"
            v-model="answer"
            autocomplete="off"
            maxlength="5"
            placeholder="5 位字符"
            :disabled="disabled || !challenge"
            aria-describedby="captcha-help"
            @input="emit('change', challenge?.id ?? '', answer)"
          >
          <Button
            variant="secondary"
            size="small"
            class="captcha-refresh"
            :disabled="disabled || pending"
            @click="refresh"
          >
            刷新图片
          </Button>
        </div>
        <small id="captcha-help">不区分大小写，2 分钟内有效，每次尝试后更新。</small>
      </div>
    </div>
    <p
      v-if="error"
      role="alert"
      class="captcha-error"
    >
      {{ error }}
    </p>
  </div>
</template>

<style scoped>
.captcha-field {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.captcha-field label {
  font-size: 13px;
  font-weight: 550;
  color: var(--oc-fg);
}

.captcha-row {
  display: grid;
  grid-template-columns: 160px minmax(0, 1fr);
  gap: 10px;
  align-items: start;
}

.captcha-frame {
  display: grid;
  place-items: center;
  width: 160px;
  height: 56px;
  padding: 2px;
  border-radius: var(--radius-control);
  border: 1px solid var(--oc-border-strong);
  background: var(--oc-bg-input);
  overflow: hidden;
}

.captcha-frame img {
  display: block;
  width: 154px;
  height: 50px;
  object-fit: cover;
  border-radius: 4px;
}

.captcha-placeholder {
  font-size: 12px;
  color: var(--oc-fg-muted);
  padding: 0 10px;
}

.captcha-input-group {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.captcha-controls {
  display: flex;
  align-items: stretch;
  gap: 8px;
  min-width: 0;
}

.captcha-controls input {
  flex: 1 1 0%;
  min-width: 0;
  width: 100%;
  padding: 8px 10px;
  font-family: var(--oc-font-mono);
  letter-spacing: 0.08em;
}

.captcha-refresh {
  flex-shrink: 0;
  white-space: nowrap;
}

small {
  color: var(--oc-fg-muted);
  font-size: 11.5px;
  line-height: 1.45;
}

.captcha-error {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-danger);
}

@media (max-width: 480px) {
  .captcha-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
