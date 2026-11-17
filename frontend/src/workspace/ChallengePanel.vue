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
    <div class="challenge-head">
      <h2>{{ state.entered ? '挑战已发布' : '可选挑战' }}</h2>
      <span class="challenge-pill">{{ state.entered ? '已进入' : '单轮可选' }}</span>
    </div>
    <p class="challenge-desc">
      至多一轮，与基础阶段共用剩余时限。未参与不扣基础分；进入不会修改已保存代码。
    </p>
    <template v-if="state.entered && state.publishedChallenge">
      <p class="challenge-meta">
        进入时的基础验收修订：{{ state.enteredRevision }}。最终验收包含基础回归与挑战。
      </p>
      <pre class="challenge-text">{{ state.publishedChallenge.requirements }}</pre>
      <details
        v-for="(content, path) in state.publishedChallenge.acceptanceFiles"
        :key="path"
        class="challenge-details"
      >
        <summary>挑战验收源码：{{ path }}</summary>
        <pre class="challenge-text">{{ content }}</pre>
      </details>
    </template>
    <template v-else>
      <p class="challenge-desc">
        当前保存版本基础验收通过且 AI 任务收敛后可进入；进入后不能退回基础阶段。
      </p>
      <Button
        variant="secondary"
        size="small"
        :disabled="disabled || !credential || !state.eligible || command.pending.value || command.uncertain.value"
        @click="enter"
      >
        确认进入挑战
      </Button>
    </template>
    <p
      v-if="command.error.value"
      role="alert"
      class="challenge-error"
    >
      {{ command.error.value }}
    </p>
    <Button
      v-if="command.uncertain.value"
      variant="secondary"
      size="small"
      :disabled="command.pending.value"
      @click="command.retry"
    >
      核对原挑战请求
    </Button>
  </section>
</template>
<style scoped>
.challenge-panel {
  border: 1px solid var(--oc-border);
  border-left: 3px solid var(--oc-accent);
  border-radius: var(--radius-control);
  padding: 12px 14px;
  margin: 0;
  background: var(--oc-surface);
  display: grid;
  gap: 8px;
  flex-shrink: 0;
}

.challenge-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.challenge-panel h2 {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
}

.challenge-pill {
  font-size: 11px;
  font-weight: 550;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--oc-accent-soft);
  border: 1px solid var(--oc-border-accent);
  color: var(--oc-accent);
}

.challenge-desc,
.challenge-meta {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
  line-height: 1.5;
}

.challenge-text {
  margin: 2px 0 0;
  padding: 10px 12px;
  border-radius: var(--radius-control);
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 18rem;
  overflow: auto;
  font-size: 12.5px;
  line-height: 1.55;
}

.challenge-details summary {
  cursor: pointer;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.challenge-error {
  margin: 0;
  font-size: 13px;
  color: var(--oc-danger);
}
</style>
