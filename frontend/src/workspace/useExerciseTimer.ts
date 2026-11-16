import { computed, onScopeDispose, ref, watch, type Ref } from 'vue';
import type { Exercise } from './api';

/** Display-only server-clock projection; admission and finalization remain server-owned. */
export function useExerciseTimer(exercise: Readonly<Ref<Exercise | null | undefined>>) {
  const now = ref(Date.now());
  const serverOffset = ref(0);
  const tick = globalThis.setInterval(() => { now.value = Date.now(); }, 1000);
  onScopeDispose(() => globalThis.clearInterval(tick));
  watch(() => exercise.value?.serverTime, (value) => {
    serverOffset.value = value ? Date.parse(value) - Date.now() : 0;
  }, { immediate: true });
  const expired = computed(() => !!exercise.value && ['ACTIVE', 'PAUSING'].includes(exercise.value.state)
    && !!exercise.value.deadlineAt && now.value + serverOffset.value >= Date.parse(exercise.value.deadlineAt));
  const remaining = computed(() => {
    const current = exercise.value;
    const millis = current && ['PAUSED', 'RESUMING'].includes(current.state)
      ? current.pause?.remainingMillis ?? Number.NaN : Date.parse(current?.deadlineAt ?? '') - now.value - serverOffset.value;
    const seconds = Math.max(0, Math.ceil(millis / 1000));
    return Number.isFinite(seconds) ? `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}` : '';
  });
  return { expired, remaining };
}
