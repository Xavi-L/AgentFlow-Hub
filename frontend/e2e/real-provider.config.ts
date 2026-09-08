import { defineConfig } from '@playwright/test'

const seconds = Number(process.env.V47_TASK_TIMEOUT_SECONDS || 180)
if (!Number.isInteger(seconds) || seconds < 60 || seconds > 300) throw new Error('V47_TASK_TIMEOUT_SECONDS must be an integer from 60 to 300')

export default defineConfig({
  testDir: '.',
  testMatch: 'real-provider.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  repeatEach: 1,
  timeout: (seconds + 240) * 1000,
  globalTimeout: (seconds + 300) * 1000,
  expect: { timeout: 15_000 },
  outputDir: process.env.V47_BROWSER_ARTIFACTS || 'test-results/v47',
  reporter: [['list']],
  use: {
    baseURL: process.env.V47_FRONTEND_URL || 'http://127.0.0.1:5173',
    browserName: 'chromium',
    channel: process.env.V47_BROWSER_CHANNEL || (process.platform === 'darwin' ? 'chrome' : undefined),
    headless: true,
    viewport: { width: 1440, height: 1000 },
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    // Login/password/JWT must never enter a Playwright trace, HAR or video bundle.
    trace: 'off',
    video: 'off',
    screenshot: 'off',
  },
})
