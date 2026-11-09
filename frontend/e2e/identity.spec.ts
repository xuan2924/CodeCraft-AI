import { expect, test } from '@playwright/test';

// Real Java/PostgreSQL only. Disable traces/screenshots so one-time credentials are not recorded.
test.skip(process.env.IDENTITY_E2E !== 'true', 'Use node scripts/test-tracer.mjs --identity');

test('administrator invites a user, both change temporary passwords, role and logout are enforced', async ({ page, context }) => {
  const username = process.env.IDENTITY_TEST_ADMIN ?? '';
  const initialPassword = process.env.IDENTITY_TEST_PASSWORD ?? '';
  const newPassword = process.env.IDENTITY_TEST_NEW_PASSWORD ?? '';
  await page.goto('/account');
  await page.getByLabel('账号名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(initialPassword);
  const login = page.getByRole('button', { name: '登录', exact: true });
  await login.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: '先修改临时密码' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '创建受邀账号' })).toHaveCount(0);
  await page.getByLabel('当前密码', { exact: true }).fill(initialPassword);
  await page.getByLabel('新密码', { exact: true }).fill(newPassword);
  await page.getByRole('button', { name: '修改密码并重新登录' }).click();
  await expect(page.getByRole('heading', { name: '受邀账号登录' })).toBeVisible();
  await page.getByLabel('账号名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(newPassword);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '创建受邀账号' })).toBeVisible();
  await page.getByLabel('新账号名').fill('e2e_invited');
  const created = page.waitForResponse((response) => response.url().endsWith('/api/admin/users') && response.status() === 201);
  await page.getByRole('button', { name: '创建账号', exact: true }).click();
  await created;
  await expect(page.getByRole('heading', { name: '临时凭证只展示这一次' })).toBeVisible();
  const temporary = await page.getByLabel('临时密码', { exact: true }).inputValue();
  // Boolean assertion avoids printing the secret in a failed reporter output.
  expect(temporary.length === 43).toBe(true);
  await page.getByRole('button', { name: '已交付，清除显示' }).click();
  await expect(page.getByLabel('临时密码', { exact: true })).toHaveCount(0);
  await page.reload();
  await expect(page.getByRole('heading', { name: '创建受邀账号' })).toBeVisible();
  await page.getByRole('button', { name: '退出登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '受邀账号登录' })).toBeVisible();
  await page.getByLabel('账号名', { exact: true }).fill('e2e_invited');
  await page.getByLabel('密码', { exact: true }).fill(temporary);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '先修改临时密码' })).toBeVisible();
  await page.getByLabel('当前密码', { exact: true }).fill(temporary);
  await page.getByLabel('新密码', { exact: true }).fill('😀'.repeat(8));
  await page.getByRole('button', { name: '修改密码并重新登录' }).click();
  await expect(page.getByRole('heading', { name: '受邀账号登录' })).toBeVisible();
  await page.getByLabel('密码', { exact: true }).fill('😀'.repeat(8));
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByText('账号已就绪。题目与工作区功能尚未接入。')).toBeVisible();
  await expect(page.getByRole('heading', { name: '创建受邀账号' })).toHaveCount(0);
  const cookies = await context.cookies();
  expect(cookies.some((cookie) => cookie.name === 'codecraft_session' && cookie.httpOnly && cookie.sameSite === 'Lax')).toBe(true);
  const csrfResponse = await page.request.get('/api/auth/csrf');
  const csrf: unknown = await csrfResponse.json();
  if (typeof csrf !== 'object' || csrf === null || !('data' in csrf) || typeof csrf.data !== 'object' || csrf.data === null
    || !('token' in csrf.data) || typeof csrf.data.token !== 'string') throw new Error('Invalid CSRF response');
  const denied = await page.request.post('/api/admin/users', { data: { username: 'not_allowed' },
    headers: { Origin: process.env.TRACER_WEB_URL ?? '', 'X-CSRF-TOKEN': csrf.data.token, 'Idempotency-Key': 'denied-request-01' } });
  expect(denied.status()).toBe(403);
  await page.setViewportSize({ width: 375, height: 720 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole('button', { name: '退出登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '受邀账号登录' })).toBeVisible();
  expect((await page.request.get('/api/auth/session')).status()).toBe(401);
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
});
