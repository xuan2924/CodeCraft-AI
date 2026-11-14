import { afterEach, expect, it, vi } from 'vitest';
import * as api from './api';

const connectionId = '10000000-0000-0000-0000-000000000001';
const modelId = '10000000-0000-0000-0000-000000000002';
const checkId = '10000000-0000-0000-0000-000000000003';
const connection = { id: connectionId, name: 'Fixture', baseUrl: 'https://example.test', protocol: 'OPENAI_CHAT', preset: 'CUSTOM',
  version: '2', configurationVersion: '1', keyConfigured: true, deleted: false };
const model = { id: modelId, connectionId, name: 'Fixture', modelId: 'fixture-model', contextWindow: 10000, maxOutputTokens: 4096,
  thinkingMode: 'OFF', reasoningEffort: null, thinkingBudgetTokens: null, temperature: null, version: '1', configurationVersion: '1', windowSource: 'USER_PROVIDED', deleted: false };
const check = { id: checkId, modelConfigurationId: modelId, connectionConfigurationVersion: '1', modelConfigurationVersion: '1', status: 'SUCCEEDED', startedAt: '2026-01-01T00:00:00Z' };
const item = { status: 'SUCCEEDED', completedAt: '2026-01-01T00:00:00Z', validUntil: '2026-01-01T00:30:00Z', reused: true, valid: true, errorCode: null };
const signal = () => new AbortController().signal;
const response = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }));
afterEach(() => vi.unstubAllGlobals());

it('keeps only non-secret response metadata instead of copying arbitrary server fields into query caches', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ ...connection, apiKey: 'fixture-secret' })));
  expect(await api.getConnection(connectionId, signal())).toEqual(connection);
});
it('updates through shared PUT/CSRF without silently testing the saved credential', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(response({ token: 'csrf' })).mockResolvedValueOnce(response(connection));
  vi.stubGlobal('fetch', fetch);
  const input: api.ConnectionInput = { name: 'Fixture', baseUrl: 'https://example.test', protocol: 'OPENAI_CHAT', preset: 'CUSTOM', apiKey: 'fixture-secret', expectedVersion: '1' };
  await api.saveConnection(connectionId, input, 'original-command', signal());
  expect(fetch).toHaveBeenCalledTimes(2);
  expect(fetch.mock.calls[1]?.[1]).toMatchObject({ method: 'PUT', body: JSON.stringify(input), headers: { 'Idempotency-Key': 'original-command', 'X-CSRF-TOKEN': 'csrf' } });
});
it('preserves the deletion version in the JSON body', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(response({ token: 'csrf' })).mockResolvedValueOnce(response({ ...model, deleted: true }));
  vi.stubGlobal('fetch', fetch);
  await api.deleteModel(modelId, '1', 'original-delete', signal());
  expect(fetch.mock.calls[1]?.[1]).toMatchObject({ method: 'DELETE', body: '{"expectedVersion":"1"}' });
});
it('rejects another connections model rather than showing it under the selected credential', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ models: [{ ...model, connectionId: checkId }], nextCursor: null })));
  await expect(api.models(connectionId, null, signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
it.each(['MODEL_CHECK_NOT_FOUND', 'MODEL_CONFIGURATION_NOT_FOUND', 'REQUEST_FAILED'])('only treats the exact no-check status as empty: %s', async (code) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code }), { status: 404 })));
  const result = api.latestCheck(modelId, signal());
  if (code === 'MODEL_CHECK_NOT_FOUND') await expect(result).resolves.toBeNull();
  else await expect(result).rejects.toMatchObject({ status: 404, code });
});
it('does not retry a possibly accepted check after a network failure', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(response({ token: 'csrf' })).mockRejectedValueOnce(new TypeError('fixture offline'));
  vi.stubGlobal('fetch', fetch);
  await expect(api.startCheck(modelId, 'original-check', signal())).rejects.toBeInstanceOf(TypeError);
  expect(fetch).toHaveBeenCalledTimes(2);
  expect(fetch.mock.calls[1]?.[1]).toMatchObject({ body: '{"feeAcknowledged":true}' });
});
it('keeps basic and tool timestamps separate and rejects a mismatched check identity', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(response({ check, reason: null, currentConfiguration: true, basic: item, tool: { ...item, reused: false } }))
    .mockResolvedValueOnce(response({ check: { ...check, id: modelId }, reason: null, currentConfiguration: true, basic: item, tool: item }));
  vi.stubGlobal('fetch', fetch);
  expect((await api.getCheck(checkId, signal())).basic.reused).toBe(true);
  await expect(api.getCheck(checkId, signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
it('rejects current-valid flags on a stale configuration', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ check, reason: null, currentConfiguration: false, basic: item, tool: item })));
  await expect(api.getCheck(checkId, signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
it('rejects a model output limit that consumes its entire declared window', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ models: [{ ...model, maxOutputTokens: 10000 }], nextCursor: null })));
  await expect(api.models(connectionId, null, signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
it.each([
  { ...item, status: 'PENDING', valid: true },
  { ...item, completedAt: null, validUntil: null },
])('rejects internally inconsistent success metadata', async (basic) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ check, reason: null, currentConfiguration: true, basic, tool: item })));
  await expect(api.getCheck(checkId, signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
