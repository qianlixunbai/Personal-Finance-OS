function dataFrom(error: unknown): { message?: unknown; errorCode?: unknown } | undefined {
    const data = (error as { response?: { data?: unknown } }).response?.data;
    if (typeof data === 'object' && data !== null) return data as { message?: unknown; errorCode?: unknown };
    if (typeof data === 'string') { try { const value: unknown = JSON.parse(data); return typeof value === 'object' && value !== null ? value as { message?: unknown; errorCode?: unknown } : undefined; } catch { return undefined; } }
    return undefined;
}

export function getErrorMessage(error: unknown, fallback: string) {
    const message = dataFrom(error)?.message;
    return typeof message === 'string' && message.trim() ? message : fallback;
}

export function getErrorCode(error: unknown) {
    const value = dataFrom(error)?.errorCode;
    return typeof value === 'string' ? value : undefined;
}
