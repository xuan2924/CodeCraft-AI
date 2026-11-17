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
    class="challenge-section"
  >
    <div class="challenge-head">
      <h2>{{ state.entered ? '进阶挑战' : '可选进阶挑战' }}</h2>
      <span class="challenge-badge">{{ state.entered ? '已进入' : '可开启' }}</span>
    </div>
    <template v-if="state.entered && state.publishedChallenge">
      <p class="challenge-meta font-mono-num">
        基线修订 r{{ state.enteredRevision }}
      </p>
      <pre class="challenge-text">{{ state.publishedChallenge.requirements }}</pre>
      <details
        v-for="(content, path) in state.publishedChallenge.acceptanceFiles"
        :key="path"
        class="challenge-details"
      >
        <summary>{{ path }}</summary>
        <pre class="challenge-text">{{ content }}</pre>
      </details>
    </template>
    <template v-else>
      <p class="challenge-desc">
        当前保存版本基础验收通过且 AI 任务完成后可进入（进入后不可退回）。
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
.challenge-section {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--oc-border);
  display: grid;
  gap: 8px;
}

.challenge-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.challenge-section h2 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.challenge-badge {
  font-size: 11px;
  color: var(--oc-accent);
}

.challenge-desc,
.challenge-meta {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--oc-fg-muted);
}

.challenge-text {
  margin: 0;
  padding: 10px;
  border-radius: var(--radius-control);
  background: var(--oc-bg);
  border: 1px solid var(--oc-border);
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-x: auto;
}

.challenge-details summary {
  font-size: 12px;
  color: var(--oc-fg-muted);
  cursor: pointer;
}

.challenge-error {
  margin: 0;
  font-size: 12px;
  color: var(--oc-danger);
}
</style>
