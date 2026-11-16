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
    <div class="captcha-image">
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
        >{{ pending ? '正在生成图片…' : '请刷新验证码' }}</span>
      </div>
      <Button
        variant="secondary"
        size="small"
        :disabled="disabled || pending"
        @click="refresh"
      >
        刷新图片
      </Button>
    </div>
    <input
      id="login-captcha"
      v-model="answer"
      autocomplete="off"
      maxlength="5"
      placeholder="输入图中 5 位字符"
      :disabled="disabled || !challenge"
      aria-describedby="captcha-help"
      @input="emit('change', challenge?.id ?? '', answer)"
    >
    <small id="captcha-help">不区分大小写。图片两分钟内有效，每次登录尝试后需获取新图片。</small>
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
  gap: 8px;
  min-width: 0;
}

.captcha-field label {
  font-size: 13px;
  font-weight: 550;
  color: var(--oc-fg);
}

.captcha-image {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.captcha-frame {
  display: grid;
  place-items: center;
  min-width: 160px;
  min-height: 56px;
  padding: 2px;
  border-radius: 8px;
  border: 1px solid var(--oc-border-strong);
  background: var(--oc-bg-input);
  overflow: hidden;
}

.captcha-frame img {
  display: block;
  border-radius: 6px;
}

.captcha-placeholder {
  font-size: 12px;
  color: var(--oc-fg-muted);
  padding: 0 12px;
}

input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--oc-border-input);
  border-radius: var(--radius-control);
  background: var(--oc-bg-input);
  color: var(--oc-fg);
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.35);
  transition:
    border-color 180ms var(--oc-ease),
    box-shadow 180ms var(--oc-ease);
}

input:hover:not(:disabled) {
  border-color: #7c8492;
}

input:focus-visible {
  border-color: var(--oc-accent);
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.35), 0 0 0 3px var(--oc-accent-glow);
}

small {
  color: var(--oc-fg-muted);
  font-size: 12.5px;
  line-height: 1.55;
}

.captcha-error {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-danger);
}
</style>
