import { expect, test } from '@playwright/test';

// Real Java/PostgreSQL only. No credential-bearing traces, screenshots or videos.
test.skip(process.env.IDENTITY_E2E !== 'true', 'Use node scripts/test-tracer.mjs --identity');

test('invitation, mandatory password change, administrator reset and old-browser revocation', async ({ page, context, browser }) => {
  const username = process.env.IDENTITY_TEST_ADMIN ?? '';
  const initialPassword = process.env.IDENTITY_TEST_PASSWORD ?? '';
  const newPassword = process.env.IDENTITY_TEST_NEW_PASSWORD ?? '';
  await page.goto('/account');
  await page.getByLabel('账号名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(initialPassword);
  await page.getByRole('button', { name: '登录', exact: true }).focus();
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
  const createdBody: { data: { userId: string } } = await (await created).json();
  const userId = createdBody.data.userId;
  await expect(page.getByRole('heading', { name: '临时凭证只展示这一次' })).toBeVisible();
  const temporary = await page.getByLabel('临时密码', { exact: true }).inputValue();
  expect(temporary.length === 43).toBe(true);
  await page.getByRole('button', { name: '已交付，清除显示' }).click();
  await page.reload();
  await expect(page.getByRole('heading', { name: '创建受邀账号' })).toBeVisible();
  await expect(page.getByLabel('临时密码', { exact: true })).toHaveCount(0);

  const userContext = await browser.newContext({ baseURL: process.env.TRACER_WEB_URL });
  try {
    const userPage = await userContext.newPage();
    await userPage.goto('/account');
    await userPage.getByLabel('账号名', { exact: true }).fill('e2e_invited');
    await userPage.getByLabel('密码', { exact: true }).fill(temporary);
    await userPage.getByRole('button', { name: '登录', exact: true }).click();
    await expect(userPage.getByRole('heading', { name: '先修改临时密码' })).toBeVisible();
    await userPage.getByLabel('当前密码', { exact: true }).fill(temporary);
    await userPage.getByLabel('新密码', { exact: true }).fill('😀'.repeat(8));
    await userPage.getByRole('button', { name: '修改密码并重新登录' }).click();
    await expect(userPage.getByRole('heading', { name: '受邀账号登录' })).toBeVisible();
    await userPage.getByLabel('密码', { exact: true }).fill('😀'.repeat(8));
    await userPage.getByRole('button', { name: '登录', exact: true }).click();
    await expect(userPage.getByText('账号已就绪，可进入题目与工作区工程底座。')).toBeVisible();
    await expect(userPage.getByRole('heading', { name: '重置账号密码' })).toHaveCount(0);
    expect((await userContext.cookies()).some((cookie) => cookie.name === 'codecraft_session'
      && cookie.httpOnly && cookie.sameSite === 'Lax')).toBe(true);
    const csrf: unknown = await (await userPage.request.get('/api/auth/csrf')).json();
    if (typeof csrf !== 'object' || csrf === null || !('data' in csrf) || typeof csrf.data !== 'object' || csrf.data === null
      || !('token' in csrf.data) || typeof csrf.data.token !== 'string') throw new Error('Invalid CSRF response');
    const denied = await userPage.request.post(`/api/admin/users/${userId}/password-reset`, { data: {},
      headers: { Origin: process.env.TRACER_WEB_URL ?? '', 'X-CSRF-TOKEN': csrf.data.token, 'Idempotency-Key': 'denied-reset-0001' } });
    expect(denied.status()).toBe(403);
    await userPage.setViewportSize({ width: 375, height: 720 });
    expect(await userPage.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);

    await page.getByLabel('目标用户标识').fill('00000000-0000-0000-0000-000000000001');
    await page.getByRole('button', { name: '确认重置密码' }).click();
    await expect(page.getByRole('alert')).toContainText('目标账号不存在');
    await page.getByLabel('目标用户标识').fill(userId);
    await page.getByRole('button', { name: '确认重置密码' }).click();
    await expect(page.getByRole('heading', { name: '临时凭证只展示这一次' })).toBeVisible();
    const replacement = await page.getByLabel('临时密码', { exact: true }).inputValue();
    expect(replacement.length === 43 && replacement !== temporary).toBe(true);
    expect((await userPage.request.get('/api/auth/session')).status()).toBe(401);
    await userPage.reload();
    await expect(userPage.getByRole('heading', { name: '受邀账号登录' })).toBeVisible();
    expect(await userPage.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
    await page.getByRole('button', { name: '已交付，清除显示' }).click();
  } finally { await userContext.close(); }
  await page.getByRole('button', { name: '退出登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '受邀账号登录' })).toBeVisible();
  expect((await context.request.get('/api/auth/session')).status()).toBe(401);
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
});
