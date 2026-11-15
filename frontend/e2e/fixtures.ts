import { test as base, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';

export const test = base.extend<{ workspace: { exerciseId: string; workspaceId: string } }>({
  workspace: async ({ context, request, baseURL }, use) => {
    const url = process.env.BROWSER_FIXTURE_URL;
    const key = process.env.BROWSER_FIXTURE_KEY;
    if (!url || !key || !baseURL) throw new Error('Use test-tracer.mjs --workspace to provision isolated fixtures');
    const seeded = await request.post(`${url}/identity`, { headers: { Authorization: `Bearer ${key}` } });
    expect(seeded.status()).toBe(201);
    const identity: { userId: string; token: string; csrf: string } = await seeded.json();
    await context.addCookies([{ name: 'codecraft_session', value: identity.token, url: baseURL, httpOnly: true, sameSite: 'Lax' }]);
    const headers = { Origin: baseURL, 'X-CSRF-TOKEN': identity.csrf, 'X-Codecraft-Owner': identity.userId, 'Idempotency-Key': randomUUID() };
    const problems = await context.request.get('/api/problems');
    expect(problems.status()).toBe(200);
    const versions: { data: { problemVersionId: string }[] } = await problems.json();
    const created = await context.request.post('/api/exercises', { headers, data: { problemVersionId: versions.data[0]?.problemVersionId } });
    expect(created.status()).toBe(202);
    const result: { data: { exerciseId: string } } = await created.json();
    const exerciseId = result.data.exerciseId;
    expect(exerciseId).toMatch(/^[0-9a-f-]{36}$/);
    let workspaceId = '';
    await expect.poll(async () => {
      const response = await context.request.get(`/api/exercises/${exerciseId}`);
      expect(response.status()).toBe(200);
      const snapshot: { data: { workspaceInstanceId: string; state: string } } = await response.json();
      workspaceId = snapshot.data.workspaceInstanceId;
      return snapshot.data.state;
    }, { timeout: 15000, message: `workspace preparation ${exerciseId}` }).toBe('READY');
    expect(workspaceId).toMatch(/^[0-9a-f-]{36}$/);
    const control = await context.request.post(`/api/exercises/${exerciseId}/control/acquire`, { headers: { ...headers, 'Idempotency-Key': randomUUID() }, data: {} });
    expect(control.status()).toBe(200);
    const credential: string = (await control.json()).data.credential;
    // Use the real model/check/selection APIs. The disposable tracer installs a
    // check-only driver for this exact synthetic target; never use a paid provider here.
    const connectionResponse = await context.request.post('/api/model-connections', {
      headers: { ...headers, 'Idempotency-Key': randomUUID() },
      data: { name: 'Workspace browser prerequisite', baseUrl: 'https://workspace-browser.invalid', protocol: 'OPENAI_CHAT', preset: 'CUSTOM', apiKey: 'workspace-browser-not-real' },
    });
    expect(connectionResponse.status()).toBe(201);
    const connection: { data: { id: string; configurationVersion: string } } = await connectionResponse.json();
    const modelResponse = await context.request.post(`/api/model-connections/${connection.data.id}/models`, {
      headers: { ...headers, 'Idempotency-Key': randomUUID() },
      data: { name: 'Workspace browser fixture', modelId: 'workspace-browser-fixture', contextWindow: 100000, maxOutputTokens: 1024, thinkingMode: 'OFF' },
    });
    expect(modelResponse.status()).toBe(201);
    const model: { data: { id: string; configurationVersion: string } } = await modelResponse.json();
    const checkResponse = await context.request.post(`/api/model-configurations/${model.data.id}/checks`, {
      headers: { ...headers, 'Idempotency-Key': randomUUID() }, data: { feeAcknowledged: true },
    });
    expect(checkResponse.status()).toBe(202);
    const check: { data: { id: string } } = await checkResponse.json();
    await expect.poll(async () => {
      const response = await context.request.get(`/api/model-checks/${check.data.id}`, { headers: { 'X-Codecraft-Owner': identity.userId } });
      expect(response.status()).toBe(200);
      const status: { data: { basic: { valid: boolean }; tool: { valid: boolean } } } = await response.json();
      return [status.data.basic.valid, status.data.tool.valid];
    }, { timeout: 15000, message: 'Synthetic workspace model prerequisites' }).toEqual([true, true]);
    const selected = await context.request.post(`/api/exercises/${exerciseId}/model`, {
      headers: { ...headers, 'Idempotency-Key': randomUUID(), 'X-Operation-Credential': credential },
      data: { connectionId: connection.data.id, modelConfigurationId: model.data.id,
        connectionConfigurationVersion: connection.data.configurationVersion, modelConfigurationVersion: model.data.configurationVersion },
    });
    expect(selected.status()).toBe(200);
    const started = await context.request.post(`/api/exercises/${exerciseId}/start`, {
      headers: { ...headers, 'Idempotency-Key': randomUUID(), 'X-Operation-Credential': credential }, data: {},
    });
    expect(started.status()).toBe(200);
    await use({ exerciseId, workspaceId });
    // Tracer owns the ephemeral database/volume, including active exercises.
    // Do not forge an ended lifecycle merely to delete test data.
  },
});
export { expect };
