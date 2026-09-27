import { defineConfig, devices } from '@playwright/test'
const external = process.env.DOCQUERY_E2E_BASE_URL
const baseURL = external || 'http://127.0.0.1:5173'
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    ...devices['Desktop Chrome'],
    baseURL,
    channel: process.env.DOCQUERY_E2E_BROWSER_CHANNEL || undefined,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: external
    ? undefined
    : {
        command: 'node scripts/dev.mjs',
        url: `${baseURL}/admin/`,
        reuseExistingServer: !process.env.CI,
        timeout: 60_000,
      },
})
