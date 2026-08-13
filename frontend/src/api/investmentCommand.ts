import api, { type CommandAwareRequestConfig } from './index';
import type { InvestmentCommandReceipt } from '../types/investmentCommand';

type Envelope<T> = { data: T };
export interface CommandTransportOptions { onUnauthorized: () => void; }

export async function postInvestmentCommand(path: string, bodyJson: string, idempotencyKey: string, options: CommandTransportOptions): Promise<InvestmentCommandReceipt & { assetId: number; logicalTransactionId: number; }> {
    const config: CommandAwareRequestConfig = {
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
        transformRequest: [(data) => data],
        investmentCommandUnauthorized: options.onUnauthorized,
        skipGlobal401Redirect: false,
    };
    const response = await api.post<Envelope<Record<string, unknown>>>(path, bodyJson, config);
    const data = response.data.data;
    const logicalTransactionId = Number(data.originalTransactionId ?? data.transactionId);
    const assetId = Number(data.assetId);
    if (!Number.isInteger(assetId) || assetId <= 0 || !Number.isInteger(logicalTransactionId) || logicalTransactionId <= 0) throw new Error('命令回执缺少必要的刷新目标。');
    return {
        commandType: 'BUY', transactionType: typeof data.transactionType === 'string' ? data.transactionType : undefined,
        cashDelta: typeof data.commandCashDelta === 'string' ? data.commandCashDelta : typeof data.cashDelta === 'string' ? data.cashDelta : undefined,
        balanceAfter: typeof data.balanceAfter === 'string' ? data.balanceAfter : undefined,
        createdAt: typeof data.createdAt === 'string' ? data.createdAt : undefined,
        idempotentReplay: data.idempotentReplay === true, assetId, logicalTransactionId,
    };
}
