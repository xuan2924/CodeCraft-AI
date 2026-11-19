import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { createMemoryHistory, createRouter } from 'vue-router';
import AdminOperationsPage from './AdminOperationsPage.vue';
import * as opsApi from './adminOperationsApi';
import * as telemetryApi from '../copilot/telemetryApi';

vi.mock('./adminOperationsApi', async (original) => ({
  ...await original<typeof import('./adminOperationsApi')>(),
  listProblems: vi.fn(),
  validateProblem: vi.fn(),
  publishProblem: vi.fn(),
  setProblemAvailability: vi.fn(),
}));
vi.mock('../copilot/telemetryApi', async (original) => ({
  ...await original<typeof import('../copilot/telemetryApi')>(),
  adminCopilotMetrics: vi.fn(),
}));

afterEach(() => {
  vi.unstubAllGlobals();
  vi.resetAllMocks();
});

async function render(role: 'USER' | 'ADMIN') {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
    code: 'OK',
    data: { id: 'admin-1', username: 'admin', role, passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' },
  }))));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: AdminOperationsPage },
      { path: '/account', name: 'account', component: { template: '<div />' } },
    ],
  });
  await router.push('/');
  const page = mount(AdminOperationsPage, {
    global: { plugins: [router, [VueQueryPlugin, { queryClient: client }]] },
  });
  await flushPromises();
  return { page, client };
}

it('blocks ordinary users from querying admin telemetry or problem endpoints', async () => {
  const { page, client } = await render('USER');
  expect(page.text()).toContain('需要管理员身份');
  expect(telemetryApi.adminCopilotMetrics).not.toHaveBeenCalled();
  expect(opsApi.listProblems).not.toHaveBeenCalled();
  page.unmount();
  client.clear();
});

it('displays sanitized copilot metrics, problem execution support, and validates/publishes artifacts', async () => {
  vi.mocked(telemetryApi.adminCopilotMetrics).mockResolvedValue({
    taskCount: 3,
    rounds: 9,
    tools: 14,
    modelElapsedMs: 3200,
    toolElapsedMs: 900,
    readWindows: 4,
    readSlotsUsed: 12,
    exploreChildren: 3,
    microCompactedRounds: 2,
    microSavedBytes: 1600,
    recoveredRevisionConflicts: 1,
    steerInterruptions: 1,
    chatInputTokens: 12000,
    chatOutputTokens: 2500,
    chatCacheReadTokens: null,
    chatCacheWriteTokens: null,
    readWindowUtilization: 0.75,
  });
  vi.mocked(opsApi.listProblems).mockResolvedValue([
    {
      id: 'p-1',
      problemId: 'sliding-window-rate-limiter',
      version: '1.0.0',
      language: 'JAVA_MAVEN',
      executionSupported: true,
      packageSha256: 'a'.repeat(64),
      available: true,
    },
    {
      id: 'p-2',
      problemId: 'python-demo',
      version: '1.0.0',
      language: 'PYTHON_PYTEST',
      executionSupported: false,
      packageSha256: 'b'.repeat(64),
      available: false,
    },
  ]);
  vi.mocked(opsApi.validateProblem).mockResolvedValue({
    valid: false,
    problemId: 'python-demo',
    version: '1.0.0',
    language: 'PYTHON_PYTEST',
    executionSupported: false,
    packageSha256: null,
    hasChallenge: false,
    errors: ['Missing static receipt'],
  });
  vi.mocked(opsApi.publishProblem).mockResolvedValue({
    id: 'p-3',
    problemId: 'rate-limiter',
    version: '1.0.1',
    packageSha256: 'c'.repeat(64),
  });

  const { page, client } = await render('ADMIN');
  const text = page.text();
  expect(text).toContain('3 / 9 / 14');
  expect(text).toContain('4（75%）');
  expect(text).toContain('sliding-window-rate-limiter');
  expect(text).toContain('支持沙箱执行');
  expect(text).toContain('仅静态契约');
  expect(text).toContain('已下架');

  await page.get('#problem-artifact-input').setValue('{"schemaVersion":1}');
  await page.findAll('button').find(b => b.text() === '仅校验契约')!.trigger('click');
  await flushPromises();
  expect(page.text()).toContain('契约校验未通过');
  expect(page.text()).toContain('Missing static receipt');

  await page.findAll('button').find(b => b.text() === '幂等发布到目录')!.trigger('click');
  await flushPromises();
  expect(opsApi.publishProblem).toHaveBeenCalled();
  expect(page.text()).toContain('已发布题包：rate-limiter v1.0.1');

  const { ApiError } = await import('../api/request');
  vi.mocked(opsApi.publishProblem).mockRejectedValueOnce(new ApiError(413, 'REQUEST_TOO_LARGE'));
  await page.get('#problem-artifact-input').setValue('{"oversized":true}');
  await page.findAll('button').find(b => b.text() === '幂等发布到目录')!.trigger('click');
  await flushPromises();
  expect(page.text()).not.toContain('原幂等键重试');
  expect(page.findAll('button').find(b => b.text() === '幂等发布到目录')!.attributes('disabled')).toBeUndefined();
  page.unmount();
  client.clear();
});

it('invalidates previous validation result as soon as artifact text is edited', async () => {
  vi.mocked(telemetryApi.adminCopilotMetrics).mockResolvedValue({
    taskCount: 0, rounds: 0, tools: 0, modelElapsedMs: 0, toolElapsedMs: 0,
    readWindows: 0, readSlotsUsed: 0, exploreChildren: 0, microCompactedRounds: 0,
    microSavedBytes: 0, recoveredRevisionConflicts: 0, steerInterruptions: 0,
    chatInputTokens: null, chatOutputTokens: null, chatCacheReadTokens: null, chatCacheWriteTokens: null,
    readWindowUtilization: null,
  });
  vi.mocked(opsApi.listProblems).mockResolvedValue([]);
  vi.mocked(opsApi.validateProblem).mockResolvedValue({
    valid: true,
    problemId: 'order-settlement-engine',
    version: '1.0.0',
    language: 'JAVA_MAVEN',
    executionSupported: true,
    packageSha256: 'a'.repeat(64),
    hasChallenge: true,
    errors: [],
  });

  const { page, client } = await render('ADMIN');
  await page.get('#problem-artifact-input').setValue('{"schemaVersion":1,"valid":true}');
  await page.findAll('button').find(b => b.text() === '仅校验契约')!.trigger('click');
  await flushPromises();
  expect(page.text()).toContain('契约校验通过');

  await page.get('#problem-artifact-input').setValue('{"schemaVersion":1,"valid":false}');
  await flushPromises();
  expect(page.text()).not.toContain('契约校验通过');
  expect(page.text()).toContain('上次校验结果已失效');
  page.unmount();
  client.clear();
});
