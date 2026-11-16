import { expect, test } from '@playwright/test';
for (const width of [1440, 390]) {
  test(`authorized feedback previews the exact optional evidence at ${width}px (API fixture)`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    let submitted = 0;
    const snapshot = JSON.stringify({ targetType: 'REPORT', description: '评价遗漏了边界测试', evidence: [], retention: '与原演练同期限，删除即撤销读取' }, null, 2);
    await page.route('**/api/**', async route => {
      const path = new URL(route.request().url()).pathname;
      if (!path.startsWith('/api/')) return route.continue();
      let data: unknown;
      if (path === '/api/auth/csrf') data = { token: 'fixture' };
      else if (path.endsWith('/report')) data = { exerciseId: 'fixture-exercise', reportId: 'report', generation: 1, state: 'FAILED', finalRevision: '0', verification: 'UNAVAILABLE', finalTestId: null, reviewSkipped: true, platformVersionId: 'version', attempts: 3, reason: 'INVALID_RESPONSE', evaluation: null, evidence: [{ id: 'file', kind: 'FILE', source: 'Main.java', summary: '冻结源码片段', truncated: true }] };
      else if (path.endsWith('/feedback/preview')) { expect(route.request().postDataJSON().evidenceIds).toEqual([]); data = { snapshot, fingerprint: 'preview' }; }
      else if (path.endsWith('/feedback')) { submitted++; expect(route.request().postDataJSON().previewFingerprint).toBe('preview'); data = { id: 'feedback', snapshot, state: 'PENDING', reply: null, createdAt: '2026-01-01T00:00:00Z', handledAt: null }; }
      else return route.fulfill({ status: 404, json: { code: 'RESOURCE_NOT_FOUND' } });
      return route.fulfill({ json: { code: 'OK', data } });
    });
    await page.goto('/ui-tests/report-fixture.html');
    await page.getByText('对此评价授权反馈', { exact: true }).click();
    await page.getByLabel('反馈描述（最多 4 KiB）').fill('评价遗漏了边界测试');
    await page.getByRole('button', { name: '预览实际发送范围' }).click();
    await expect(page.locator('.feedback-composer pre')).toHaveText(snapshot);
    await expect(page.getByRole('button', { name: '确认授权并提交' })).toBeDisabled();
    await page.getByLabel('我已核对完整内容与保留规则，授权管理员读取此快照').check();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: `test-results/ui/feedback-preview-${width}.png`, fullPage: true });
    await page.getByRole('button', { name: '确认授权并提交' }).focus(); await page.keyboard.press('Enter');
    await expect.poll(() => submitted).toBe(1);
    await expect(page.getByText('反馈已提交，可在“授权反馈”页查看状态和一次性处理回复。')).toBeVisible();
  });
  test(`administrator only sees authorized snapshot and handles it once at ${width}px (API fixture)`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    let handled = false;
    const entry = () => ({ id: 'feedback', snapshot: '{"description":"授权描述", "evidence":[]}', state: handled ? 'HANDLED' : 'PENDING', reply: handled ? '已核对，仅处理授权材料。' : null, createdAt: '2026-01-01T00:00:00Z', handledAt: handled ? '2026-01-01T00:01:00Z' : null });
    await page.route('**/api/**', async route => {
      const path = new URL(route.request().url()).pathname;
      if (!path.startsWith('/api/')) return route.continue();
      let data: unknown;
      if (path === '/api/auth/session') data = { id: 'admin', username: 'administrator', role: 'ADMIN', passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' };
      else if (path === '/api/auth/csrf') data = { token: 'fixture' };
      else if (path === '/api/admin/feedback') data = [entry()];
      else if (path.endsWith('/reply')) { handled = true; data = entry(); }
      else return route.abort();
      return route.fulfill({ json: { code: 'OK', data } });
    });
    await page.goto('/ui-tests/login-fixture.html?page=feedback');
    await page.getByText('查看完整授权快照').click();
    await expect(page.locator('.feedback-entry a')).toHaveCount(0);
    await page.getByLabel('一次性处理回复（最多 4 KiB）').fill('已核对，仅处理授权材料。');
    await page.getByRole('button', { name: '回复并标记已处理' }).click();
    await expect(page.getByRole('heading', { name: '已处理' })).toBeVisible();
    await expect(page.getByText('处理回复：已核对，仅处理授权材料。')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: `test-results/ui/feedback-admin-${width}.png`, fullPage: true });
  });
}
