import assert from 'node:assert/strict';
import test from 'node:test';

import {
    buildColumnMappings,
    collectMappingValues,
    normalizeSourceValue,
    validateColumnSelection,
    validateValueMappings,
} from '../src/utils/transactionImportMapping.ts';
import {
    clearTransactionImportDraft,
    consumeTransactionImportReceiptReturn,
    readTransactionImportDraft,
    transactionImportDraftKey,
    writeTransactionImportReceiptReturn,
    writeTransactionImportDraft,
} from '../src/utils/transactionImportStorage.ts';
import api from '../src/api/index.ts';
import { cancelTransactionImport, fetchTransactionImportRows, updateTransactionImportMapping, uploadTransactionImport } from '../src/api/transactionImport.ts';
import { collectWarningMetadata, isLastPreviewPage, previewRowStatus, requiredWarningIds } from '../src/utils/transactionImportPreview.ts';

class MemoryStorage implements Storage {
    private readonly values = new Map<string, string>();
    get length() { return this.values.size; }
    clear() { this.values.clear(); }
    getItem(key: string) { return this.values.get(key) ?? null; }
    key(index: number) { return [...this.values.keys()][index] ?? null; }
    removeItem(key: string) { this.values.delete(key); }
    setItem(key: string, value: string) { this.values.set(key, value); }
}

const columns = ['日期', '类型', '金额', '账户', '分类', '说明'];
const selectedColumns = { date: '日期', time: '', type: '类型', amount: '金额', account: '账户', category: '分类', description: '说明', currency: '' };

test('builds the backend canonical-target mapping and explicitly ignores unused columns', () => {
    assert.deepEqual(buildColumnMappings(columns, selectedColumns), {
        date: '日期', type: '类型', amount: '金额', account: '账户', category: '分类', description: '说明',
    });
    assert.deepEqual(buildColumnMappings([...columns, '备注'], selectedColumns), {
        date: '日期', type: '类型', amount: '金额', account: '账户', category: '分类', description: '说明', 备注: 'IGNORE',
    });
});

test('rejects missing required and duplicate source-column selections before mapping discovery', () => {
    assert.equal(validateColumnSelection(columns, { ...selectedColumns, amount: '' }), '请为金额选择来源列');
    assert.equal(validateColumnSelection(columns, { ...selectedColumns, category: '账户' }), '同一来源列不能映射到多个字段');
    assert.equal(validateColumnSelection(columns, { ...selectedColumns, date: '不存在' }), '所选来源列已不在当前文件中');
    assert.equal(validateColumnSelection(columns, selectedColumns), null);
});

test('uses NFC plus trim only to deduplicate discovered source values and keeps a representative display value', () => {
    assert.equal(normalizeSourceValue('  Caf\u00e9  '), 'Caf\u00e9');
    assert.equal(normalizeSourceValue('Cafe\u0301'), 'Caf\u00e9');
    const values = collectMappingValues([
        { sourceValues: { 类型: ' expense ', 账户: '  现金 ', 分类: '餐饮' } },
        { sourceValues: { 类型: 'EXPENSE', 账户: '现金', 分类: ' 餐饮 ' } },
        { sourceValues: { 类型: 'income', 账户: '工资卡', 分类: '工资' } },
    ], selectedColumns);
    assert.deepEqual(values, { types: ['expense', 'EXPENSE', 'income'], accounts: ['现金', '工资卡'], categories: ['餐饮', '工资'] });
});

test('fails closed when required type, account or category mappings are incomplete or category source spans income and expense', () => {
    const values = { types: ['收入', '支出'], accounts: ['现金'], categories: ['餐饮'] };
    assert.equal(validateValueMappings(values, { 收入: 'INCOME' }, { 现金: 1 }, { 餐饮: 8 }), '请完成所有类型映射');
    assert.equal(validateValueMappings(values, { 收入: 'INCOME', 支出: 'EXPENSE' }, {}, { 餐饮: 8 }), '请完成所有账户映射');
    assert.equal(validateValueMappings(values, { 收入: 'INCOME', 支出: 'EXPENSE' }, { 现金: 1 }, {}), '请完成所有分类映射');
    assert.equal(validateValueMappings(values, { 收入: 'INCOME', 支出: 'EXPENSE' }, { 现金: 1 }, { 餐饮: 8 }, { 餐饮: ['INCOME', 'EXPENSE'] }), '同一分类来源值同时用于收入和支出，请在源文件中区分后重新上传');
    assert.equal(validateValueMappings(values, { 收入: 'INCOME', 支出: 'EXPENSE' }, { 现金: 1 }, { 餐饮: 8 }, { 餐饮: ['EXPENSE'] }), null);
});

test('persists only a same-user minimal draft and never restores a foreign or malformed snapshot', () => {
    const storage = new MemoryStorage();
    const snapshot = {
        schemaVersion: 1 as const, userId: 7, activeStep: 'MAPPING' as const, file: { name: 'demo.csv', size: 20, type: 'text/csv' },
        sessionId: '9c7fcbe8-c16b-4d61-a7f4-521a6bb6d091', batchId: null, sessionStatus: 'MAPPING_REQUIRED', revision: 1,
        detectedColumns: columns, mapping: null, summary: null, fileDigest: 'file', mappingDigest: null, optionsDigest: 'options', normalizedRowsDigest: null,
        expiresAt: '2026-08-24T10:00:00.000Z', previewToken: null, updatedAt: '2026-08-24T09:00:00.000Z',
    };
    writeTransactionImportDraft(storage, snapshot);
    assert.equal(transactionImportDraftKey(7), 'finance-os:transaction-import:draft:v1:7');
    assert.deepEqual(readTransactionImportDraft(storage, 7), snapshot);
    assert.equal(readTransactionImportDraft(storage, 8), null);
    storage.setItem(transactionImportDraftKey(7), '{broken');
    assert.equal(readTransactionImportDraft(storage, 7), null);
});

test('clears only the logging-out user import draft', () => {
    const storage = new MemoryStorage();
    storage.setItem(transactionImportDraftKey(7), '{"userId":7}');
    storage.setItem(transactionImportDraftKey(8), '{"userId":8}');
    clearTransactionImportDraft(storage, 7);
    assert.equal(storage.getItem(transactionImportDraftKey(7)), null);
    assert.equal(storage.getItem(transactionImportDraftKey(8)), '{"userId":8}');
});

test('persists only a same-user Receipt route identity across authentication, never receipt data', () => {
    const storage = new MemoryStorage();
    writeTransactionImportReceiptReturn(storage, 7, '0a7fcbe8-c16b-4d61-a7f4-521a6bb6d091');
    assert.deepEqual(JSON.parse(storage.getItem('finance-os:transaction-import:receipt-return:v1') ?? '{}'), { schemaVersion: 1, userId: 7, batchId: '0a7fcbe8-c16b-4d61-a7f4-521a6bb6d091' });
    assert.equal(consumeTransactionImportReceiptReturn(storage, 8), null);
    assert.equal(storage.getItem('finance-os:transaction-import:receipt-return:v1'), null);
    writeTransactionImportReceiptReturn(storage, 7, '0a7fcbe8-c16b-4d61-a7f4-521a6bb6d091');
    assert.equal(consumeTransactionImportReceiptReturn(storage, 7), '/transactions/import/receipts/0a7fcbe8-c16b-4d61-a7f4-521a6bb6d091');
    assert.equal(consumeTransactionImportReceiptReturn(storage, 7), null);
});

test('fails closed for stale transaction-import snapshot identities and incompatible versions', () => {
    const valid = {
        schemaVersion: 1 as const, userId: 7, activeStep: 'PREVIEW_READY' as const, file: null,
        sessionId: '9c7fcbe8-c16b-4d61-a7f4-521a6bb6d091', batchId: null, sessionStatus: 'PREVIEW_READY', revision: 1,
        detectedColumns: [], mapping: null, summary: null, fileDigest: 'file', mappingDigest: null, optionsDigest: 'options', normalizedRowsDigest: null,
        expiresAt: '2026-12-31T23:59:59.000Z', previewToken: 'opaque', updatedAt: '2026-08-24T09:00:00.000Z',
    };
    const storage = new MemoryStorage();
    for (const patch of [{ schemaVersion: 2 }, { sessionId: 'not-a-session' }, { revision: 0 }, { userId: 8 }, { previewToken: '' }]) {
        storage.setItem(transactionImportDraftKey(7), JSON.stringify({ ...valid, ...patch }));
        assert.equal(readTransactionImportDraft(storage, 7), null);
    }
});

test('uses only the contracted multipart and rows endpoints, never a confirm endpoint', async () => {
    const calls: Array<{ method: string; path: string; body?: unknown; params?: unknown }> = [];
    const post = api.post; const put = api.put; const get = api.get; const remove = api.delete;
    api.post = async (path, body) => { calls.push({ method: 'POST', path, body }); return { data: { data: { importSessionId: 'session' } } }; };
    api.put = async (path, body) => { calls.push({ method: 'PUT', path, body }); return { data: { data: { importSessionId: 'session' } } }; };
    api.get = async (path, config) => { calls.push({ method: 'GET', path, params: config?.params }); return { data: { data: [] } }; };
    api.delete = async path => { calls.push({ method: 'DELETE', path }); return { data: { data: null } }; };
    try {
        await uploadTransactionImport(new File(['a'], 'demo.csv', { type: 'text/csv' }));
        await updateTransactionImportMapping('session', { columnMappings: {}, typeMappings: {}, accountMappings: {}, categoryMappings: {} });
        await fetchTransactionImportRows('session', 2, 500);
        await cancelTransactionImport('session');
    } finally { api.post = post; api.put = put; api.get = get; api.delete = remove; }
    assert.deepEqual(calls.map(call => [call.method, call.path]), [
        ['POST', '/imports/transactions/preview'], ['PUT', '/imports/transactions/session/preview'],
        ['GET', '/imports/transactions/session/rows'], ['DELETE', '/imports/transactions/session'],
    ]);
    assert.deepEqual(calls[2].params, { page: 2, size: 500 });
    assert.equal(calls.some(call => call.path.includes('/confirm')), false);
    const uploadBody = calls[0].body as FormData;
    assert.equal(uploadBody.get('file') instanceof File, true);
    assert.equal((await (uploadBody.get('request') as Blob).text()), '{"format":"AUTO","mapping":null}');
    const mappingBody = calls[1].body as FormData;
    assert.equal((await (mappingBody.get('mapping') as Blob).text()), '{"columnMappings":{},"typeMappings":{},"accountMappings":{},"categoryMappings":{}}');
});

test('projects backend row severity without recalculating validation and keeps every backend warning ID', () => {
    const rows = [{
        rowNumber: 8, sourceValues: {}, normalizedValues: {}, mappingStatus: 'INVALID', duplicateStatus: 'DATABASE_PROBABLE', importable: false,
        errors: [{ id: 'error-1', code: 'INVALID_AMOUNT', scope: 'ROW', field: 'amount', rowNumber: 8, message: '金额格式无效', retryable: false }],
        warnings: [
            { id: 'warning-b', code: 'DATABASE_PROBABLE', scope: 'ROW', field: null, rowNumber: 8, message: '疑似重复', retryable: false },
            { id: 'warning-a', code: 'IN_FILE_PROBABLE', scope: 'ROW', field: null, rowNumber: 8, message: '文件内疑似重复', retryable: false },
        ],
    }, {
        rowNumber: 9, sourceValues: {}, normalizedValues: {}, mappingStatus: 'MAPPED', duplicateStatus: 'NONE', importable: true,
        errors: [], warnings: [{ id: 'warning-b', code: 'DATABASE_PROBABLE', scope: 'ROW', field: null, rowNumber: 9, message: '疑似重复', retryable: false }],
    }];
    assert.equal(previewRowStatus(rows[0]), 'ERROR');
    assert.equal(previewRowStatus(rows[1]), 'WARNING');
    assert.deepEqual(collectWarningMetadata(rows, 1), [
        { id: 'warning-a', code: 'IN_FILE_PROBABLE', rowNumber: 8, field: null, message: '文件内疑似重复', page: 1 },
        { id: 'warning-b', code: 'DATABASE_PROBABLE', rowNumber: 8, field: null, message: '疑似重复', page: 1 },
    ]);
});

test('uses the contracted short-page termination and requires every warning group acknowledgement', () => {
    assert.equal(isLastPreviewPage(499, 500), true);
    assert.equal(isLastPreviewPage(500, 500), false);
    const warnings = [
        { id: 'warning-a', code: 'IN_FILE_PROBABLE', rowNumber: 8, field: null, message: 'a', page: 1 },
        { id: 'warning-b', code: 'DATABASE_PROBABLE', rowNumber: 9, field: null, message: 'b', page: 1 },
    ];
    assert.deepEqual(requiredWarningIds(warnings, new Set(['warning-b'])), []);
    assert.deepEqual(requiredWarningIds(warnings, new Set(['warning-a', 'warning-b'])), ['warning-a', 'warning-b']);
});
