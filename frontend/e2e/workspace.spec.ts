import { test, expect } from './fixtures';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

test.skip(process.env.WORKSPACE_E2E !== 'true', 'Use node scripts/test-tracer.mjs --workspace');
const sourcePath = 'src/main/java/interview/CartTotal.java';

test('typing and refreshing offers the unsaved draft for recovery @smoke', async ({ page, workspace }) => {
  await page.goto('/workspace');
  await page.getByRole('button', { name: '接管操作权', exact: true }).click();
  await expect(page.getByText('持有操作权', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: sourcePath, exact: true }).click();
  const editor = page.getByRole('textbox', { name: '文件编辑器', exact: false });
  await expect(page.getByRole('button', { name: '保存文件', exact: true })).toBeEnabled();
  await expect(editor).toBeEditable();
  const marker = `// unsaved draft ${workspace.exerciseId}`;
  await editor.focus();
  await page.keyboard.press('ControlOrMeta+End');
  await page.keyboard.insertText(`\n${marker}\n`);
  await expect(page.getByText('本地草稿已更新；', { exact: false })).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: '接管操作权', exact: true }).click();
  await expect(page.getByText('持有操作权', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: sourcePath, exact: true }).click();
  await page.getByRole('button', { name: '恢复此草稿', exact: true }).click();
  await expect(page.getByText('草稿已恢复到编辑器，尚未写入服务器。', { exact: true })).toBeVisible();
  await expect(editor).toHaveValue(new RegExp(marker));
});

test('save and run a solution, then recover the durable result after refresh @smoke', async ({ page, workspace }) => {
  test.setTimeout(60000);
  await page.goto('/workspace');
  await page.getByRole('button', { name: '接管操作权', exact: true }).click();
  await expect(page.getByText('持有操作权', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: sourcePath, exact: true }).click();
  const reference = readFileSync(resolve('..', 'problems/cart-total/1.1.0/reference', sourcePath), 'utf8');
  const editor = page.getByRole('textbox', { name: '文件编辑器', exact: false });
  await expect(page.getByRole('button', { name: '保存文件', exact: true })).toBeEnabled();
  await expect(editor).toBeEditable();
  await editor.focus();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.insertText(reference + `\n// ${workspace.workspaceId}\n`);
  await page.getByRole('tab', { name: '验证输出' }).click();
  await page.getByRole('button', { name: '运行基础测试', exact: true }).click();
  await page.getByRole('button', { name: '保存后运行', exact: true }).click();
  const result = page.getByRole('article', { name: '当前测试结果' });
  await expect(result.getByRole('heading')).toContainText('通过', { timeout: 45000 });
  await expect(result).toContainText('启动修订 1');
  await expect(result).toContainText('清理：SUCCEEDED / SUCCEEDED');
  await page.reload();
  await page.getByRole('tab', { name: '验证输出' }).click();
  await expect(page.getByRole('article', { name: '当前测试结果' })).toContainText('通过');
  await expect(page.getByRole('article', { name: '当前测试结果' })).toContainText('启动修订 1');
});
