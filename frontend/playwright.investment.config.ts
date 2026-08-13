import { defineConfig } from '@playwright/test';

export default defineConfig({
    testDir: './tests/e2e',
    testMatch: 'investment-command.spec.ts',
    workers: 1,
    retries: 0,
    timeout: 60_000,
    use: {
        baseURL: process.env.INVESTMENT_E2E_BASE_URL ?? 'http://localhost:5180',
        headless: true,
        viewport: { width: 1280, height: 900 },
        launchOptions: process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : undefined,
    },
    reporter: [['list']],
});
