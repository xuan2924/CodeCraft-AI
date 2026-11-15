import { afterEach, expect, it, vi } from 'vitest';
import { projectInstructions } from './projectInstructionsApi';
const value = { exerciseId: 'exercise', revision: '7', scopeTruncated: false, sources: [{ path: 'AGENTS.md', scope: '.', status: 'LOADED', content: 'rules' }] };
function response(data: unknown) { vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => new Response(JSON.stringify({ code: 'OK', data })))); }
afterEach(() => vi.unstubAllGlobals());
it('accepts retained snapshots and rejects foreign identity, invalid paths, missing text and oversized guidance', async () => {
  response(value); await expect(projectInstructions('owner', 'exercise', 'task', new AbortController().signal)).resolves.toEqual(value);
  for (const malformed of [{ ...value, exerciseId: 'other' }, { ...value, sources: [{ ...value.sources[0], path: '../AGENTS.md' }] },
    { ...value, sources: [{ ...value.sources[0], content: null }] }, { ...value, sources: [{ ...value.sources[0], content: '大'.repeat(3000) }] },
    { ...value, sources: [{ ...value.sources[0], status: 'READ_FAILED' }] }]) {
    response(malformed); await expect(projectInstructions('owner', 'exercise', 'task', new AbortController().signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  }
});
