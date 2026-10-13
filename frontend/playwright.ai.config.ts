import { defineConfig } from '@playwright/test';

export default defineConfig({
    testDir: './tests/e2e',
    testMatch: ['ai-analyst.spec.ts', 'ai-state-isolation.spec.ts'],
    forbidOnly: !!process.env.CI,
    workers: 1,
    retries: 0,
    timeout: 30_000,
    webServer: {
        command: 'npm run dev -- --host 127.0.0.1 --port 5182 --strictPort',
        url: 'http://127.0.0.1:5182',
        reuseExistingServer: false,
        timeout: 30_000,
    },
    use: {
        baseURL: 'http://127.0.0.1:5182',
        headless: true,
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure',
        viewport: { width: 1280, height: 900 },
        launchOptions: process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : undefined,
    },
    reporter: [['list']],
});
