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

function installBrowserState(pathname: string, hash: string, token: string | null) {
    let storedToken = token;
    let currentHash = hash;
    let serverPathRedirects = 0;
    let hashRedirects = 0;

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
                get hash() {
                    return currentHash;
                },
                set hash(value: string) {
                    currentHash = value;
                    hashRedirects += 1;
                },
                get href() {
                    return `${pathname}${currentHash}`;
                },
                set href(value: string) {
                    serverPathRedirects += 1;
                    throw new Error(`unexpected server redirect: ${value}`);
                },
            },
        },
    });

    return {
        token: () => storedToken,
        hash: () => currentHash,
        hashRedirects: () => hashRedirects,
        serverPathRedirects: () => serverPathRedirects,
    };
}

async function rejectUnauthorized(url: string) {
    await assert.rejects(
        responseRejectedHandler()({ response: { status: 401 }, config: { url } }),
    );
}

test('登录和注册 401 不清除 token 或重定向', async () => {
    const browser = installBrowserState('/portfolio/', '#/login', 'existing-token');

    await rejectUnauthorized('/login');
    await rejectUnauthorized('/register');

    assert.equal(browser.token(), 'existing-token');
    assert.equal(browser.hashRedirects(), 0);
    assert.equal(browser.serverPathRedirects(), 0);
});

test('根目录的受保护接口 401 通过 HashRouter 返回登录页', async () => {
    const browser = installBrowserState('/', '#/accounts', 'expired-token');

    await rejectUnauthorized('/accounts');

    assert.equal(browser.token(), null);
    assert.equal(browser.hash(), '#/login');
    assert.equal(browser.hashRedirects(), 1);
    assert.equal(browser.serverPathRedirects(), 0);
});

test('子目录部署的 401 保持当前部署基址并跳转登录 hash', async () => {
    const browser = installBrowserState('/portfolio/', '#/assets', 'expired-token');

    await rejectUnauthorized('/assets/page');

    assert.equal(browser.token(), null);
    assert.equal(browser.hash(), '#/login');
    assert.equal(browser.hashRedirects(), 1);
    assert.equal(browser.serverPathRedirects(), 0);
});

test('已在登录 Hash 路由时只清除失效 token，不重复跳转', async () => {
    const browser = installBrowserState('/portfolio/', '#/login', 'expired-token');

    await rejectUnauthorized('/dashboard');

    assert.equal(browser.token(), null);
    assert.equal(browser.hash(), '#/login');
    assert.equal(browser.hashRedirects(), 0);
    assert.equal(browser.serverPathRedirects(), 0);
});
