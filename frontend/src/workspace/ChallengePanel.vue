<script setup lang="ts">
import { useQueryClient } from '@tanstack/vue-query';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import Button from '../components/ui/Button.vue';
import { message } from './api';
import * as api from './challengeApi';

const props = defineProps<{ owner: string; exerciseId: string; credential: string; disabled: boolean; state: api.Challenge }>();
const client = useQueryClient();
const command = useCommand<api.Challenge>(() => `${props.owner}/${props.exerciseId}`, () => {
  void client.invalidateQueries({ queryKey: ['workspace', props.owner] });
}, { definitiveRejection: (error) => error instanceof ApiError && [400, 401, 403, 404, 409, 410, 429].includes(error.status),
  identityFailure: () => false, errorMessage: message });
function enter() {
  if (props.disabled || !props.credential || !props.state.eligible || command.pending.value || command.uncertain.value) return;
  const owner = props.owner;
  const exercise = props.exerciseId;
  void command.submit((key, signal) => api.enter(owner, exercise, key, props.credential, signal));
}
</script>
<template>
  <section
    v-if="state.available"
    aria-label="预设挑战"
    class="challenge-panel"
  >
    <h2>{{ state.entered ? '挑战已发布' : '可选挑战' }}</h2>
    <p>至多一轮，与基础阶段共用剩余时限。未参与不扣基础分；进入不会修改已保存代码。</p>
    <template v-if="state.entered && state.publishedChallenge">
      <p>进入时的基础验收修订：{{ state.enteredRevision }}。最终验收包含基础回归与挑战。</p>
      <pre class="challenge-text">{{ state.publishedChallenge.requirements }}</pre>
      <details
        v-for="(content, path) in state.publishedChallenge.acceptanceFiles"
        :key="path"
      >
        <summary>挑战验收源码：{{ path }}</summary><pre class="challenge-text">{{ content }}</pre>
      </details>
    </template>
    <template v-else>
      <p>当前保存版本基础验收通过且 AI 任务收敛后可进入；进入后不能退回基础阶段。</p>
      <Button
        variant="secondary"
        :disabled="disabled || !credential || !state.eligible || command.pending.value || command.uncertain.value"
        @click="enter"
      >
        确认进入挑战
      </Button>
    </template>
    <p
      v-if="command.error.value"
      role="alert"
    >
      {{ command.error.value }}
    </p>
    <Button
      v-if="command.uncertain.value"
      variant="secondary"
      :disabled="command.pending.value"
      @click="command.retry"
    >
      核对原挑战请求
    </Button>
  </section>
</template>
<style scoped>
.challenge-panel { border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 1rem; }
.challenge-panel h2 { font-size: 1rem; }
.challenge-text { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 28rem; overflow: auto; font-size: .875rem; }
</style>
