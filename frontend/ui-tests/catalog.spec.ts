import { expect, test, type Page } from '@playwright/test';
async function fixtures(page: Page) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    let data: unknown;
    if (path === '/api/auth/session') data = { id: 'fixture', username: 'invited', role: 'USER', passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' };
    else if (path === '/api/problems') data = Array.from({ length: 14 }, (_, index) => ({ problemId: `problem-${index}`, problemVersionId: `v-${index}`, title: index ? `工程题目 ${index}` : '购物车金额计算与边界条件'.repeat(8), requirements: '实现确定性的金额计算。\n处理空购物车与数量边界。', version: '1.0.0', durationSeconds: index ? 2700 : 0, editableRoots: [], acceptanceFiles: {}, executionAvailable: index !== 0 }));
    else if (path === '/api/problem-favorites') data = [{ problemId: 'removed', title: '已下架的收藏题目', available: false }];
    else if (path === '/api/exercises/current') return route.fulfill({ status: 404, json: { code: 'RESOURCE_NOT_FOUND', data: null } });
    else if (path === '/api/exercises') data = new URL(route.request().url()).search ? [] : Array.from({ length: 50 }, (_, index) => ({ exerciseId: `exercise-${index}`, endedAt: '2026-01-01T00:00:00Z', retainUntil: '2026-01-31T00:00:00Z', deletionState: null }));
    else return route.abort();
    return route.fulfill({ json: { code: 'OK', data } });
  });
}
for (const width of [1440, 390]) {
  test(`catalog long titles, unavailable versions, zero duration and pagination at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); await fixtures(page);
    await page.goto('/ui-tests/login-fixture.html?page=catalog');
    await expect(page.getByRole('button', { name: '准备演练' })).toBeDisabled();
    await expect(page.getByText('时限 0 分钟 · 服务端执行环境')).toBeVisible();
    await expect(page.getByText('已下架的收藏题目 · 不可开始')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.locator('.problem-choice').first().focus();
    await expect(page.getByRole('tooltip')).toContainText('购物车金额计算与边界条件');
    await page.getByLabel('搜索题目').focus();
    await page.screenshot({ path: `test-results/ui/catalog-${width}.png`, fullPage: true });
    await page.getByRole('button', { name: '下一页' }).focus(); await page.keyboard.press('Enter');
    await expect(page.getByText('第 2 页')).toBeVisible();
    await page.getByLabel('仅收藏').check();
    await expect(page.getByText('没有符合筛选的题目')).toBeVisible();
  });
}
test('history retains dates, bounds horizontal table scrolling and paginates without fake reports', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 }); await fixtures(page);
  await page.goto('/ui-tests/login-fixture.html?page=history');
  await expect(page.getByRole('cell', { name: 'exercise-0', exact: true })).toBeVisible();
  await page.getByLabel('筛选本页演练').fill('exercise-49');
  await expect(page.getByRole('row')).toHaveCount(2);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('button', { name: '下一页' }).click();
  await expect(page.getByText('没有符合条件的历史演练。')).toBeVisible();
  await page.getByRole('button', { name: '返回第一页' }).click();
  await expect(page.getByRole('cell', { name: 'exercise-49', exact: true })).toBeVisible();
});
