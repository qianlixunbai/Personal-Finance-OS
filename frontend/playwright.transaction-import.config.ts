import { defineConfig } from '@playwright/test';

export default defineConfig({
    testDir: './tests/e2e',
    testMatch: 'transaction-import.spec.ts',
    workers: 1,
    retries: 0,
    timeout: 30_000,
    webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 5181', url: 'http://127.0.0.1:5181', reuseExistingServer: true, timeout: 30_000 },
    use: { baseURL: 'http://127.0.0.1:5181', headless: true, viewport: { width: 1280, height: 900 } },
    reporter: [['list']],
});
