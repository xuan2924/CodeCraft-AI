import { beforeEach, expect, it, vi } from 'vitest';
import { post, request } from '../api/request';
import * as api from './commandApi';

vi.mock('../api/request', async original => ({ ...await original<typeof import('../api/request')>(), request: vi.fn(), post: vi.fn() }));
const id = '10000000-0000-0000-0000-000000000001';
const task = '20000000-0000-0000-0000-000000000002';
const exercise = '30000000-0000-0000-0000-000000000003';
const workspace = '40000000-0000-0000-0000-000000000004';
const child = '50000000-0000-0000-0000-000000000005';
const time = '2026-01-01T00:00:00Z';
const pending: api.CommandProposal = { id, sequence: '1', taskId: task, exerciseId: exercise, workspaceInstanceId: workspace, baseRevision: '1',
  command: 'exit 7', directory: '.', mode: 'ASK', modeVersion: '0', state: 'PENDING', commandTaskId: null, decision: null, reason: null,
  result: null, createdAt: time, decidedAt: null, completedAt: null };
beforeEach(() => vi.resetAllMocks());
const signal = () => new AbortController().signal;
it('rejects cross-parent, cross-exercise and structurally impossible approval records', async () => {
  for (const changes of [{ taskId: id }, { exerciseId: id }, { state: 'WAITING' }, { state: 'RESOLVED' }, { mode: 'UNSAFE' }, { sequence: '1e2' }]) {
    vi.mocked(request).mockResolvedValue({ ...pending, ...changes });
    await expect(api.detail('owner', exercise, task, id, signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
});
it('checks pagination order and explicitly binds reads to the owner and parent', async () => {
  vi.mocked(request).mockResolvedValue({ commands: [pending], nextBefore: null });
  expect((await api.page('owner', exercise, task, null, signal())).commands).toEqual([pending]);
  expect(vi.mocked(request).mock.calls[0]![0]).toBe(`/api/copilot-tasks/${task}/commands`);
  vi.mocked(request).mockResolvedValue({ commands: [pending, pending], nextBefore: '1' });
  await expect(api.page('owner', exercise, task, null, signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
it('preserves a structured rejection and sends only the explicit decision with its original key', async () => {
  const result: api.CommandProposal = { ...pending, state: 'RESOLVED', decision: 'REJECT', reason: 'not this command', decidedAt: time, completedAt: time,
    result: { code: 'COMMAND_REJECTED', reason: 'not this command', result: null, logTruncated: false, omittedLogBytes: '0' } };
  vi.mocked(post).mockResolvedValue(result);
  const input: api.Decision = { decision: 'REJECT', reason: 'not this command' };
  expect(await api.decide('owner', exercise, task, id, input, 'original-key', 'control', signal())).toEqual(result);
  expect(vi.mocked(post).mock.calls[0]!.slice(3)).toEqual(['original-key', 'control', 'owner']);
  expect(api.latest(result, pending)).toBe(result);
});
it('never accepts a nested execution from a different command binding', async () => {
  vi.mocked(request).mockResolvedValue({ ...pending, state: 'RESOLVED', commandTaskId: child, decision: 'APPROVE', decidedAt: time, completedAt: time,
    result: { code: 'COMMAND_RESULT', reason: '', logTruncated: false, omittedLogBytes: '0', result: {
      id, exerciseId: exercise, workspaceInstanceId: workspace, executionInstanceId: child, revision: '1', stateVersion: '3', command: 'exit 7', directory: '.',
      state: 'EXITED', reason: 'command_exit', quiescent: true, stopRequested: false, changed: false, synchronization: 'UNCHANGED', synchronizationReason: '',
      synchronizedRevision: null, exitCode: 7, logs: [], droppedBytes: '0', logsIncomplete: false, cleanup: 'SUCCEEDED', copyCleanup: 'SUCCEEDED', createdAt: time, completedAt: time,
    } } });
  await expect(api.detail('owner', exercise, task, id, signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
