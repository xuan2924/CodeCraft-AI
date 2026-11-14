import { beforeEach, expect, it, vi } from 'vitest';
import { request, post } from '../api/request';
import { decide, proposalDetail, proposalStatus, proposals, type Proposal } from './api';
vi.mock('../api/request', async (original) => ({ ...await original<typeof import('../api/request')>(), request: vi.fn(), post: vi.fn() }));
const value: Proposal = { id: '10000000-0000-0000-0000-000000000001', taskId: '10000000-0000-0000-0000-000000000002',
  sequence: '1', state: 'PENDING', mode: 'ASK', modeVersion: '0', baseRevision: '1', fileCount: 1, resultRevision: null, changed: null, decisionType: null,
  decisionReason: null, failureCode: null, createdAt: '2026-01-01T00:00:00Z', decidedAt: null, completedAt: null };
const signal = new globalThis.AbortController().signal;
beforeEach(() => vi.resetAllMocks());
it('rejects a false success without a revision receipt and mismatched task identities', async () => {
  vi.mocked(request).mockResolvedValue({ ...value, state: 'APPLIED' });
  await expect(proposalStatus('owner', value.taskId, value.id, signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  vi.mocked(post).mockResolvedValue({ ...value, taskId: value.id });
  await expect(decide('owner', value.taskId, value.id, 'credential', 'APPROVE', null, 'original', signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
it('checks exact file counts, unique paths and create/update/delete evidence shapes', async () => {
  vi.mocked(request).mockResolvedValue({ proposal: value, files: [{ path: 'src/main/java/Main.java', kind: 'UPDATE', before: null, after: 'class Main {}' }] });
  await expect(proposalDetail('owner', value.taskId, value.id, signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  vi.mocked(request).mockResolvedValue({ proposal: value, files: [{ path: 'src/main/java/Main.java', kind: 'CREATE', before: null, after: 'class Main {}' }] });
  expect((await proposalDetail('owner', value.taskId, value.id, signal)).files[0]?.kind).toBe('CREATE');
});
it('rejects unordered pages and mismatched history cursors', async () => {
  vi.mocked(request).mockResolvedValue({ items: [value, value], nextCursor: '1' });
  await expect(proposals('owner', value.taskId, '0', signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  vi.mocked(request).mockResolvedValue({ items: [value], nextCursor: '2' });
  await expect(proposals('owner', value.taskId, '0', signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
