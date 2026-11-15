import { afterEach, expect, it, vi } from 'vitest';
import { start } from './api';
afterEach(() => vi.unstubAllGlobals());
import { parseSnapshot } from './skillApi';
it('submits only the explicitly selected IDs with the original task command', async () => {
  const exercise = '30000000-0000-0000-0000-000000000001';
  const ids = ['10000000-0000-0000-0000-000000000001'];
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ code: 'OK', data: { token: 'csrf' } })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'OK', data: { id: '40000000-0000-0000-0000-000000000001', exerciseId: exercise,
      state: 'QUEUED', reason: null, stopRequested: false, createdAt: '2026-01-01T00:00:00Z', completedAt: null } })));
  vi.stubGlobal('fetch', fetcher);
  const pending = start('owner', exercise, 'control', 'Inspect', 'request-key', new AbortController().signal, ids);
  ids.length = 0;
  await pending;
  expect(JSON.parse(fetcher.mock.calls[1]?.[1].body)).toEqual({ text: 'Inspect', feeAcknowledged: true, skillIds: ['10000000-0000-0000-0000-000000000001'] });
  expect(fetcher).toHaveBeenCalledTimes(2);
});
it('does not confuse available Skill names with explicitly loaded bodies', () => {
  const item = { skillId: '10000000-0000-0000-0000-000000000001', versionId: '20000000-0000-0000-0000-000000000001', name: 'guide', description: 'Guidance', explicitlySelected: false, explicitLoaded: false };
  expect(parseSnapshot(item).explicitLoaded).toBe(false);
  expect(() => parseSnapshot({ ...item, explicitLoaded: true })).toThrow();
  expect(() => parseSnapshot({ ...item, versionId: 'bad' })).toThrow();
});
