<script setup lang="ts">
import { computed, ref } from 'vue';
import { useQueryClient } from '@tanstack/vue-query';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import Button from '../components/ui/Button.vue';
import { message } from './api';
import * as api from './feedbackApi';
const props = defineProps<{ owner: string; admin: boolean; value: api.Feedback }>();
const reply = ref('');
const replyBytes = computed(() => new globalThis.TextEncoder().encode(reply.value).length);
const client = useQueryClient();
const command = useCommand<api.Feedback>(() => `${props.owner}/${props.value.id}`, () => { reply.value = ''; void client.invalidateQueries({ queryKey: ['feedback'] }); }, {
  definitiveRejection: error => error instanceof ApiError && [400, 401, 403, 404, 409, 410, 429].includes(error.status), errorMessage: message, identityFailure: () => false });
function submit() {
  if (!reply.value.trim() || new globalThis.TextEncoder().encode(reply.value).length > 4096 || command.pending.value || command.uncertain.value) return;
  const { owner, value } = props;
  const content = reply.value;
  void command.submit((key, signal) => api.reply(owner, value.id, content, key, signal));
}
</script>
<template>
  <article class="feedback-entry">
    <header><h2>{{ value.state === 'PENDING' ? '待处理' : '已处理' }}</h2><p>{{ value.createdAt }} · {{ value.id }}</p></header>
    <details><summary>查看完整授权快照</summary><pre>{{ value.snapshot }}</pre></details>
    <p
      v-if="value.reply"
      class="reply"
    >
      处理回复：{{ value.reply }}
    </p>
    <form
      v-if="admin && value.state === 'PENDING'"
      @submit.prevent="submit"
    >
      <label>一次性处理回复（最多 4 KiB）<textarea
        v-model="reply"
        rows="4"
        :disabled="command.pending.value || command.uncertain.value"
      /></label><p>当前 {{ replyBytes }} / 4096 字节；回复提交后不可修改。</p><Button
        type="submit"
        variant="primary"
        :disabled="!reply.trim() || replyBytes > 4096 || command.pending.value || command.uncertain.value"
      >
        回复并标记已处理
      </Button>
    </form>
    <p
      v-if="command.error.value"
      role="alert"
    >
      {{ command.error.value }}
    </p><Button
      v-if="command.uncertain.value"
      variant="secondary"
      :disabled="command.pending.value"
      @click="command.retry"
    >
      核对原处理请求
    </Button>
  </article>
</template>
<style scoped>
.feedback-entry {
  padding: 20px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  border-radius: 12px;
  display: grid;
  gap: 12px;
}

header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 16px;
}

header p {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
  overflow-wrap: anywhere;
}

h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 460px;
  overflow: auto;
  font-size: 12.5px;
  padding: 12px;
  border-radius: 8px;
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
}

label {
  display: grid;
  gap: 6px;
  font-size: 13px;
  font-weight: 550;
}

textarea {
  display: block;
  box-sizing: border-box;
  width: 100%;
  font: inherit;
  padding: 10px 12px;
  color: var(--oc-fg);
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border-input);
  border-radius: 8px;
}

.reply {
  margin: 0;
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--oc-accent-soft);
  border: 1px solid var(--oc-border-accent);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 13.5px;
}

summary {
  cursor: pointer;
  font-size: 13px;
  color: var(--oc-fg-muted);
}
</style>
