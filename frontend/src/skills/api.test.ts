import { afterEach, describe, expect, it, vi } from 'vitest';
import { importSkill, parsePackage, parseSkill } from './api';
afterEach(() => vi.unstubAllGlobals());

describe('Skill API boundary', () => {
  it('forwards imported JSON unchanged so the server sees duplicate fields', async () => {
    const raw = '{"formatVersion":1,"formatVersion":1,"files":[]}';
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ code: 'OK', data: { token: 'csrf' } })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'SKILL_INVALID_PACKAGE' }), { status: 400 }));
    vi.stubGlobal('fetch', fetcher);
    await expect(importSkill('owner', raw, 'key', new AbortController().signal)).rejects.toThrow('SKILL_INVALID_PACKAGE');
    expect(fetcher.mock.calls[1]?.[1].body).toBe(raw);
    expect(fetcher.mock.calls[1]?.[1].headers['X-Codecraft-Owner']).toBe('owner');
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it('preserves raw main document and rejects invalid package shapes', () => {
    const main = '---\r\nname: review\r\ndescription: d\r\n---\r\nBody';
    expect(parsePackage({ formatVersion: 1, files: [{ path: 'SKILL.md', content: main }] }).files[0]?.content).toBe(main);
    expect(() => parsePackage({ formatVersion: 2, files: [] })).toThrow();
    expect(() => parseSkill({ id: 'foreign', enabled: true })).toThrow();
  });
});
