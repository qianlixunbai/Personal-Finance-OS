import type { InvestmentCommandDraft } from '../types/investmentCommand.ts';

type Row = [label: string, value: string];
type Comparison = [label: string, before: string, after: string];
export interface PositionConfirmationContext { instrumentName: string; instrumentSymbol: string; accountName: string; currentQuantity: string | null; }
export interface TransactionConfirmationContext {
    instrumentName: string; instrumentSymbol: string; accountName: string; transactionType: string; effectiveTradeTime: string;
    original: { quantity: string | null; unitPrice: string | null; grossAmount: string | null; feeAmount: string | null; taxAmount: string | null; externalReference: string | null; note: string | null; };
}

function display(value: string | null | undefined) { return value && value.length > 0 ? value : '不适用'; }
function identity(name: string, symbol: string) { return `${name}（${symbol}）`; }

export function buildCommandConfirmation(draft: InvestmentCommandDraft, position?: PositionConfirmationContext, transaction?: TransactionConfirmationContext) {
    const rows: Row[] = [];
    let notice = '请核对本次手工记账事实；总额、现金影响、成本、盈亏与最终持仓由后端计算。';
    let comparisons: Comparison[] | undefined;
    if (position) rows.push(['标的', identity(position.instrumentName, position.instrumentSymbol)], ['账户', position.accountName]);
    if (draft.commandType === 'SELL') {
        rows.push(['当前持仓数量', display(position?.currentQuantity)], ['本次卖出数量', draft.quantity], ['单价', draft.unitPrice], ['费用', draft.feeAmount], ['税费', draft.taxAmount]);
        notice = '最终持仓、释放成本、已实现盈亏和账户余额由后端计算。';
    } else if (draft.commandType === 'REVERSAL' && transaction) {
        rows.push(['原交易类型', transaction.transactionType], ['标的', identity(transaction.instrumentName, transaction.instrumentSymbol)], ['账户', transaction.accountName]);
        if (transaction.original.quantity !== null) rows.push(['原数量', transaction.original.quantity]);
        if (transaction.original.unitPrice !== null) rows.push(['原单价', transaction.original.unitPrice]);
        if (transaction.original.grossAmount !== null) rows.push(['原总额', transaction.original.grossAmount]);
        rows.push(['原费用', display(transaction.original.feeAmount)], ['原税费', display(transaction.original.taxAmount)], ['生效交易时间', transaction.effectiveTradeTime], ['纠正原因', draft.reason]);
        notice = '原记录不会删除；系统会追加不可变冲正事实，并继续保留完整审计时间线。';
    } else if (draft.commandType === 'REPLACEMENT' && transaction) {
        rows.push(['原交易类型', transaction.transactionType], ['更正请求类型', draft.replacementRequestType ?? '未知'], ['标的', identity(transaction.instrumentName, transaction.instrumentSymbol)], ['账户', transaction.accountName], ['更正原因', draft.reason]);
        const original = transaction.original;
        comparisons = transaction.transactionType === 'DIVIDEND'
            ? [['分红总额', display(original.grossAmount), draft.grossAmount], ['费用', display(original.feeAmount), draft.feeAmount], ['税费', display(original.taxAmount), draft.taxAmount], ['外部参考号', display(original.externalReference), display(draft.externalReference)], ['备注', display(original.note), display(draft.note)]]
            : [['数量', display(original.quantity), draft.quantity], ['单价', display(original.unitPrice), draft.unitPrice], ['费用', display(original.feeAmount), draft.feeAmount], ['税费', display(original.taxAmount), draft.taxAmount], ['外部参考号', display(original.externalReference), display(draft.externalReference)], ['备注', display(original.note), display(draft.note)]];
        notice = '原记录不会修改；系统会追加 correction facts，logical transaction 将展示更正后的有效业务值。';
    } else {
        if (draft.commandType === 'DIVIDEND') rows.push(['分红总额', draft.grossAmount], ['费用', draft.feeAmount], ['税费', draft.taxAmount], ['外部参考号', display(draft.externalReference)], ['备注', display(draft.note)]);
        else rows.push(['数量', draft.quantity], ['单价', draft.unitPrice], ['费用', draft.feeAmount], ['税费', draft.taxAmount]);
    }
    return { rows, comparisons, notice };
}
