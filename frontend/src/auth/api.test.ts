import { afterEach, describe, expect, it, vi } from 'vitest';
import { changePassword, logout, resetPassword } from './api';

afterEach(() => vi.unstubAllGlobals());
describe('pending identity revocation', () => {
  it.each(['logout', 'change', 'reset'])('does not report %s as completed or automatically replay it', async (action) => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'OK', data: { token: 'csrf' } })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'OK', data: {
        operationId: 'revocation-operation', state: 'PENDING', queryUrl: '/api/operations/revocation-operation',
      } }), { status: 202 }));
    vi.stubGlobal('fetch', fetch);
    const signal = new AbortController().signal;
    const result = action === 'logout' ? logout(signal) : action === 'change'
      ? changePassword('old-password', 'new-password', 'original-key', signal)
      : resetPassword('target-user', 'original-key', signal);
    await expect(result).rejects.toMatchObject({ status: 202, code: 'REVOCATION_PENDING' });
    expect(fetch).toHaveBeenCalledTimes(2);
  });
});
