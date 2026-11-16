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
.feedback-entry { padding:24px; border:1px solid var(--border); border-radius:var(--radius-lg); margin:24px 0; } header p { color:var(--muted); overflow-wrap:anywhere; } h2 { font-size:18px; } pre { white-space:pre-wrap; overflow-wrap:anywhere; max-height:480px; overflow:auto; font-size:13px; } textarea { display:block; box-sizing:border-box; width:100%; font:inherit; padding:12px; margin:8px 0 16px; color:var(--foreground); background:var(--surface); border:1px solid var(--border); } .reply { white-space:pre-wrap; overflow-wrap:anywhere; } summary { cursor:pointer; padding:12px 0; }
</style>
