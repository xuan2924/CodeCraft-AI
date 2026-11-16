import { expect, test } from '@playwright/test';
for (const width of [1440, 390]) {
  test(`platform administration form is bounded, keyboard usable and distinct from publication at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    await page.route('**/api/**', async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (!path.startsWith('/api/')) return route.continue();
      if (path === '/api/auth/session') return route.fulfill({ json: { code: 'OK', data: { id: 'admin', username: 'admin', role: 'ADMIN', passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' } } });
      if (path === '/api/admin/platform-agents') return route.fulfill({ json: { code: 'OK', data: { versions: [], roles: [{ role: 'INTERVIEWER', currentVersion: null, revision: '0' }, { role: 'EVALUATOR', currentVersion: null, revision: '0' }] } } });
      return route.abort();
    });
    await page.goto('/ui-tests/login-fixture.html?page=platform');
    await expect(page.getByText('暂无平台版本，请保存新草稿。')).toBeVisible();
    await page.getByLabel('平台角色', { exact: true }).focus();
    await page.keyboard.press('ArrowDown');
    await expect(page.getByLabel('平台角色', { exact: true })).toHaveValue('EVALUATOR');
    await page.getByLabel('配置名称', { exact: true }).fill('很长的平台角色配置名称'.repeat(8));
    await page.getByLabel('角色指令', { exact: true }).fill('仅解释已发布需求，不伪造测试通过，也不改变 Java 阶段。');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await expect(page.getByRole('button', { name: '保存新草稿', exact: true })).toBeEnabled();
    await expect(page.getByText('保存不代表检查或发布', { exact: false })).toBeVisible();
    await page.screenshot({ path: `test-results/ui/platform-${width}.png`, fullPage: true });
  });
}
