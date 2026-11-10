import { defineConfig } from '@playwright/test';

// Real browser IndexedDB, no Java, PostgreSQL, Go or Docker.
export default defineConfig({
  testDir: './integration', fullyParallel: true, retries: 0, timeout: 10000,
  use: { baseURL: 'http://127.0.0.1:4174', browserName: 'chromium', trace: 'retain-on-failure' },
  webServer: { command: 'npm run dev -- --port 4174', url: 'http://127.0.0.1:4174', reuseExistingServer: !process.env.CI },
});
