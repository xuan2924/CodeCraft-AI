import { expect, test } from '@playwright/test';

for (const width of [1440, 390]) {
  test(`shell and long content fit ${width}px; navigation remains keyboard accessible`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/ui-tests/fixture.html');
    await expect(page.getByRole('heading', { name: '工程演练组件检查' })).toBeVisible();
    if (width < 900) {
      const toggle = page.getByRole('button', { name: '展开或收起导航' });
      await toggle.focus();
      await page.keyboard.press('Enter');
      await expect(toggle).toHaveAttribute('aria-expanded', 'true');
      await expect(page.getByRole('navigation')).toBeVisible();
      await page.getByRole('link', { name: '个人模型' }).click();
      await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({ path: `test-results/ui/shell-${width}.png`, fullPage: true });
  });
}

test('dialog traps keyboard focus, Escape restores the trigger, and splitter supports arrow keys', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/ui-tests/fixture.html');
  const trigger = page.getByRole('button', { name: '打开确认' });
  await trigger.click();
  const dialog = page.getByRole('dialog', { name: '确认操作' });
  await expect(dialog).toBeVisible();
  for (let index = 0; index < 6; index++) {
    await page.keyboard.press('Tab');
    expect(await dialog.evaluate((element) => element.contains(document.activeElement))).toBe(true);
  }
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(trigger).toBeFocused();
  const separator = page.getByRole('separator');
  const before = Number(await separator.getAttribute('aria-valuenow'));
  await separator.focus();
  await page.keyboard.press('ArrowRight');
  await expect.poll(async () => Number(await separator.getAttribute('aria-valuenow'))).toBeGreaterThan(before);
});

test('reduced motion disables looping loaders and retains focus outlines', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/ui-tests/fixture.html');
  const button = page.getByRole('button', { name: '打开确认' });
  await button.focus();
  expect(await button.evaluate((element) => getComputedStyle(element).outlineStyle)).toBe('solid');
  expect(await page.evaluate(() => {
    const spinner = document.createElement('span');
    spinner.className = 'ui-spinner'; document.body.append(spinner);
    const duration = getComputedStyle(spinner).animationDuration;
    spinner.remove(); return duration;
  })).toBe('1e-05s');
});
