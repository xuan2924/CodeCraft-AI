<script setup lang="ts">
// Presentation-only fixture; the browser spec supplies explicit API substitutes.
import InterviewerPanel from '../src/workspace/InterviewerPanel.vue';
import ChallengePanel from '../src/workspace/ChallengePanel.vue';
import type { Challenge } from '../src/workspace/challengeApi';
const state: Challenge = { available: true, entered: true, eligible: false, reason: 'ENTERED', enteredRevision: '0', baseTestId: 'fixture-base-test',
  publishedChallenge: { requirements: '挑战需求：保留基础规则，按整数分计算折扣。'.repeat(30), acceptanceFiles: { 'VeryLongChallengeAcceptanceTest.java': 'class Test { /* read-only acceptance */ }' } } };
</script>
<template>
  <main class="interview-fixture">
    <h1>面试阶段交互展示</h1>
    <InterviewerPanel
      owner="fixture-owner"
      exercise-id="fixture-exercise"
      credential="fixture-control"
      :disabled="false"
      phase="READY"
    />
    <ChallengePanel
      owner="fixture-owner"
      exercise-id="fixture-exercise"
      credential="fixture-control"
      :disabled="true"
      :state="state"
    />
  </main>
</template>
<style scoped>
.interview-fixture { max-width: 70rem; margin: auto; padding: 1rem; display: grid; gap: 1rem; min-width: 0; }
</style>
