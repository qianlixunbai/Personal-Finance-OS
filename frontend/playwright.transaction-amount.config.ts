import { defineConfig } from '@playwright/test';

export default defineConfig({
    testDir: './tests/e2e',
    testMatch: 'transaction-amount.spec.ts',
    workers: 1,
    retries: 0,
    timeout: 30_000,
    outputDir: './node_modules/.cache/playwright-transaction-amount',
    webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 5182', url: 'http://127.0.0.1:5182', reuseExistingServer: false, timeout: 30_000 },
    use: { baseURL: 'http://127.0.0.1:5182', headless: true, viewport: { width: 1280, height: 900 } },
    reporter: [['list']],
});
