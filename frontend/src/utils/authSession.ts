// Local lifecycle identity only; never used as a server credential.
export const authSessionKey = 'finance-os:auth-session:v1';
export const authSessionMutationLock = 'finance-os:auth-session-mutation:v1';
const userIdKey = 'finance-os:auth-user-id:v1';
const changing = 'changing';
const changedEvent = 'finance-os:auth-session-changed';

// Keep the current check and all credential writes/redirects in the same
// cross-tab critical section. Only non-AI callers may use compatibility mode.
export class AiAuthSafetyError extends Error {
    readonly code = 'AI_AUTH_SAFETY_UNAVAILABLE';

    constructor(cause?: unknown) {
        super('当前环境无法安全保护 AI 会话，本次分析已停止。请使用新版浏览器或稍后重试。', { cause });
        this.name = 'AiAuthSafetyError';
    }
}

export async function withAuthSessionMutation<T>(operation: () => T | Promise<T>, requireLock = false): Promise<T> {
    let entered = false;
    try {
        const locks = navigator.locks;
        if (typeof locks?.request !== 'function') {
            if (requireLock) throw new AiAuthSafetyError();
            return operation();
        }
        const result = await locks.request(authSessionMutationLock, { mode: 'exclusive' }, () => {
            entered = true;
            return operation();
        });
        if (requireLock && !entered) throw new AiAuthSafetyError();
        return result;
    } catch (error) {
        // Preserve operation errors (including stale-session cancellation).
        // Lock acquisition failure must never invoke the AI operation unlocked.
        if (requireLock && !entered && !(error instanceof AiAuthSafetyError)) throw new AiAuthSafetyError(error);
        throw error;
    }
}

export interface AuthSession {
    id: string | null;
    token: string;
    userId: string;
}

export function readAuthSession(): AuthSession | null {
    const id = localStorage.getItem(authSessionKey);
    const token = localStorage.getItem('token');
    const userId = localStorage.getItem(userIdKey);
    // A missing nonce supports sessions created before C6. Every new login
    // gets a fresh nonce, including logins that return an identical JWT.
    if (id === changing || !token || !userId || !Number.isSafeInteger(Number(userId)) || Number(userId) <= 0) return null;
    if (id !== localStorage.getItem(authSessionKey)
        || token !== localStorage.getItem('token') || userId !== localStorage.getItem(userIdKey)) return null;
    return { id, token, userId };
}

export function sameAuthSession(a: AuthSession | null, b: AuthSession | null): boolean {
    return a === b || !!a && !!b && a.id === b.id && a.token === b.token && a.userId === b.userId;
}

export function isCurrentAuthSession(session: AuthSession | null): boolean {
    return !!session && sameAuthSession(session, readAuthSession());
}

export function establishAuthSession(token: string, userId: number): void {
    const id = crypto.randomUUID();
    localStorage.setItem(authSessionKey, changing);
    localStorage.setItem('token', token);
    localStorage.setItem(userIdKey, String(userId));
    localStorage.setItem(authSessionKey, id);
    window.dispatchEvent(new Event(changedEvent));
}

export function clearAuthSession(): void {
    localStorage.setItem(authSessionKey, changing);
    localStorage.removeItem('token');
    localStorage.removeItem(userIdKey);
    localStorage.removeItem(authSessionKey);
    window.dispatchEvent(new Event(changedEvent));
}

export function subscribeAuthSessionChange(check: () => void): () => void {
    const onStorage = (event: StorageEvent) => {
        if (event.storageArea === localStorage
            && (event.key === null || [authSessionKey, 'token', userIdKey].includes(event.key))) check();
    };
    window.addEventListener('storage', onStorage);
    window.addEventListener(changedEvent, check);
    window.addEventListener('focus', check);
    document.addEventListener('visibilitychange', check);
    return () => {
        window.removeEventListener('storage', onStorage);
        window.removeEventListener(changedEvent, check);
        window.removeEventListener('focus', check);
        document.removeEventListener('visibilitychange', check);
    };
}
