import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { askFinanceAi } from '../api/ai';
import { AlertMessage, EmptyState } from '../components/Feedback';
import { PageHeader } from '../components/PageHeader';
import { getErrorMessage } from '../utils/error';

const maxQuestionLength = 3000;
const suggestedQuestions = [
    '我的财务概览怎么样？',
    '我这个月的现金流怎么样？',
    '我的投资组合现在是什么情况？',
];

type RequestStatus = 'idle' | 'loading' | 'success' | 'error';
interface SuccessfulAnswer {
    question: string;
    answer: string;
}

export default function AiAnalyst() {
    const [question, setQuestion] = useState('');
    const [status, setStatus] = useState<RequestStatus>('idle');
    const [error, setError] = useState('');
    const [lastSuccess, setLastSuccess] = useState<SuccessfulAnswer | null>(null);
    const requestLock = useRef(false);

    const submitQuestion = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (requestLock.current) return;

        const submittedQuestion = question.trim();
        if (!submittedQuestion) return;
        if (submittedQuestion.length > maxQuestionLength) {
            setError('问题最多 3000 个字符，请缩短后重试。');
            setStatus('error');
            return;
        }

        requestLock.current = true;
        setError('');
        setStatus('loading');
        try {
            const answer = await askFinanceAi(submittedQuestion);
            setLastSuccess({ question: submittedQuestion, answer });
            setStatus('success');
        } catch (requestError) {
            setError(getErrorMessage(requestError, fallbackMessage(requestError)));
            setStatus('error');
        } finally {
            requestLock.current = false;
        }
    };

    return <div className="ai-analyst">
        <PageHeader title="AI 财务分析" subtitle="基于当前账户、交易和投资数据进行只读分析。" />

        <p className="ai-analyst__note">AI 回答基于系统当前只读财务数据生成，不会修改账户、交易、持仓或余额。每次分析独立进行，不会携带历史对话。</p>

        <form className="page-panel ai-analyst__composer" onSubmit={submitQuestion}>
            <label className="field" htmlFor="ai-analyst-question">
                <span className="field__label">问题</span>
                <textarea
                    id="ai-analyst-question"
                    className="field__control ai-analyst__textarea"
                    value={question}
                    maxLength={maxQuestionLength}
                    rows={5}
                    disabled={status === 'loading'}
                    onChange={event => setQuestion(event.target.value)}
                    aria-describedby="ai-analyst-question-count"
                />
            </label>
            <div className="ai-analyst__composer-footer">
                <span className="ai-analyst__count" id="ai-analyst-question-count">{question.length} / {maxQuestionLength}</span>
                <button className="button button--primary" type="submit" disabled={status === 'loading' || !question.trim()}>
                    {status === 'loading' ? '正在分析…' : '提交问题'}
                </button>
            </div>
        </form>

        <section className="ai-analyst__suggestions" aria-labelledby="ai-analyst-suggestions-heading">
            <h2 className="ai-analyst__suggested-heading" id="ai-analyst-suggestions-heading">你可以这样提问</h2>
            {suggestedQuestions.map(suggestion => <button
                className="button button--secondary"
                key={suggestion}
                type="button"
                disabled={status === 'loading'}
                onClick={() => setQuestion(suggestion)}
            >{suggestion}</button>)}
        </section>

        {status === 'loading' && <p className="ai-analyst__status" role="status">{lastSuccess ? '正在重新分析…' : '正在分析…'}</p>}
        {error && <AlertMessage type="error">{error}</AlertMessage>}

        <section className="content-card ai-analyst__result" aria-labelledby="ai-answer-heading">
            <h2 className="content-card__title" id="ai-answer-heading">分析结果</h2>
            {lastSuccess ? <>
                {status === 'loading' && <p className="ai-analyst__status">正在分析新问题，以下仍是上一次成功分析的结果。</p>}
                {status === 'error' && <p className="ai-analyst__status">本次分析未成功，以下仍是上一次成功分析的结果。</p>}
                <p className="ai-analyst__question"><strong>问题：</strong>{lastSuccess.question}</p>
                <div className="ai-analyst__answer">{lastSuccess.answer}</div>
            </> : <EmptyState message={status === 'error' ? '本次分析未生成回答，请检查问题后重试。' : '提交问题后，分析结果会显示在这里。'} />}
        </section>
    </div>;
}

function fallbackMessage(error: unknown) {
    const status = (error as { response?: { status?: number } } | null)?.response?.status;
    switch (status) {
        case 400: return '问题无效，请检查问题后重试。';
        case 429: return 'AI 请求过于频繁，请稍后再试。';
        case 502: return 'AI 服务返回异常，请稍后再试。';
        case 503: return 'AI 服务暂时不可用，请稍后再试。';
        case 500: return '服务器内部错误，请稍后再试。';
        default: return 'AI 分析失败，请稍后重试。';
    }
}
