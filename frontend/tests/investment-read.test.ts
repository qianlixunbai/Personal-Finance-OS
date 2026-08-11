import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import {
    createCursorPageState,
    goToNextCursorPage,
    goToPreviousCursorPage,
    resetCursorPageState,
} from '../src/utils/investmentCursor.ts';
import {
    formatDecimalString,
    formatInvestmentMoney,
    formatInvestmentQuantity,
    investmentCorrectionStatusLabel,
    investmentFreshnessLabel,
    investmentReceiptApplicabilityLabel,
    investmentTransactionTypeLabel,
} from '../src/utils/investmentRead.ts';

test('formats financial strings without floating point conversion', () => {
    assert.equal(formatDecimalString('0'), '0');
    assert.equal(formatDecimalString('0.00'), '0.00');
    assert.equal(formatDecimalString('-0.01'), '-0.01');
    assert.equal(formatDecimalString('123456789012345678901234.56'), '123,456,789,012,345,678,901,234.56');
    assert.equal(formatDecimalString('-123456789012345678901234.56'), '-123,456,789,012,345,678,901,234.56');
    assert.equal(formatDecimalString('1.00000000'), '1.00000000');
    assert.equal(formatDecimalString('0.00000000'), '0.00000000');
    assert.equal(formatDecimalString(null), '暂无');
    assert.equal(formatDecimalString(undefined), '暂无');
    assert.equal(formatInvestmentMoney('123456789012345678901234.56', 'CNY'), '￥123,456,789,012,345,678,901,234.56');
    assert.equal(formatInvestmentMoney('-123456789012345678901234.56', 'CNY'), '￥-123,456,789,012,345,678,901,234.56');
    assert.equal(formatInvestmentMoney('0', 'CNY'), '￥0');
    assert.equal(formatInvestmentMoney(null, 'CNY'), '暂无');
    assert.equal(formatInvestmentQuantity('10.50000000'), '10.5');
    assert.equal(formatInvestmentQuantity('-0.00000000'), '0');
});

test('maps public investment labels without exposing backend-only wording', () => {
    assert.equal(investmentCorrectionStatusLabel('UNCHANGED'), '原始');
    assert.equal(investmentCorrectionStatusLabel('REVERSED'), '已冲正');
    assert.equal(investmentCorrectionStatusLabel('REPLACED'), '已替换');
    assert.equal(investmentTransactionTypeLabel('OPENING_POSITION'), '期初持仓');
    assert.equal(investmentTransactionTypeLabel('BUY'), '买入');
    assert.equal(investmentTransactionTypeLabel('SELL'), '卖出');
    assert.equal(investmentTransactionTypeLabel('DIVIDEND'), '分红');
    assert.equal(investmentFreshnessLabel('FRESH'), '最新');
    assert.equal(investmentFreshnessLabel('STALE'), '已过期');
    assert.equal(investmentFreshnessLabel('PARTIAL'), '部分可用');
    assert.equal(investmentFreshnessLabel('UNAVAILABLE'), '暂不可用');
    assert.equal(investmentReceiptApplicabilityLabel('POSTING_TIME'), '原始入账回执');
    assert.equal(investmentReceiptApplicabilityLabel('CORRECTION_FINAL'), '纠正完成回执');
    assert.equal(investmentReceiptApplicabilityLabel('NOT_APPLICABLE'), '不适用');
    assert.equal(investmentTransactionTypeLabel('REVERSAL' as never), '契约异常（REVERSAL）');
    assert.equal(investmentCorrectionStatusLabel('FUTURE_STATUS' as never), '未知纠正状态（FUTURE_STATUS）');
    assert.equal(investmentFreshnessLabel('FUTURE_FRESHNESS' as never), '未知估值状态（FUTURE_FRESHNESS）');
    assert.equal(investmentReceiptApplicabilityLabel('FUTURE_RECEIPT' as never), '未知回执适用性（FUTURE_RECEIPT）');
});

test('keeps opaque cursor history locally and resets it on filter changes', () => {
    const first = createCursorPageState();
    const second = goToNextCursorPage(first, 'opaque-from-page-one');
    const third = goToNextCursorPage(second, 'opaque-from-page-two');

    assert.deepEqual(second, { currentCursor: 'opaque-from-page-one', cursorHistory: [null] });
    assert.deepEqual(third, { currentCursor: 'opaque-from-page-two', cursorHistory: [null, 'opaque-from-page-one'] });
    assert.deepEqual(goToPreviousCursorPage(third), second);
    assert.deepEqual(resetCursorPageState(), first);
});

test('investment API wrapper uses only the contracted read paths', async () => {
    const source = await readFile(new URL('../src/api/investment.ts', import.meta.url), 'utf8');

    assert.match(source, /fetchInvestmentPortfolio[^\n]+['`]\/investment\/portfolio['`]/);
    assert.match(source, /fetchInvestmentPositions[^\n]+['`]\/investment\/positions['`]/);
    assert.match(source, /fetchInvestmentPosition[^\n]+`\/investment\/positions\/\$\{positionId\}`/);
    assert.match(source, /fetchInvestmentTransactions[^\n]+['`]\/investment\/transactions['`]/);
    assert.match(source, /fetchInvestmentTransaction[^\n]+`\/investment\/transactions\/\$\{logicalTransactionId\}`/);
    assert.match(source, /fetchInvestmentAuditTimeline[^\n]+`\/investment\/transactions\/\$\{logicalTransactionId\}\/audit-timeline`/);
    assert.doesNotMatch(source, /\/investments\//);
    assert.doesNotMatch(source, /\/audit['`]/);
});

test('labels every audit event and replacement fact with a safe unknown fallback', async () => {
    const helpers = await import('../src/utils/investmentRead.ts') as Record<string, unknown>;
    assert.equal(typeof helpers.investmentAuditEventLabel, 'function');
    assert.equal(typeof helpers.investmentAuditFactRoleLabel, 'function');
    const eventLabel = helpers.investmentAuditEventLabel as (value: string) => string;
    const factRoleLabel = helpers.investmentAuditFactRoleLabel as (value: string) => string;

    assert.equal(eventLabel('ORIGINAL_POSTING'), '原始入账');
    assert.equal(eventLabel('STANDALONE_REVERSAL'), '冲正');
    assert.equal(eventLabel('REPLACEMENT_COMMAND'), '替换操作');
    assert.equal(eventLabel('FUTURE_EVENT'), '未知审计事件（FUTURE_EVENT）');
    assert.equal(factRoleLabel('GROUPED_REVERSAL'), '内部冲正事实');
    assert.equal(factRoleLabel('REPLACEMENT_FACT'), '替换事实');
    assert.equal(factRoleLabel('FUTURE_FACT'), '未知审计事实（FUTURE_FACT）');
});

test('maps investment HTTP failures to stable user-facing messages', async () => {
    const helpers = await import('../src/utils/investmentRead.ts') as Record<string, unknown>;
    assert.equal(typeof helpers.getInvestmentReadErrorMessage, 'function');
    const message = helpers.getInvestmentReadErrorMessage as (error: unknown, context: 'filter' | 'detail' | 'general') => string;

    assert.equal(message({ response: { status: 400 } }, 'filter'), '筛选条件无效，请检查后重试');
    assert.equal(message({ response: { status: 404 } }, 'detail'), '该投资记录不存在或不可访问');
    assert.equal(message({ response: { status: 500, data: { message: 'stack trace' } } }, 'general'), '投资数据暂时无法读取，请稍后重试');
});

test('accepts results only from the latest detail request', async () => {
    const cursor = await import('../src/utils/investmentCursor.ts') as Record<string, unknown>;
    assert.equal(typeof cursor.createLatestRequestGate, 'function');
    const createGate = cursor.createLatestRequestGate as () => { begin: () => number; isCurrent: (requestId: number) => boolean; invalidate: () => void };
    const gate = createGate();
    const first = gate.begin();
    const second = gate.begin();

    assert.equal(gate.isCurrent(first), false);
    assert.equal(gate.isCurrent(second), true);
    gate.invalidate();
    assert.equal(gate.isCurrent(second), false);
});

test('keeps nullable receipt DTOs and omits an inapplicable correction receipt card', async () => {
    const types = await readFile(new URL('../src/types/investment.ts', import.meta.url), 'utf8');
    const page = await readFile(new URL('../src/pages/Investments.tsx', import.meta.url), 'utf8');

    assert.match(types, /postingReceipt:\s*InvestmentReceipt\s*\|\s*null/);
    assert.match(types, /factType:\s*string\s*\|\s*null/);
    assert.match(page, /detail\.correctionFinalReceipt\s*&&\s*<ReceiptCard title="纠正完成回执"/);
});
