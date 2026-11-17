import { afterEach, expect, it, vi } from 'vitest';
import { ask, history } from './interviewerApi';

const baseTask = {
  id: 'task-1',
  exerciseId: 'ex-1',
  question: '边界如何取舍？',
  answer: '优先半便士舍入。',
  state: 'SUCCEEDED',
  reason: null,
  platformVersionId: 'pv-1',
  phase: 'ACTIVE',
  stage: 'BASE',
  mode: 'HINT',
  topic: 'precision',
  hintLevel: 2,
  revision: '3',
  createdAt: '2026-11-17T03:00:00Z',
  completedAt: '2026-11-17T03:00:05Z',
};

afterEach(() => vi.unstubAllGlobals());

it('parses stateful interviewer fields and forwards mode/topic on ask', async () => {
  const fetcher = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'OK', data: [baseTask] })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'OK', data: { token: 'csrf' } })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'OK', data: { ...baseTask, mode: 'DEFENSE', hintLevel: null } })));
  vi.stubGlobal('fetch', fetcher);

  const items = await history('owner', 'ex-1', '', new AbortController().signal);
  expect(items[0]).toMatchObject({ stage: 'BASE', mode: 'HINT', topic: 'precision', hintLevel: 2 });

  const created = await ask('owner', 'ex-1', '请追问', 'idem-1', 'cred-1', new AbortController().signal, { mode: 'DEFENSE' });
  expect(created.mode).toBe('DEFENSE');
  expect(created.hintLevel).toBeNull();
  expect(JSON.parse(fetcher.mock.calls[2]![1].body)).toEqual({ text: '请追问', mode: 'DEFENSE' });
});

it('rejects invalid mode, stage, or out-of-range hintLevel', async () => {
  vi.stubGlobal('fetch', vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'OK', data: [{ ...baseTask, mode: 'INVENTED' }] })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'OK', data: [{ ...baseTask, hintLevel: 4 }] }))));

  await expect(history('owner', 'ex-1', '', new AbortController().signal)).rejects.toThrow('INVALID_RESPONSE');
  await expect(history('owner', 'ex-1', '', new AbortController().signal)).rejects.toThrow('INVALID_RESPONSE');
});
