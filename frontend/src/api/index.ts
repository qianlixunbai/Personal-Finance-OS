import axios from 'axios';
import type { AxiosRequestConfig } from 'axios';
import type { MarketQuoteRefreshResponse } from '../types/market-data';
import type { ReferenceValuationResponse } from '../types/reference-valuation';
import type { PendingTransactionImportV1 } from '../types/transactionImport';
import { writePendingTransactionImport } from '../utils/transactionImportStorage.ts';
import { AiAuthSafetyError, clearAuthSession, isCurrentAuthSession, readAuthSession, withAuthSessionMutation, type AuthSession } from '../utils/authSession.ts';

const api = axios.create({
    baseURL: '/api/v1',
});

export type CommandAwareRequestConfig = AxiosRequestConfig & {
    investmentCommandUnauthorized?: () => void;
    transactionImportUnauthorized?: () => void;
    transactionImportAuthRecord?: PendingTransactionImportV1;
    skipGlobal401Redirect?: boolean;
    aiSession?: AuthSession | null;
    aiSentSession?: AuthSession;
};

function isPublicAuthRequest(url?: string) {
    const path = url?.split('?')[0];
    return path === '/login'
        || path === '/register'
        || path?.endsWith('/api/v1/login')
        || path?.endsWith('/api/v1/register');
}

function isAiAskRequest(config?: AxiosRequestConfig) {
    if (!config?.url) return false;
    try {
        const path = decodeURIComponent(new URL(api.getUri(config), 'http://finance-os.local/').pathname);
        return path === '/api/v1/ai/ask';
    } catch { return false; }
}

api.interceptors.request.use(async (config) => {
    const aiConfig = config as typeof config & CommandAwareRequestConfig;
    if (isAiAskRequest(config)) {
        // Direct AI client calls cannot bypass the boundary by omitting config.
        const session = 'aiSession' in aiConfig ? aiConfig.aiSession : readAuthSession();
        aiConfig.aiSession = session;
        try {
            return await withAuthSessionMutation(() => {
                if (!isCurrentAuthSession(session ?? null)) {
                    throw new axios.CanceledError('AI 请求所属会话已失效', config);
                }
                // Bind the exact validated identity, never a later token read.
                config.headers.Authorization = `Bearer ${session!.token}`;
                aiConfig.aiSentSession = { ...session! };
                return config;
            }, true);
        } catch (error) {
            if (error instanceof AiAuthSafetyError) Object.assign(error, { config });
            throw error;
        }
    }
    const token = localStorage.getItem('token');
    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
});

api.interceptors.response.use(
    (res) => res,
    async (err) => {
        const token = localStorage.getItem('token');
        const commandConfig = err.config as CommandAwareRequestConfig | undefined;
        if (err.response?.status === 401 && token && !isPublicAuthRequest(err.config?.url)) {
            const aiRequest = isAiAskRequest(err.config);
            try {
                await withAuthSessionMutation(() => {
                    if (aiRequest && (!isCurrentAuthSession(commandConfig?.aiSentSession ?? null)
                        || err.config.headers?.Authorization !== `Bearer ${commandConfig?.aiSentSession?.token}`)) {
                        return;
                    }
                    commandConfig?.investmentCommandUnauthorized?.();
                    commandConfig?.transactionImportUnauthorized?.();
                    if (commandConfig?.transactionImportAuthRecord) writePendingTransactionImport(localStorage, commandConfig.transactionImportAuthRecord);
                    clearAuthSession();
                    if (commandConfig?.skipGlobal401Redirect) return;
                    if (window.location.pathname !== '/login') {
                        window.location.href = '/login';
                    }
                }, aiRequest);
            } catch (error) {
                if (aiRequest && error instanceof AiAuthSafetyError) {
                    // Keep the original Axios failure as cause, with no auth effects.
                    return Promise.reject(Object.assign(error, { config: err.config, cause: err }));
                }
                throw error;
            }
        }
        return Promise.reject(err);
    }
);

export default api;

export async function refreshAssetQuote(assetId: number): Promise<MarketQuoteRefreshResponse> {
    const response = await api.post(`/assets/${assetId}/quote/refresh`);
    return response.data.data as MarketQuoteRefreshResponse;
}

export async function refreshAssetReferenceValuation(assetId: number): Promise<ReferenceValuationResponse> {
    const response = await api.post(`/assets/${assetId}/reference-valuation/refresh`);
    return response.data.data as ReferenceValuationResponse;
}
