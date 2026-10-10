import { defineConfig } from '@playwright/test';

export default defineConfig({
    testDir: './tests/e2e',
    testMatch: 'asset-allocation-tooltip.spec.ts',
    workers: 1,
    retries: 0,
    timeout: 30_000,
    webServer: {
        command: 'npm run dev -- --host 127.0.0.1 --port 5183 --strictPort',
        url: 'http://127.0.0.1:5183',
        reuseExistingServer: false,
        timeout: 30_000,
    },
    use: {
        baseURL: 'http://127.0.0.1:5183',
        headless: true,
        reducedMotion: 'reduce',
        launchOptions: process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : undefined,
    },
    projects: [
        { name: 'desktop', use: { viewport: { width: 1280, height: 900 } } },
        { name: 'narrow', use: { viewport: { width: 390, height: 844 } } },
    ],
    reporter: [['list']],
});
