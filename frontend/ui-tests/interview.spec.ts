import { expect, test } from '@playwright/test';

for (const width of [1440, 390]) {
  test(`interviewer and published challenge remain readable and keyboard accessible at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    let submitted = 0;
    await page.route('**/api/**', async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (!path.startsWith('/api/')) return route.continue();
      if (path === '/api/auth/csrf') return route.fulfill({ json: { code: 'OK', data: { token: 'fixture-token', headerName: 'X-CSRF-TOKEN' } } });
      if (path.endsWith('/interviewer/tasks')) {
        if (route.request().method() === 'GET') return route.fulfill({ json: { code: 'OK', data: [] } });
        submitted++;
        return route.fulfill({ json: { code: 'OK', data: { id: 'fixture-task', exerciseId: 'fixture-exercise', question: '折扣如何取整？', answer: null,
          state: 'QUEUED', reason: null, platformVersionId: 'fixture-version', phase: 'READY', revision: '0', createdAt: '2026-01-01T00:00:00Z', completedAt: null } } });
      }
      return route.abort();
    });
    await page.goto('/ui-tests/interview-fixture.html');
    await expect(page.getByText('尚无提问。可以先核对需求，也可以直接开始实现。')).toBeVisible();
    await page.getByLabel('向面试官提问', { exact: true }).fill('折扣如何取整？');
    await page.getByRole('button', { name: '发送给面试官' }).focus();
    await page.keyboard.press('Enter');
    await expect.poll(() => submitted).toBe(1);
    await expect(page.getByLabel('向面试官提问', { exact: true })).toHaveValue('');
    await expect(page.getByText('进入时的基础验收修订：0。最终验收包含基础回归与挑战。')).toBeVisible();
    const summary = page.locator('summary');
    await summary.focus(); await page.keyboard.press('Enter');
    await expect(page.getByText('class Test { /* read-only acceptance */ }')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: `test-results/ui/interview-${width}.png`, fullPage: true });
  });
}
