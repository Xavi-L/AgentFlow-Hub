import { defineConfig } from '@playwright/test'
import path from 'node:path'

const control = process.env.V48_CONTROL_DIR
if (!control) throw new Error('V48_CONTROL_DIR must identify the running disposable V48 fixture')

export default defineConfig({
  testDir: '.',
  testMatch: 'failure-recovery.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  repeatEach: 1,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  outputDir: path.join(control, 'browser-artifacts'),
  reporter: [['list'], ['json', { outputFile: path.join(control, 'browser-results.json') }]],
  use: {
    baseURL: process.env.V48_FRONTEND_URL || 'http://127.0.0.1:5178',
    browserName: 'chromium',
    channel: process.env.V48_BROWSER_CHANNEL || (process.platform === 'darwin' ? 'chrome' : undefined),
    headless: true,
    viewport: { width: 1440, height: 1000 },
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    // Explicit post-login screenshots and public JSON below exclude login credentials/JWT.
    trace: 'off',
    video: 'off',
    screenshot: 'off',
  },
})
