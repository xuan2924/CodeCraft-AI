import { expect, test } from '@playwright/test';
for (const width of [1440, 390]) {
  test(`invited login layout and captcha keyboard interaction at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    let issued = 0;
    await page.route('**/api/auth/**', async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith('/session')) return route.fulfill({ status: 401, json: { code: 'UNAUTHENTICATED', data: null } });
      if (path.endsWith('/csrf')) return route.fulfill({ json: { code: 'OK', data: { token: 'fixture' } } });
      if (path.endsWith('/captcha')) {
        issued++;
        return route.fulfill({ json: { code: 'OK', data: { id: 'a'.repeat(43), expiresAt: new Date(Date.now() + 120000).toISOString(),
          image: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jH1kAAAAASUVORK5CYII=' } } });
      }
      return route.abort();
    });
    await page.goto('/ui-tests/login-fixture.html');
    await expect(page.getByLabel('图片验证码', { exact: true })).toBeEnabled();
    await page.getByLabel('账号名', { exact: true }).fill('invited');
    await page.getByLabel('密码', { exact: true }).fill('fixture-password');
    await expect(page.getByRole('button', { name: '登录', exact: true })).toBeDisabled();
    await page.getByLabel('图片验证码', { exact: true }).fill('ABCDE');
    await expect(page.getByRole('button', { name: '登录', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: '刷新图片' }).focus();
    await page.keyboard.press('Enter');
    await expect.poll(() => issued).toBe(2);
    await expect(page.getByLabel('图片验证码', { exact: true })).toHaveValue('');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: `test-results/ui/login-${width}.png`, fullPage: true });
  });
}
