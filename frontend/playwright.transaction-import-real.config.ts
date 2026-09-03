import { defineConfig } from '@playwright/test';

export default defineConfig({
    testDir: './tests/e2e',
    testMatch: 'transaction-import-real.spec.ts',
    workers: 1,
    retries: 0,
    timeout: 60_000,
    use: {
        baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5180',
        headless: true,
        viewport: { width: 1280, height: 900 },
        launchOptions: process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : undefined,
    },
    reporter: [['list']],
});
