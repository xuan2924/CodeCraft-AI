import { expect, test } from '@playwright/test';
for (const width of [1440, 390]) {
  test(`report review and evidence are accessible at ${width}px (API fixture)`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    let posted = 0;
    const waiting = { exerciseId: 'fixture-exercise', reportId: null, generation: 0, state: 'WAITING_REVIEW', finalRevision: '0', verification: 'UNAVAILABLE', finalTestId: null, reviewSkipped: null, platformVersionId: null, attempts: 0, reason: null, evaluation: null, evidence: [] };
    await page.route('**/api/**', async route => {
      const path = new URL(route.request().url()).pathname;
      if (!path.startsWith('/api/')) return route.continue();
      if (path === '/api/auth/csrf') return route.fulfill({ json: { code: 'OK', data: { token: 'fixture' } } });
      if (path.endsWith('/report/review')) {
        posted++;
        return route.fulfill({ json: { code: 'OK', data: { ...waiting, reportId: 'report', generation: 1, state: 'SUCCEEDED', reviewSkipped: true,
          evaluation: { verification: 'UNAVAILABLE', total: null, conclusion: '平台验收不可用，保留已知事实。', dimensions: [{ key: 'QUALITY', score: 0, feedback: '此处为明确标记的界面夹具，不是真实评分。', evidenceIds: ['file'] }, ...['CORRECTNESS', 'VALIDATION', 'COLLABORATION'].map(key => ({ key, score: null, feedback: '证据不足，保留为空。', evidenceIds: [] }))] },
          evidence: [{ id: 'file', kind: 'FILE', source: 'src/very-long-path-for-frozen-revision/Main.java', summary: '冻结版本的有界源码取样，不附全部私有工程。', truncated: true }] } } });
      }
      if (path.endsWith('/report')) return route.fulfill({ json: { code: 'OK', data: waiting } });
      if (path.endsWith('/report/evidence')) return route.fulfill({ json: { code: 'OK', data: { id: 'file', kind: 'FILE', source: 'Main.java', summary: '冻结源码', content: '<script>safe text</script>', truncated: false } } });
      return route.fulfill({ status: 404, json: { code: 'RESOURCE_NOT_FOUND' } });
    });
    await page.goto('/ui-tests/report-fixture.html');
    await expect(page.getByText('等待回顾 · 冻结修订 0')).toBeVisible();
    await page.getByRole('button', { name: '跳过回顾并评价' }).focus(); await page.keyboard.press('Enter');
    await expect.poll(() => posted).toBe(1);
    await expect(page.getByText('0 / 25')).toBeVisible();
    await page.locator('.evidence-button').click();
    await expect(page.getByText('<script>safe text</script>', { exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: `test-results/ui/report-${width}.png`, fullPage: true });
  });
}
