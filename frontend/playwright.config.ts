import { defineConfig } from '@playwright/test';

if (!process.env.TRACER_WEB_URL) {
  throw new Error('Use node scripts/test-tracer.mjs to provision real isolated services, or set TRACER_WEB_URL.');
}
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 30000,
  outputDir: process.env.PLAYWRIGHT_OUTPUT_DIR ?? 'test-results/e2e',
  reporter: [['list'], ['html', { outputFolder: process.env.PLAYWRIGHT_REPORT_DIR ?? 'playwright-report', open: 'never' }]],
  preserveOutput: 'failures-only',
  use: { baseURL: process.env.TRACER_WEB_URL, browserName: 'chromium',
    trace: 'retain-on-failure', screenshot: 'only-on-failure', video: 'retain-on-failure' },
});
