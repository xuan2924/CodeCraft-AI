import { expect, test } from '@playwright/test';
const exercise = '00000000-0000-0000-0000-000000000001';
for (const width of [1600, 390]) {
  test(`actual workbench components retain focus-mode controls at ${width}px (API fixture)`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1100 });
    await page.route('**/api/**', async route => {
      const path = new URL(route.request().url()).pathname;
      if (!path.startsWith('/api/')) return route.continue();
      let data: unknown;
      if (path === '/api/auth/session') data = { id: 'owner', username: 'invited', role: 'USER', passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' };
      else if (path === '/api/problems') data = [{ problemVersionId: 'problem', title: '购物车金额计算', requirements: '以整数分表示金额。保持原有 API，处理零值与边界条件。'.repeat(8), version: '1.1.0', durationSeconds: 2700, editableRoots: ['src/main/java'], acceptanceFiles: {}, executionAvailable: true }];
      else if (path === '/api/exercises/current' || path === `/api/exercises/${exercise}`) data = { exerciseId: exercise, problemVersionId: 'problem', workspaceInstanceId: 'workspace', state: 'ACTIVE', revision: '0', paths: ['src/main/java/interview/CartTotal.java'], deadlineAt: '2030-01-01T00:00:00Z', preparationFailureReason: null, cleanupState: null };
      else if (path === '/api/exercises') data = [];
      else if (path.endsWith('/files')) data = { workspaceInstanceId: 'workspace', revision: '0', path: 'src/main/java/interview/CartTotal.java', content: 'package interview;\npublic class CartTotal {\n    // 显式保存，不自动提交草稿\n}\n' };
      else if (path.endsWith('/control')) data = { exerciseId: exercise, operationId: null, state: 'SUCCEEDED', generation: '1', held: false, credential: null };
      else if (path.endsWith('/challenge')) data = { available: false, entered: false, eligible: false, reason: 'NONE', enteredRevision: null, baseTestId: null, publishedChallenge: null };
      else if (path.endsWith('/interviewer/tasks')) data = [];
      else if (path.endsWith('/copilot/conversations')) data = [{ id: exercise, exerciseId: exercise, initial: true, createdAt: '2026-01-01T00:00:00Z' }];
      else if (path.endsWith('/copilot/messages')) data = { messages: [], nextCursor: null };
      else if (path.endsWith('/copilot/tasks/current')) data = null;
      else if (path.endsWith('/storage')) data = { limitBytes: 536870912, recoveryBytes: 268435456, goBytes: 0, databaseBytes: 0, reservedBytes: 0, measuredAt: '2026-01-01T00:00:00Z' };
      else return route.fulfill({ status: 404, json: { code: 'RESOURCE_NOT_FOUND' } });
      return route.fulfill({ json: { code: 'OK', data } });
    });
    await page.goto('/ui-tests/login-fixture.html?page=workspace');
    await expect(page.getByRole('heading', { name: '实现工作台', exact: true })).toBeVisible();
    await page.getByTestId('workspace-file').click();
    await expect(page.getByRole('group', { name: '文件编辑', exact: true })).toBeVisible();
    await expect(page.locator('.monaco-editor').first()).toBeVisible();
    await expect(page.locator('.monaco-editor').first()).toHaveCSS('background-color', 'rgb(11, 13, 16)');
    await expect(page.getByRole('region', { name: 'AI 协作面板', exact: true })).toBeVisible();
    const focus = page.getByRole('button', { name: '专注实现，收起题目' });
    await focus.focus(); await page.keyboard.press('Enter');
    await expect(page.getByRole('region', { name: '题目与文件面板', exact: true })).toBeHidden();
    await expect(page.getByRole('region', { name: 'AI 协作面板', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '退出专注，显示全部面板' }).click();
    await expect(page.getByRole('region', { name: '题目与文件面板', exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const height = await page.locator('.workbench-viewport').evaluate(element => element.getBoundingClientRect().height);
    expect(height).toBeLessThanOrEqual(width > 1100 ? 1100 : 1800);
    await page.locator('.workbench').screenshot({ path: `test-results/ui/workbench-${width}.png` });
  });
}
