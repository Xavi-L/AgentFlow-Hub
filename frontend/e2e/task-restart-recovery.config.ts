import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: '.', testMatch: '**/task-restart-recovery.spec.ts',
  fullyParallel: false, workers: 1, retries: 0, timeout: 60_000,
  expect: { timeout: 15_000 },
  outputDir: process.env.V02A_BROWSER_ARTIFACTS || `${process.env.V02A_CONTROL_DIR}/browser-artifacts`,
  reporter: [['list']],
  use: {
    baseURL: process.env.V02A_FRONTEND_URL || `http://127.0.0.1:${process.env.V02A_FRONTEND_PORT || 5182}`,
    browserName: 'chromium', channel: process.env.V02A_BROWSER_CHANNEL || (process.platform === 'darwin' ? 'chrome' : undefined),
    headless: true, viewport: { width: 1440, height: 1000 }, screenshot: 'only-on-failure', trace: 'retain-on-failure',
  },
})
