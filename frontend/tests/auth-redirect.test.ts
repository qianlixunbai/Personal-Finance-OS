import assert from 'node:assert/strict';
import test from 'node:test';
import api from '../src/api/index.ts';

type UnauthorizedError = {
    response?: { status?: number };
    config?: { url?: string };
};

type RejectedHandler = (error: UnauthorizedError) => Promise<never>;

function responseRejectedHandler(): RejectedHandler {
    const responseInterceptor = api.interceptors.response as unknown as {
        handlers: Array<{ rejected: RejectedHandler }>;
    };
    return responseInterceptor.handlers[0].rejected;
}

function installBrowserState(pathname: string, token: string | null) {
    let storedToken = token;
    let href = pathname;
    let redirectCount = 0;

    Object.defineProperty(globalThis, 'localStorage', {
        configurable: true,
        value: {
            getItem: (key: string) => key === 'token' ? storedToken : null,
            removeItem: (key: string) => {
                if (key === 'token') storedToken = null;
            },
        },
    });
    Object.defineProperty(globalThis, 'window', {
        configurable: true,
        value: {
            location: {
                pathname,
                get href() {
                    return href;
                },
                set href(value: string) {
                    href = value;
                    redirectCount += 1;
                },
            },
        },
    });

    return {
        token: () => storedToken,
        href: () => href,
        redirectCount: () => redirectCount,
    };
}

async function rejectUnauthorized(url: string) {
    await assert.rejects(
        responseRejectedHandler()({ response: { status: 401 }, config: { url } }),
    );
}

test('登录请求返回 401 时保留 token 和当前页面，由登录页展示错误信息', async () => {
    const browser = installBrowserState('/login', 'existing-token');

    await rejectUnauthorized('/login');

    assert.equal(browser.token(), 'existing-token');
    assert.equal(browser.redirectCount(), 0);
});

test('注册请求返回 401 时不触发 token 失效跳转', async () => {
    const browser = installBrowserState('/register', 'existing-token');

    await rejectUnauthorized('/register');

    assert.equal(browser.token(), 'existing-token');
    assert.equal(browser.redirectCount(), 0);
});

test('受保护接口返回 401 但当前没有 token 时不跳转', async () => {
    const browser = installBrowserState('/accounts', null);

    await rejectUnauthorized('/accounts');

    assert.equal(browser.token(), null);
    assert.equal(browser.redirectCount(), 0);
});

test('受保护接口返回 401 且存在 token 时清除 token 并跳转登录页', async () => {
    const browser = installBrowserState('/accounts', 'expired-token');

    await rejectUnauthorized('/accounts');

    assert.equal(browser.token(), null);
    assert.equal(browser.href(), '/login');
    assert.equal(browser.redirectCount(), 1);
});

test('已在登录页时只清除失效 token，不重复跳转', async () => {
    const browser = installBrowserState('/login', 'expired-token');

    await rejectUnauthorized('/dashboard');

    assert.equal(browser.token(), null);
    assert.equal(browser.redirectCount(), 0);
});
