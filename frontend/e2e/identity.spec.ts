import { expect, test, type Page, type Response } from '@playwright/test';
import { randomUUID } from 'node:crypto';

// Credentials belong only to this disposable tracer database, never development .env.
test.skip(process.env.IDENTITY_E2E !== 'true', 'Use node scripts/test-tracer.mjs --identity');

async function solveFixtureCaptcha(page: Page, response: Response) {
  const data: { data: { id: string } } = await response.json();
  const fixture = await page.request.post(`${process.env.BROWSER_FIXTURE_URL}/captcha`, {
    headers: { Authorization: `Bearer ${process.env.BROWSER_FIXTURE_KEY}` }, data: { id: data.data.id },
  });
  expect(fixture.status()).toBe(201);
  await page.getByLabel('图片验证码', { exact: true }).fill('ABCDE');
}

test('administrator login, mandatory password change, invitation and logout @smoke', async ({ page }) => {
  const username = process.env.IDENTITY_TEST_ADMIN ?? '';
  const initialPassword = process.env.IDENTITY_TEST_PASSWORD ?? '';
  const newPassword = process.env.IDENTITY_TEST_NEW_PASSWORD ?? '';
  const firstCaptcha = page.waitForResponse('**/api/auth/captcha');
  await page.goto('/account');
  await solveFixtureCaptcha(page, await firstCaptcha);
  await page.getByLabel('账号名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(initialPassword);
  await page.getByRole('button', { name: '登录', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: '先修改临时密码' })).toBeVisible();
  await page.getByLabel('当前密码', { exact: true }).fill(initialPassword);
  await page.getByLabel('新密码', { exact: true }).fill(newPassword);
  const secondCaptcha = page.waitForResponse('**/api/auth/captcha');
  await page.getByRole('button', { name: '修改密码并重新登录' }).click();
  await solveFixtureCaptcha(page, await secondCaptcha);
  await expect(page.getByRole('heading', { name: '受邀账号登录' })).toBeVisible();
  await page.getByLabel('账号名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(newPassword);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '创建受邀账号' })).toBeVisible();
  await page.getByLabel('新账号名').fill(`e2e_${randomUUID().replaceAll('-', '').slice(0, 24)}`);
  await page.getByRole('button', { name: '创建账号', exact: true }).click();
  await expect(page.getByRole('heading', { name: '临时凭证只展示这一次' })).toBeVisible();
  await expect(page.getByLabel('临时密码', { exact: true })).not.toHaveValue('');
  await page.getByRole('button', { name: '已交付，清除显示' }).click();
  await page.getByRole('button', { name: '退出登录', exact: true }).click();
  await page.getByRole('button', { name: '丢弃账号草稿并退出', exact: true }).click();
  await expect(page.getByRole('heading', { name: '受邀账号登录' })).toBeVisible();
});
