import api from './index.ts';
import type { CommandAwareRequestConfig } from './index.ts';
import { readAuthSession, type AuthSession } from '../utils/authSession.ts';

interface AiAskResponseEnvelope {
    code: number;
    message: string;
    data: {
        answer: string;
    };
}

export async function askFinanceAi(question: string, session: AuthSession | null = readAuthSession()): Promise<string> {
    const config: CommandAwareRequestConfig = { aiSession: session };
    const response = await api.post<AiAskResponseEnvelope>('/ai/ask', { question }, config);
    const answer: unknown = response.data.data?.answer;
    if (typeof answer !== 'string') {
        throw new Error('AI 返回结果格式无效');
    }
    return answer;
}
