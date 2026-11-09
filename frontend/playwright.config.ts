import { defineConfig } from '@playwright/test';

if (!process.env.TRACER_WEB_URL) {
  throw new Error('Use node scripts/test-tracer.mjs to provision real isolated services, or set TRACER_WEB_URL.');
}
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 30000,
  preserveOutput: process.env.IDENTITY_E2E ? 'never' : 'always',
  use: { baseURL: process.env.TRACER_WEB_URL, browserName: 'chromium',
    trace: process.env.IDENTITY_E2E ? 'off' : 'retain-on-failure', screenshot: 'off', video: 'off' },
});
