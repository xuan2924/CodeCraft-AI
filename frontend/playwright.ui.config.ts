import { defineConfig } from '@playwright/test';

/** Pure presentation tests, explicitly separate from real-service e2e/integration. */
export default defineConfig({
  testDir: './ui-tests', testMatch: '**/*.spec.ts', fullyParallel: true,
  forbidOnly: !!process.env.CI, retries: 0, timeout: 15000,
  outputDir: 'test-results/ui', reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:5197', browserName: 'chromium', screenshot: 'only-on-failure' },
  webServer: { command: 'npm run dev -- --port 5197', url: 'http://127.0.0.1:5197/ui-tests/fixture.html', reuseExistingServer: false },
});
