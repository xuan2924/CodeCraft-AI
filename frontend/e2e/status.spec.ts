import { expect, test } from '@playwright/test';

const javaUnavailable = process.env.EXPECT_JAVA === 'UNAVAILABLE';
const expectedDatabase = process.env.EXPECT_DATABASE ?? 'AVAILABLE';
const expectedSandbox = process.env.EXPECT_SANDBOX ?? 'AVAILABLE';

test('browser reports actual dependency state through Java, with keyboard retry', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '服务状态' })).toBeVisible();
  const retry = page.getByRole('button', { name: '重新检查' });
  if (javaUnavailable) {
    await expect(page.getByRole('alert')).toContainText('无法取得有效状态');
    await expect(page.getByRole('table')).toHaveCount(0);
  } else {
    const response = await page.request.get('/api/internal/status');
    expect(response.status()).toBe(expectedDatabase === 'AVAILABLE' && expectedSandbox === 'AVAILABLE' ? 200 : 503);
    expect(await response.json()).toEqual({ database: expectedDatabase, sandbox: expectedSandbox });
    expect(response.headers()['cache-control']).toBe('no-store');
    await expect(page.getByTestId('database').locator('td').last()).toHaveText(expectedDatabase === 'AVAILABLE' ? '可用' : '不可用');
    await expect(page.getByTestId('sandbox').locator('td').last()).toHaveText(expectedSandbox === 'AVAILABLE' ? '可用' : '不可用');
  }
  await expect(retry).toBeEnabled();
  await retry.focus();
  const request = page.waitForResponse('**/api/internal/status');
  await page.keyboard.press('Enter');
  await request;
  await expect(retry).toBeEnabled();
  if (process.env.TRACER_SCREENSHOT_DIR) {
    await page.screenshot({ path: `${process.env.TRACER_SCREENSHOT_DIR}/desktop-${expectedDatabase}-${expectedSandbox}-${javaUnavailable}.png`, fullPage: true });
  }
  await page.setViewportSize({ width: 375, height: 720 });
  await expect(retry).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  if (process.env.TRACER_SCREENSHOT_DIR) {
    await page.screenshot({ path: `${process.env.TRACER_SCREENSHOT_DIR}/mobile-${expectedDatabase}-${expectedSandbox}-${javaUnavailable}.png`, fullPage: true });
  }
});
