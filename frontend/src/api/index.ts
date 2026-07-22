import axios from 'axios';
import type { MarketQuoteRefreshResponse } from '../types/market-data';
import type { ReferenceValuationResponse } from '../types/reference-valuation';

const api = axios.create({
    baseURL: '/api/v1',
});

function isPublicAuthRequest(url?: string) {
    const path = url?.split('?')[0];
    return path === '/login'
        || path === '/register'
        || path?.endsWith('/api/v1/login')
        || path?.endsWith('/api/v1/register');
}

api.interceptors.request.use((config) => {
    const token = localStorage.getItem('token');
    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
});

api.interceptors.response.use(
    (res) => res,
    (err) => {
        const token = localStorage.getItem('token');
        if (err.response?.status === 401 && token && !isPublicAuthRequest(err.config?.url)) {
            localStorage.removeItem('token');
            if (window.location.pathname !== '/login') {
                window.location.href = '/login';
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
