import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';

test.skip(process.env.COPILOT_BROWSER_E2E !== 'disposable', 'Use the explicit paid --workspace --copilot-browser tracer');
// Credentials/cookies and network payloads must not enter automatic failure artifacts.
test.use({ trace: 'off', video: 'off', screenshot: 'off' });
const source = 'src/main/java/interview/CartTotal.java';
const marker = '// Copilot integration edit';
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid fixture response object');
  return value as Record<string, unknown>;
}
function text(value: unknown): string { if (typeof value !== 'string') throw new Error('Invalid fixture string'); return value; }
function array(value: unknown): unknown[] { if (!Array.isArray(value)) throw new Error('Invalid fixture list'); return value; }

test('real personal DeepSeek read, approved edit, Docker test and reconnect observation', async ({ page, context, baseURL }) => {
  test.setTimeout(180000);
  const fixtureUrl = process.env.BROWSER_FIXTURE_URL;
  const fixtureKey = process.env.BROWSER_FIXTURE_KEY;
  if (!fixtureUrl || !fixtureKey || !baseURL) throw new Error('Explicit paid disposable fixture is required');
  expect(Object.keys(process.env).filter((key) => key.toUpperCase() === 'DEEPSEEK')).toEqual([]);
  const unauthorized = await fetch(new URL('/api/test-fixtures/copilot-connection', baseURL), { method: 'POST',
    headers: { Origin: baseURL, 'Idempotency-Key': randomUUID() }, signal: AbortSignal.timeout(10000) });
  expect([401, 403]).toContain(unauthorized.status);
  // Only disposable session credentials reach this driver; the provider key remains in trusted Java.
  const seeded = await fetch(`${fixtureUrl}/identity`, { method: 'POST', headers: { Authorization: `Bearer ${fixtureKey}` }, signal: AbortSignal.timeout(10000) });
  if (seeded.status !== 201) throw new Error('Disposable USER provisioning failed');
  const identity = object(await seeded.json());
  const token = text(identity.token); const csrf = text(identity.csrf); const owner = text(identity.userId);
  await context.addCookies([{ name: 'codecraft_session', value: token, url: baseURL, httpOnly: true, sameSite: 'Lax' }]);
  async function api(method: string, path: string, body?: unknown, expected = 200): Promise<unknown> {
    const response = await fetch(new URL(path, baseURL), { method, signal: AbortSignal.timeout(15000),
      headers: { Cookie: `codecraft_session=${token}`, Origin: baseURL!, 'X-CSRF-TOKEN': csrf, 'X-Codecraft-Owner': owner,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const raw = await response.text();
    const envelope = object(JSON.parse(raw));
    if (response.status !== expected) throw new Error(`Fixture ${method} ${path}: status=${response.status}, code=${text(envelope.code)}`);
    return envelope.data;
  }
  let connectionId = ''; let connectionVersion = ''; let exerciseId = ''; let taskId = '';
  const failures: unknown[] = [];
  let current = page;
  try {
    const connection = object(await api('POST', '/api/test-fixtures/copilot-connection', {}, 201));
    connectionId = text(connection.id); connectionVersion = text(connection.version);
    const model = object(await api('POST', `/api/model-connections/${connectionId}/models`, { name: 'DeepSeek flash non-thinking', modelId: 'deepseek-flash',
      contextWindow: 100000, maxOutputTokens: 2048, thinkingMode: 'OFF' }, 201));
    const modelId = text(model.id);
    const problem = object(array(await api('GET', '/api/problems'))[0]);
    exerciseId = text(object(await api('POST', '/api/exercises', { problemVersionId: text(problem.problemVersionId) }, 202)).exerciseId);
    const base = `/api/exercises/${exerciseId}`;
    await expect.poll(async () => object(await api('GET', base)).state, { timeout: 20000 }).toBe('READY');
    const original = text(object(await api('GET', `${base}/files?path=${encodeURIComponent(source)}`)).content);
    await current.goto('/workspace');
    await expect(current.getByRole('heading', { name: '购物车金额计算', exact: true })).toBeVisible();
    await expect(current.getByText('第二阶段工程练习：', { exact: false })).toContainText('无面试官、挑战或 AI 报告');
    await current.getByRole('button', { name: '申请操作权', exact: true }).click();
    await current.getByLabel('连接', { exact: true }).selectOption(connectionId);
    await current.getByLabel('具体模型', { exact: true }).selectOption(modelId);
    await current.getByRole('button', { name: '显式使用此版本', exact: true }).click();
    await current.getByLabel('我确认检查可能产生费用', { exact: true }).check();
    await current.getByRole('button', { name: '开始检查', exact: true }).click();
    await expect(current.getByRole('button', { name: '两项结果均可复用', exact: true })).toBeVisible({ timeout: 60000 });
    await current.getByRole('button', { name: '确认开始计时', exact: true }).click();
    await expect(current.getByText('服务器截止时间：', { exact: false })).toBeVisible();
    await expect(current.getByText('剩余时间 ', { exact: false })).toBeVisible();
    await current.getByLabel('给 Copilot 的请求', { exact: true }).fill(
      `Read ${source} with view_file. Propose exactly one UPDATE that prepends the single line ${marker} and otherwise preserves every existing line. Wait for my approval. After confirmed application, run_tests exactly once on the applied revision with target base. Then reply briefly with the exact structured test result state, including FAILED if tests fail. Do not fix the intentional bug or propose another edit.`);
    await current.getByLabel('我确认模型请求可能收费', { exact: true }).check();
    await current.getByRole('button', { name: '保存草稿并发送', exact: true }).click();
    const proposalRegion = current.getByRole('region', { name: 'Copilot 修改提案', exact: true });
    await expect(proposalRegion.getByRole('heading', { name: /等待整批审批/ })).toBeVisible({ timeout: 60000 });
    const snapshot = object(await api('GET', `${base}/copilot/tasks/latest`));
    taskId = text(object(snapshot.task).id);
    const proposals = object(await api('GET', `/api/copilot-tasks/${taskId}/proposals?before=0`));
    const proposal = object(array(proposals.items)[0]); const proposalId = text(proposal.id);
    expect(proposal.taskId).toBe(taskId); expect(proposal.baseRevision).toBe('0'); expect(proposal.state).toBe('PENDING');
    const evidence = object(await api('GET', `/api/copilot-proposals/${proposalId}`));
    const files = array(evidence.files); expect(files).toHaveLength(1);
    const change = object(files[0]); expect(change.path).toBe(source); expect(change.before).toBe(original);
    const proposed = text(change.after); expect(proposed).toContain(marker); expect(proposed.replace(marker, '').trim()).toBe(original.trim());
    await expect(proposalRegion.locator('.monaco-diff-editor')).toBeVisible();
    await proposalRegion.getByText('查看完整纯文本', { exact: false }).click();
    await expect(proposalRegion.locator('pre').last()).toHaveText(proposed);

    // A disconnected browser must only rediscover the original task/proposal, not replay model/tool effects.
    await context.setOffline(true); await current.close(); await context.setOffline(false);
    current = await context.newPage(); await current.goto('/workspace');
    const recovered = current.getByRole('region', { name: 'Copilot 修改提案', exact: true });
    await expect(recovered).toContainText(proposalId, { timeout: 15000 });
    expect(text(object(object(await api('GET', `${base}/copilot/tasks/latest`)).task).id)).toBe(taskId);
    expect(array(object(await api('GET', `/api/copilot-tasks/${taskId}/proposals?before=0`)).items)).toHaveLength(1);
    await current.getByRole('button', { name: '接管操作权', exact: true }).click();
    await recovered.getByLabel('我已核对整批 Diff', { exact: false }).check();
    await recovered.getByRole('button', { name: '批准整批修改', exact: true }).click();
    await expect(recovered.getByText('已确认修订 1', { exact: false })).toBeVisible({ timeout: 20000 });
    await expect.poll(async () => object(await api('GET', `/api/copilot-tasks/${taskId}`)).state, { timeout: 60000 }).toBe('SUCCEEDED');
    expect(object(await api('GET', `${base}/files?path=${encodeURIComponent(source)}`)).content).toBe(proposed);
    expect(object(await api('GET', base)).revision).toBe('1');
    const bindings = array(await api('GET', `/api/copilot-tasks/${taskId}/tests`)); expect(bindings).toHaveLength(1);
    const bound = object(bindings[0]); expect(bound.taskId).toBe(taskId); expect(bound.revision).toBe('1'); expect(bound.resultState).toBe('FAILED');
    const result = object(await api('GET', `${base}/tasks/${text(bound.testTaskId)}`));
    expect(result.revision).toBe('1'); expect(result.state).toBe('FAILED'); expect(result.quiescent).toBe(true);
    expect(result.cleanup).toBe('SUCCEEDED'); expect(result.copyCleanup).toBe('SUCCEEDED'); expect(object(result.evidence).kind).toBe('SUREFIRE_XML');
    const messages = array(object(await api('GET', `${base}/copilot/messages?after=-1`)).messages);
    const toolMessages = messages.map(object).filter((item) => item.kind === 'TOOL');
    function toolResult(name: string): Record<string, unknown> {
      const message = toolMessages.find((item) => text(item.text).startsWith(`${name} [`));
      if (!message) throw new Error(`Missing confirmed ${name} tool message`);
      expect(message.taskId).toBe(taskId);
      const body = text(message.text);
      return object(JSON.parse(body.slice(body.indexOf('\n') + 1)));
    }
    const read = toolResult('view_file'); expect(read.code).toBe('OK'); expect(read.revision).toBe('0');
    expect(array(read.lines).map(text).join('\n')).toContain(original.split('\n')[0]);
    expect(toolResult('propose_changes').revision).toBe('1');
    expect(object(toolResult('run_tests').result).taskId).toBe(bound.testTaskId);
    const last = object(messages.at(-1)); expect(last.kind).toBe('ASSISTANT'); expect(text(last.text).toUpperCase()).toContain('FAILED');
    const linked = current.getByRole('region', { name: 'Copilot 关联测试', exact: true });
    await expect(linked).toContainText('版本 1，基础测试：测试失败');
    await linked.getByRole('button', { name: '查看原测试记录与日志', exact: true }).click();
    const shown = current.getByRole('article', { name: '当前测试结果', exact: true });
    await expect(shown.getByRole('heading')).toContainText('测试失败'); await expect(shown).toContainText('启动修订 1');
    await shown.getByText('查看日志文本', { exact: false }).click();
    await expect(shown.getByLabel('只读测试日志文本', { exact: true })).toContainText('Tests run:');
    const output = process.env.TRACER_SCREENSHOT_DIR;
    if (output) {
      await mkdir(output, { recursive: true });
      await recovered.screenshot({ path: join(output, 'approved-proposal.png') });
      await shown.screenshot({ path: join(output, 'structured-test-result.png') });
    }
    console.log(`Live browser evidence: model=deepseek-flash thinking=OFF, task=${taskId}, proposal=${proposalId}, test=${text(bound.testTaskId)}, revision=1, structured result=FAILED`);
  } catch (failure) { failures.push(failure); }
  // No automatic retry of unknown side effects, even during cleanup; tracer owns all remaining resources.
  if (exerciseId) {
    try {
      const latest = await api('GET', `/api/exercises/${exerciseId}/copilot/tasks/latest`);
      if (latest !== null && object(latest).task !== null) await api('POST', `/api/copilot-tasks/${text(object(object(latest).task).id)}/stop`, {});
    } catch (failure) { failures.push(failure); }
  }
  if (connectionId) {
    try { await api('DELETE', `/api/model-connections/${connectionId}`, { expectedVersion: connectionVersion }); }
    catch (failure) { failures.push(failure); }
  }
  if (failures.length) throw new AggregateError(failures, 'Live browser validation or cleanup failed');
});
