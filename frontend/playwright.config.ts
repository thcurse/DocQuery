import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.DOCQUERY_E2E_BASE_URL ?? 'http://127.0.0.1:8080'
const browserChannel = process.env.DOCQUERY_E2E_BROWSER_CHANNEL || undefined

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
    channel: browserChannel,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
})
