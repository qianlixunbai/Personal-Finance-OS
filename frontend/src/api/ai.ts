import api from './index.ts';

interface AiAskResponseEnvelope {
    code: number;
    message: string;
    data: {
        answer: string;
    };
}

export async function askFinanceAi(question: string): Promise<string> {
    const response = await api.post<AiAskResponseEnvelope>('/ai/ask', { question });
    const answer: unknown = response.data.data?.answer;
    if (typeof answer !== 'string') {
        throw new Error('AI 返回结果格式无效');
    }
    return answer;
}
