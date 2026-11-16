import { afterEach, expect, it, vi } from 'vitest';
import { effectScope, nextTick, ref } from 'vue';
import type { Exercise } from './api';
import { useExerciseTimer } from './useExerciseTimer';

afterEach(() => vi.useRealTimers());
const fixture = (): Exercise => ({ exerciseId: 'one', problemVersionId: 'problem', workspaceInstanceId: 'workspace',
  state: 'ACTIVE', revision: '0', paths: [], preparationFailureReason: null, cleanupState: null,
  serverTime: '2026-01-01T00:00:00Z', deadlineAt: '2026-01-01T00:00:03Z' });

it('projects server time, clamps expiry and disposes its ticker', () => {
  vi.useFakeTimers(); vi.setSystemTime('2026-01-01T01:00:00Z');
  const scope = effectScope();
  const timer = scope.run(() => useExerciseTimer(ref(fixture())))!;
  expect(timer.remaining.value).toBe('00:03');
  expect(timer.expired.value).toBe(false);
  vi.advanceTimersByTime(4000);
  expect(timer.remaining.value).toBe('00:00');
  expect(timer.expired.value).toBe(true);
  scope.stop();
  expect(vi.getTimerCount()).toBe(0);
});

it('freezes paused time and resets the projection when the exercise changes', async () => {
  vi.useFakeTimers(); vi.setSystemTime('2026-01-01T00:00:00Z');
  const exercise = ref<Exercise | null>(fixture());
  const scope = effectScope();
  const timer = scope.run(() => useExerciseTimer(exercise))!;
  exercise.value = { ...fixture(), state: 'PAUSED', deadlineAt: null,
    pause: { pauseOperationId: 'pause', resumeOperationId: null, remainingMillis: 42000, confirmedAt: '2026-01-01T00:00:00Z' } };
  await nextTick(); vi.advanceTimersByTime(100000);
  expect(timer.remaining.value).toBe('00:42');
  expect(timer.expired.value).toBe(false);
  exercise.value = null; await nextTick();
  expect(timer.remaining.value).toBe('');
  expect(timer.expired.value).toBe(false);
  exercise.value = { ...fixture(), exerciseId: 'two', serverTime: '2026-01-01T00:00:01Z' };
  await nextTick();
  expect(timer.remaining.value).toBe('00:02');
  scope.stop();
});
