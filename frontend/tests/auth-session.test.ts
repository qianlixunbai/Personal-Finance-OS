import assert from 'node:assert/strict';
import test from 'node:test';
import { AiAuthSafetyError, authSessionKey, clearAuthSession, establishAuthSession, isCurrentAuthSession, readAuthSession, sameAuthSession, withAuthSessionMutation } from '../src/utils/authSession.ts';

function fixture() {
    const data = new Map<string, string>();
    const storage = {
        getItem: (key: string) => data.get(key) ?? null,
        setItem: (key: string, value: string) => { data.set(key, value); },
        removeItem: (key: string) => { data.delete(key); },
    };
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: storage });
    Object.defineProperty(globalThis, 'window', { configurable: true, value: new EventTarget() });
    return storage;
}

test('same JWT/user relogin and ABA get distinct lifecycle identities', () => {
    fixture();
    establishAuthSession('fictional-a', 71);
    const oldA = readAuthSession();
    assert.ok(isCurrentAuthSession(oldA));
    clearAuthSession();
    assert.equal(isCurrentAuthSession(oldA), false);
    establishAuthSession('fictional-a', 71);
    const newA = readAuthSession();
    assert.equal(sameAuthSession(oldA, newA), false);
    establishAuthSession('fictional-b', 72);
    establishAuthSession('fictional-a', 71);
    assert.equal(isCurrentAuthSession(oldA), false);
    assert.equal(isCurrentAuthSession(newA), false);
});

test('legacy session is readable; intermediate login/logout storage is invalid', () => {
    const storage = fixture();
    storage.setItem('token', 'fictional-legacy');
    storage.setItem('finance-os:auth-user-id:v1', '71');
    const legacy = readAuthSession();
    assert.deepEqual(legacy, { id: null, token: 'fictional-legacy', userId: '71' });
    storage.setItem(authSessionKey, 'changing');
    assert.equal(readAuthSession(), null);
    establishAuthSession('fictional-legacy', 71);
    assert.equal(isCurrentAuthSession(legacy), false);
    storage.removeItem('finance-os:auth-user-id:v1');
    assert.equal(readAuthSession(), null);
});

test('auth lifecycle notifications are synchronous and preserve business recovery records', () => {
    const storage = fixture();
    const investmentKey = 'finance-os:investment-command:pending:v1:71:fixture';
    const importKey = 'finance-os:transaction-import:pending:v1:71:fixture';
    storage.setItem(investmentKey, 'investment-evidence');
    storage.setItem(importKey, 'import-evidence');
    const observed: unknown[] = [];
    window.addEventListener('finance-os:auth-session-changed', () => observed.push(readAuthSession()));
    establishAuthSession('fictional-a', 71);
    clearAuthSession();
    assert.equal(observed.length, 2);
    assert.ok(observed[0]);
    assert.equal(observed[1], null);
    assert.equal(storage.getItem(investmentKey), 'investment-evidence');
    assert.equal(storage.getItem(importKey), 'import-evidence');
});

for (const mode of ['missing', 'no-request', 'reject', 'throw', 'getter-throw']) {
    test(`AI required-lock ${mode} does not execute an auth mutation`, async () => {
        fixture();
        establishAuthSession('fictional-a', 71);
        const before = readAuthSession();
        const unavailable = new Error('fictional lock boundary failure');
        const navigatorFixture = mode === 'getter-throw'
            ? Object.defineProperty({}, 'locks', { get: () => { throw unavailable; } })
            : { locks: mode === 'missing' ? undefined : mode === 'no-request' ? {} : {
                request: () => { if (mode === 'throw') throw unavailable; return Promise.reject(unavailable); },
            } };
        Object.defineProperty(globalThis, 'navigator', { configurable: true, value: navigatorFixture });
        let invoked = 0;
        await assert.rejects(withAuthSessionMutation(() => { invoked++; clearAuthSession(); }, true),
            (error: unknown) => error instanceof AiAuthSafetyError && error.code === 'AI_AUTH_SAFETY_UNAVAILABLE');
        assert.equal(invoked, 0);
        assert.deepEqual(readAuthSession(), before);
    });
}

test('non-AI callers retain absent-lock compatibility and lock errors; callback errors are not swallowed', async () => {
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {} });
    assert.equal(await withAuthSessionMutation(() => 'legacy result'), 'legacy result');
    const failure = new Error('fictional native rejection');
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { locks: { request: () => Promise.reject(failure) } } });
    await assert.rejects(withAuthSessionMutation(() => 'must not run'), error => error === failure);
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { locks: { request: (_name: string, _options: unknown, operation: () => unknown) => operation() } } });
    await assert.rejects(withAuthSessionMutation(() => { throw failure; }, true), error => error === failure);
});
