import { beforeEach, expect, it, vi } from 'vitest';
import { request } from '../api/request';
import { tests, type TestRun } from './api';
vi.mock('../api/request', async (original) => ({ ...await original<typeof import('../api/request')>(), request: vi.fn() }));
const value: TestRun = { toolId: '10000000-0000-0000-0000-000000000001', taskId: '10000000-0000-0000-0000-000000000002',
  testTaskId: '10000000-0000-0000-0000-000000000003', revision: '1', target: 'base', state: 'WAITING', resultState: null,
  createdAt: '2026-01-01T00:00:00Z', completedAt: null };
const signal = new globalThis.AbortController().signal;
beforeEach(() => vi.resetAllMocks());
it('reads bounded exact-parent metadata without requesting full logs', async () => {
  vi.mocked(request).mockResolvedValue([value]);
  expect(await tests('owner', value.taskId, signal)).toEqual([value]);
  expect(request).toHaveBeenCalledWith(`/api/copilot-tasks/${value.taskId}/tests`, signal, expect.objectContaining({ headers: expect.any(Object) }));
});
it('rejects duplicate, oversized, cross-parent and contradictory result records', async () => {
  for (const body of [[value, value], Array.from({ length: 21 }, () => value), [{ ...value, taskId: value.toolId }],
    [{ ...value, resultState: 'PASSED' }], [{ ...value, state: 'RESOLVED' }], [{ ...value, revision: 1 }],
    [{ ...value, target: 'shell' }], [{ ...value, state: 'RESOLVED', resultState: 'UNKNOWN', completedAt: value.createdAt }]]) {
    vi.mocked(request).mockResolvedValue(body);
    await expect(tests('owner', value.taskId, signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
});
