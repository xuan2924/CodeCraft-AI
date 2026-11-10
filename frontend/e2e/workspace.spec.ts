import { expect, test, type BrowserContext } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// Reuse the already-authenticated fixture without consuming a sixth login in
// the same rate-limit window. Credentials stay in this worker's memory only.
let sessionCookies: Awaited<ReturnType<BrowserContext['cookies']>> = [];

test.skip(process.env.WORKSPACE_E2E !== 'true', 'Use node scripts/test-tracer.mjs --workspace');

test('published problem, controlled editing, drafts, conflicts and atomic files', async ({ page, context }) => {
  const username = process.env.IDENTITY_TEST_ADMIN ?? '';
  const initial = process.env.IDENTITY_TEST_PASSWORD ?? '';
  const password = process.env.IDENTITY_TEST_NEW_PASSWORD ?? '';
  await page.goto('/account');
  await page.getByLabel('账号名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(initial);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '先修改临时密码' })).toBeVisible();
  await page.getByLabel('当前密码', { exact: true }).fill(initial);
  await page.getByLabel('新密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '修改密码并重新登录' }).click();
  await expect(page.getByRole('heading', { name: '受邀账号登录' })).toBeVisible();
  await page.getByLabel('账号名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('link', { name: '题目与工作区', exact: true }).click();
  await expect(page.getByRole('heading', { name: '题目与工作区', exact: true })).toBeVisible();
  await expect(page.getByText('工程底座：', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: '创建独立工作区' }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByText('已准备，仅可浏览', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'src/main/java/interview/CartTotal.java', exact: true }).click();
  await expect(page.getByTestId('source')).toContainText('class CartTotal');
  await expect(page.locator('textarea')).toHaveCount(0);
  await page.reload();
  await expect(page.getByText('已准备，仅可浏览', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'src/main/java/interview/CartTotal.java', exact: true }).click();
  await expect(page.getByTestId('source')).toContainText('class CartTotal');
  await page.setViewportSize({ width: 375, height: 720 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole('button', { name: '放弃本次演练' }).click();
  await expect(page.getByRole('button', { name: '创建独立工作区' })).toBeVisible();
  await page.getByRole('button', { name: '创建独立工作区' }).click();
  await expect(page.getByText('已准备，仅可浏览', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: '申请操作权' }).click();
  await expect(page.getByText('当前页面持有操作权', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '确认开始计时' }).click();
  await expect(page.getByText('已开始，请显式保存修改', { exact: false })).toBeVisible();
  await expect(page.getByText('服务器截止时间：', { exact: false })).toBeVisible();
  await page.setViewportSize({ width: 1280, height: 900 });
  const path = 'src/main/java/interview/CartTotal.java';
  await page.getByRole('button', { name: path, exact: true }).click();
  const current = await (await page.request.get('/api/exercises/current')).json();
  const fileUrl = `/api/exercises/${current.data.exerciseId}/files?path=${encodeURIComponent(path)}`;
  const original = (await (await page.request.get(fileUrl)).json()).data.content as string;
  const editor = page.getByRole('textbox', { name: '文件编辑器', exact: false });
  await editor.focus();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.insertText(original + '\n// saved by browser\n');
  await page.getByRole('button', { name: '保存文件', exact: true }).click();
  await expect(page.getByText('已保存到工作区，修订 1。', { exact: true })).toBeVisible();
  await editor.focus();
  await page.keyboard.press('ControlOrMeta+End');
  await page.keyboard.insertText('// obsolete draft after undo\n');
  await expect(page.getByText('本地草稿已更新；', { exact: false })).toBeVisible();
  await expect(page.getByRole('button', { name: '重命名文件', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '删除文件', exact: true })).toBeDisabled();
  page.once('dialog', (dialog) => dialog.dismiss());
  await page.getByRole('button', { name: '关闭文件', exact: true }).click();
  await expect(page.getByTestId('source')).toContainText('obsolete draft after undo');
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: '关闭文件', exact: true }).click();
  await page.getByRole('button', { name: path, exact: true }).click();
  await expect(page.getByTestId('source')).toContainText('obsolete draft after undo');
  await editor.focus();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.insertText(original + '\n// saved by browser\n');
  await expect(page.getByText('当前页面没有未保存修改；', { exact: false })).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: path, exact: true }).click();
  await expect(page.getByTestId('source')).toContainText('saved by browser');
  await expect(page.getByRole('button', { name: '恢复此草稿' })).toHaveCount(0);
  await editor.focus();
  await page.keyboard.press('ControlOrMeta+End');
  await page.keyboard.insertText('// recover this local draft\n');
  await expect(page.getByText('本地草稿已更新；', { exact: false })).toBeVisible();
  page.once('dialog', (dialog) => dialog.accept());
  await page.reload();
  await page.getByRole('button', { name: path, exact: true }).click();
  await page.getByRole('button', { name: '恢复此草稿' }).first().click();
  await expect(page.getByTestId('source')).toContainText('recover this local draft');
  expect((await (await page.request.get(fileUrl)).json()).data.content).not.toContain('recover this local draft');
  // Lose the browser session without explicit logout: IndexedDB must not be cleared.
  await context.clearCookies();
  page.once('dialog', (dialog) => dialog.accept());
  await page.reload();
  await page.goto('/account');
  await page.getByLabel('账号名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('link', { name: '题目与工作区', exact: true }).click();
  await page.getByRole('button', { name: path, exact: true }).click();
  await page.getByRole('button', { name: '恢复此草稿' }).first().click();
  await expect(page.getByTestId('source')).toContainText('recover this local draft');
  expect((await (await page.request.get(fileUrl)).json()).data.content).not.toContain('recover this local draft');
  const other = await context.newPage();
  await other.goto('/workspace');
  await other.getByRole('button', { name: '接管操作权' }).click();
  await other.getByRole('button', { name: path, exact: true }).click();
  const secondEditor = other.getByRole('textbox', { name: '文件编辑器', exact: false });
  await secondEditor.focus(); await other.keyboard.press('ControlOrMeta+a');
  await other.keyboard.insertText(original + '\n// saved by second page\n');
  await other.getByRole('button', { name: '保存文件', exact: true }).click();
  await expect(other.getByText('已保存到工作区，修订 2。', { exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: '当前服务端内容' })).toContainText('saved by second page');
  await expect(page.getByRole('button', { name: '保存文件', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '接管操作权' }).click();
  await page.getByRole('button', { name: '保留本地内容，采用此基线' }).click();
  await page.getByRole('button', { name: '保存文件', exact: true }).click();
  await expect(page.getByText('已保存到工作区，修订 3。', { exact: true })).toBeVisible();
  await expect(other.getByText('当前页面只读，可保留本地草稿', { exact: true })).toBeVisible();
  await other.close();
  await page.getByLabel('新建或重命名的目标路径').fill('src/test/java/BrowserFile.java');
  await page.getByRole('button', { name: '创建文本文件' }).click();
  await expect(page.getByText('已保存到工作区，修订 4。', { exact: true })).toBeVisible();
  await page.getByLabel('新建或重命名的目标路径').fill('src/test/java/Renamed.java');
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: '重命名文件', exact: true }).click();
  await expect(page.getByText('已保存到工作区，修订 5。', { exact: true })).toBeVisible();
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: '删除文件', exact: true }).click();
  await expect(page.getByText('已保存到工作区，修订 6。', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'src/test/java/Renamed.java', exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);

  const stalePage = await context.newPage();
  const session = await (await page.request.get('/api/auth/session')).json();
  // Model an old page retaining its identity snapshot, not valid server authorization.
  await stalePage.route('**/api/auth/session', (route) => route.fulfill({ json: session }));
  await stalePage.goto('/workspace');
  await stalePage.getByRole('button', { name: path, exact: true }).click();
  await stalePage.getByRole('textbox', { name: '文件编辑器', exact: false }).focus();
  await stalePage.keyboard.press('ControlOrMeta+End');
  await stalePage.keyboard.insertText('// stale page backup\n');
  await expect(stalePage.getByText('本地草稿已更新；', { exact: false })).toBeVisible();
  await page.getByRole('link', { name: '账号与登录' }).click();
  await page.getByRole('button', { name: '退出登录', exact: true }).click();
  await page.getByRole('button', { name: '取消退出', exact: true }).click();
  await page.getByRole('button', { name: '退出登录', exact: true }).click();
  await page.getByRole('button', { name: '保存后退出', exact: true }).click();
  const logoutPanel = page.getByRole('region', { name: '保存后退出处理' });
  await expect(logoutPanel).toBeVisible();
  // Older page variants are not silently removed by saving a different variant.
  await logoutPanel.getByRole('button', { name: '刷新草稿列表' }).click();
  await expect(logoutPanel.getByRole('button', { name: '刷新草稿列表' })).toBeEnabled();
  while (await logoutPanel.getByRole('button', { name: '丢弃这份备份' }).count()) {
    page.once('dialog', (dialog) => dialog.accept());
    await logoutPanel.getByRole('button', { name: '丢弃这份备份' }).first().click();
    await expect(logoutPanel.getByRole('button', { name: '刷新草稿列表' })).toBeEnabled();
  }
  await page.getByRole('button', { name: path, exact: true }).click();
  await expect(page.getByTestId('source')).toContainText('class CartTotal');
  await editor.focus();
  await page.keyboard.press('ControlOrMeta+End');
  await page.keyboard.insertText('// saved before logout\n');
  await expect(page.getByTestId('source')).toContainText('saved before logout');
  await logoutPanel.getByRole('button', { name: '保存编辑内容并完成退出' }).click();
  await expect(logoutPanel.getByRole('alert')).toContainText('保存未确认');
  expect((await page.request.get('/api/auth/session')).status()).toBe(200);
  await page.getByRole('button', { name: '接管操作权' }).click();
  await expect(page.getByText('当前页面持有操作权', { exact: true })).toBeVisible();
  const logoutOwner = (await (await page.request.get('/api/auth/session')).json()).data.id as string;
  // Deterministically introduce a late backup at the real IndexedDB final check,
  // after the preliminary list was empty. No API response is mocked.
  await page.evaluate((draft) => {
    const originalCursor = IDBObjectStore.prototype.openCursor;
    IDBObjectStore.prototype.openCursor = function (...args) {
      if (this.name === 'drafts' && this.transaction.mode === 'readwrite') {
        IDBObjectStore.prototype.openCursor = originalCursor;
        this.put(draft);
      }
      return originalCursor.apply(this, args);
    };
  }, { owner: logoutOwner, exercise: current.data.exerciseId as string, path, id: 'late-logout-race', revision: '6', baseContent: original, content: 'do not silently delete', updatedAt: Date.now() });
  await logoutPanel.getByRole('button', { name: '保存编辑内容并完成退出' }).click();
  await expect(logoutPanel.getByRole('alert')).toContainText('状态已变化');
  await expect(logoutPanel.getByText('do not silently delete', { exact: true })).toBeAttached();
  expect((await page.request.get('/api/auth/session')).status()).toBe(200);
  page.once('dialog', (dialog) => dialog.accept());
  await logoutPanel.getByRole('button', { name: '丢弃这份备份' }).click();
  await page.route('**/api/auth/logout', async (route) => {
    const response = await route.fetch();
    // Model a successful concurrent login's local epoch reopening while the old
    // logout response is delayed. Use the real database, not a mocked draft API.
    await page.evaluate((draft) => new Promise<void>((resolve, reject) => {
      const opened = indexedDB.open('codecraft-workspace-drafts');
      opened.onerror = () => reject(new Error('Draft database unavailable'));
      opened.onsuccess = () => {
        const database = opened.result;
        const tx = database.transaction(['drafts', 'accounts'], 'readwrite');
        tx.oncomplete = () => { database.close(); resolve(); };
        tx.onabort = () => { database.close(); reject(new Error('Draft fixture failed')); };
        const accounts = tx.objectStore('accounts');
        const account = accounts.get(draft.owner);
        account.onsuccess = () => {
          accounts.put({ owner: draft.owner, epoch: Number(account.result.epoch) + 1, blocked: false });
          tx.objectStore('drafts').put(draft);
        };
      };
    }), { owner: logoutOwner, exercise: current.data.exerciseId as string, path, id: 'new-login-draft', revision: '7', baseContent: original, content: 'new login draft must survive old logout', updatedAt: Date.now() });
    await route.fulfill({ response });
  });
  await logoutPanel.getByRole('button', { name: '保存编辑内容并完成退出' }).click();
  await expect(page.getByText('请先登录并完成临时密码修改。', { exact: true })).toBeVisible();
  await page.goto('/account');
  await page.getByLabel('账号名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('link', { name: '题目与工作区', exact: true }).click();
  expect((await (await page.request.get(fileUrl)).json()).data.content).toContain('saved before logout');

  const countDrafts = () => page.evaluate(() => new Promise<number>((resolve, reject) => {
    const request = indexedDB.open('codecraft-workspace-drafts');
    request.onerror = () => reject(new Error('Draft database unavailable'));
    request.onsuccess = () => {
      const database = request.result;
      const count = database.transaction('drafts').objectStore('drafts').count();
      count.onsuccess = () => { database.close(); resolve(count.result); };
      count.onerror = () => { database.close(); reject(new Error('Draft count failed')); };
    };
  }));
  expect(await countDrafts()).toBe(1);
  await stalePage.getByRole('textbox', { name: '文件编辑器', exact: false }).focus();
  await stalePage.keyboard.insertText('// must not revive after logout and login\n');
  await expect(stalePage.getByText('本地草稿更新失败', { exact: false })).toBeVisible();
  expect(await countDrafts()).toBe(1);
  await page.getByRole('button', { name: path, exact: true }).click();
  await page.getByRole('button', { name: '恢复此草稿' }).click();
  await expect(page.getByTestId('source')).toContainText('new login draft must survive old logout');
  expect((await (await page.request.get(fileUrl)).json()).data.content).not.toContain('new login draft must survive old logout');
  stalePage.once('dialog', (dialog) => dialog.accept());
  await stalePage.close();
  sessionCookies = await context.cookies();
});

test('runs saved stable versions and marks results stale after a later save', async ({ page, context }) => {
  test.setTimeout(60000);
  expect(sessionCookies.length).toBeGreaterThan(0);
  await context.addCookies(sessionCookies);
  await page.goto('/workspace');
  await page.getByRole('button', { name: '接管操作权', exact: true }).click();
  const path = 'src/main/java/interview/CartTotal.java';
  await page.getByRole('button', { name: path, exact: true }).click();
  const current = (await (await page.request.get('/api/exercises/current')).json()).data;
  const base = `/api/exercises/${current.exerciseId}`;
  const editor = page.getByRole('textbox', { name: '文件编辑器', exact: false });
  const result = page.getByRole('article', { name: '当前测试结果' });
  await page.getByRole('button', { name: '运行基础测试', exact: true }).click();
  await expect(page.getByRole('button', { name: '运行基础测试', exact: true })).toBeDisabled();
  await expect(result.getByRole('heading')).toContainText('测试失败', { timeout: 30000 });
  await expect(result).toContainText('副本清理：SUCCEEDED');
  const reference = readFileSync(resolve('..', 'problems/cart-total/1.0.1/reference/src/main/java/interview/CartTotal.java'), 'utf8');
  await editor.focus(); await page.keyboard.press('ControlOrMeta+a'); await page.keyboard.insertText(reference);
  await page.getByRole('button', { name: '运行基础测试', exact: true }).click();
  await expect(page.getByRole('group', { name: '运行前保存确认' })).toBeVisible();
  await page.getByRole('button', { name: '返回编辑', exact: true }).click();
  expect((await (await page.request.get(`${base}/tasks`)).json()).data).toHaveLength(1);
  await page.getByRole('button', { name: '运行基础测试', exact: true }).click();
  const started = page.waitForResponse((response) => response.url().endsWith(`${base}/tests`) && response.request().method() === 'POST');
  await page.getByRole('button', { name: '保存后运行', exact: true }).click();
  const accepted = (await (await started).json()).data;
  await expect.poll(async () => (await (await page.request.get(`${base}/tasks/${accepted.taskId}`)).json()).data.state,
    { timeout: 15000 }).toBe('RUNNING');
  await editor.focus(); await page.keyboard.press('ControlOrMeta+End'); await page.keyboard.insertText('\n// newer than the running test\n');
  await page.getByRole('button', { name: '保存文件', exact: true }).click();
  await expect(result.getByRole('heading')).toContainText('通过', { timeout: 30000 });
  await expect(result).toContainText('这是旧版本结果');
  await expect(result).toContainText(`启动修订 ${accepted.revision}`);
  await expect(result).toContainText('副本清理：SUCCEEDED');
  expect((await (await page.request.get(`${base}/tasks`)).json()).data).toHaveLength(2);
  await page.reload();
  await expect(page.getByRole('article', { name: '当前测试结果' })).toContainText('通过');
  expect((await (await page.request.get(`${base}/tasks`)).json()).data).toHaveLength(2);
});

test('streams readonly logs, recovers browser disconnect, preserves subscriptions on takeover and stops explicitly', async ({ page, context }) => {
  test.setTimeout(90000);
  await context.addCookies(sessionCookies);
  await page.goto('/workspace');
  await page.getByRole('button', { name: '接管操作权', exact: true }).click();
  const path = 'src/main/java/interview/CartTotal.java';
  await page.getByRole('button', { name: path, exact: true }).click();
  const current = (await (await page.request.get('/api/exercises/current')).json()).data;
  const base = `/api/exercises/${current.exerciseId}`;
  const reference = readFileSync(resolve('..', 'problems/cart-total/1.0.1/reference/src/main/java/interview/CartTotal.java'), 'utf8');
  const source = reference.replace('public final class CartTotal {', 'public final class CartTotal { static { System.out.print("\\033]0;untrusted-title\\007"); System.err.println("browser-stderr"); for (int tick=0; tick<60; tick++) { System.out.println("browser-live-"+tick); try { Thread.sleep(1000); } catch (InterruptedException e) { throw new RuntimeException(e); } } }');
  expect(source).toContain('browser-live-');
  const editor = page.getByRole('textbox', { name: '文件编辑器', exact: false });
  await editor.focus(); await page.keyboard.press('ControlOrMeta+a'); await page.keyboard.insertText(source);
  await page.getByRole('button', { name: '保存文件', exact: true }).click();
  await expect(page.getByText('当前页面没有未保存修改；', { exact: false })).toBeVisible();
  const acceptedResponse = page.waitForResponse((response) => response.url().endsWith(`${base}/tests`) && response.request().method() === 'POST');
  await page.getByRole('button', { name: '运行基础测试', exact: true }).click();
  const accepted = (await (await acceptedResponse).json()).data;
  const result = page.getByRole('article', { name: '当前测试结果' });
  const title = await page.title();
  await expect(result.locator('.test-output')).toContainText('browser-live-', { timeout: 30000 });
  await expect(result.locator('.test-output')).toContainText('[stderr]');
  await expect(result.locator('.xterm')).toBeVisible();
  expect(await page.title()).toBe(title);
  expect(await result.locator('.test-output').textContent()).not.toContain(String.fromCodePoint(27));
  const taskUrl = `${base}/tasks/${accepted.taskId}`;
  await context.setOffline(true);
  await page.waitForTimeout(1500); // Real network interruption, not synthetic SSE data.
  await context.setOffline(false);
  await page.reload();
  await expect(result).toContainText(accepted.taskId);
  await expect(result.locator('.test-output')).toContainText('browser-live-', { timeout: 15000 });
  expect((await (await page.request.get(taskUrl)).json()).data.state).toBe('RUNNING');
  expect((await (await page.request.get(`${base}/tasks`)).json()).data).toHaveLength(3);
  await page.getByRole('button', { name: '接管操作权', exact: true }).click();
  const other = await context.newPage();
  await other.goto('/workspace');
  await other.getByRole('button', { name: '接管操作权', exact: true }).click();
  await expect(page.getByRole('button', { name: '停止任务', exact: true })).toBeDisabled();
  await expect(result.locator('.test-output')).toContainText('browser-live-');
  await other.getByRole('button', { name: '停止任务', exact: true }).click();
  await expect(other.getByRole('article', { name: '当前测试结果' }).getByRole('heading')).toContainText('已停止', { timeout: 15000 });
  await expect(result.getByRole('heading')).toContainText('已停止', { timeout: 15000 });
  await expect(result).toContainText('副本清理：SUCCEEDED');
  expect((await (await page.request.get(`${base}/tasks`)).json()).data).toHaveLength(3);
  await other.close();
});
