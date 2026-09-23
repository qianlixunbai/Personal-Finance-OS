import assert from 'node:assert/strict';
import test from 'node:test';

import {
    readTransactionImportDraft,
    transactionImportDraftKey,
    writeTransactionImportDraft,
} from '../src/utils/transactionImportStorage.ts';

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

test('restores only a valid same-user import draft', () => {
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
    for (const patch of [{ schemaVersion: 2 }, { userId: 8 }, { sessionId: 'not-a-session' }, { activeStep: 'PREVIEW_READY', previewToken: '' }]) {
        storage.setItem(transactionImportDraftKey(7), JSON.stringify({ ...snapshot, ...patch }));
        assert.equal(readTransactionImportDraft(storage, 7), null);
    }
});
