import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: '.',
  testMatch: '**/*.spec.ts',
  testIgnore: '**/real-provider.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  outputDir: process.env.V43_BROWSER_ARTIFACTS || 'test-results/v43',
  reporter: [['list']],
  use: {
    baseURL: process.env.V43_FRONTEND_URL || 'http://127.0.0.1:5173',
    browserName: 'chromium',
    channel: process.env.V43_BROWSER_CHANNEL || (process.platform === 'darwin' ? 'chrome' : undefined),
    headless: true,
    viewport: { width: 1440, height: 1000 },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
})
