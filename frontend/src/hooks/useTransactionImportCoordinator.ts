import { useCallback, useEffect, useMemo, useState } from 'react';
import { cancelTransactionImport, fetchTransactionImportOptions, fetchTransactionImportRows, updateTransactionImportMapping, uploadTransactionImport } from '../api/transactionImport';
import type { DiscoveredMappingValues, ImportAccountOption, ImportCategoryOption, ImportTransactionType, TransactionImportColumnSelection, TransactionImportDraftSnapshot, TransactionImportMapping, TransactionImportPreviewResponse } from '../types/transactionImport';
import { buildMapping, collectMappingValues, emptyColumnSelection, normalizeSourceValue, validateColumnSelection, validateValueMappings } from '../utils/transactionImportMapping';
import { clearTransactionImportDraft, readTransactionImportDraft, writeTransactionImportDraft } from '../utils/transactionImportStorage';
import { getErrorMessage } from '../utils/error';

const maximumFileSize = 5 * 1024 * 1024;
const pageSize = 500;
const maximumDiscoveryPages = 20;

function currentUserId() {
    const value = Number(localStorage.getItem('finance-os:auth-user-id:v1'));
    return Number.isSafeInteger(value) && value > 0 ? value : null;
}

function selectionFromMapping(mapping: TransactionImportMapping | null) {
    const next = emptyColumnSelection();
    if (!mapping) return next;
    for (const key of Object.keys(next) as Array<keyof TransactionImportColumnSelection>) next[key] = mapping.columnMappings[key] === 'IGNORE' ? '' : mapping.columnMappings[key] ?? '';
    return next;
}

function previewFromSnapshot(snapshot: TransactionImportDraftSnapshot): TransactionImportPreviewResponse {
    return {
        importSessionId: snapshot.sessionId, importBatchId: snapshot.batchId, sessionStatus: snapshot.sessionStatus, revision: snapshot.revision,
        detectedColumns: snapshot.detectedColumns, mapping: snapshot.mapping, rows: [], summary: snapshot.summary ?? { totalRows: 0, validRows: 0, warningRows: 0, errorRows: 0, importableRows: 0, duplicateCandidates: 0 },
        fileDigest: snapshot.fileDigest, mappingDigest: snapshot.mappingDigest, optionsDigest: snapshot.optionsDigest, normalizedRowsDigest: snapshot.normalizedRowsDigest,
        expiresAt: snapshot.expiresAt, confirmable: false, previewToken: snapshot.previewToken,
    };
}

function fileIssue(file: File | null) {
    if (!file) return '请选择要上传的文件';
    const extension = file.name.toLowerCase().match(/\.[^.]+$/)?.[0];
    if (extension !== '.csv' && extension !== '.xlsx') return '请选择 CSV 或 XLSX 文件';
    if (file.size === 0) return '文件不能为空';
    if (file.size > maximumFileSize) return '文件不能超过 5 MiB';
    return null;
}

export function useTransactionImportCoordinator() {
    const userId = currentUserId();
    const [file, setFile] = useState<File | null>(null);
    const [fileDisplay, setFileDisplay] = useState<{ name: string; size: number; type: string } | null>(null);
    const [preview, setPreview] = useState<TransactionImportPreviewResponse | null>(null);
    const [selection, setSelection] = useState<TransactionImportColumnSelection>(emptyColumnSelection);
    const [discovered, setDiscovered] = useState<DiscoveredMappingValues | null>(null);
    const [categoryTypeSources, setCategoryTypeSources] = useState<Record<string, string[]>>({});
    const [typeMappings, setTypeMappings] = useState<Record<string, ImportTransactionType>>({});
    const [accountMappings, setAccountMappings] = useState<Record<string, number>>({});
    const [categoryMappings, setCategoryMappings] = useState<Record<string, number>>({});
    const [accounts, setAccounts] = useState<ImportAccountOption[]>([]);
    const [categories, setCategories] = useState<ImportCategoryOption[]>([]);
    const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [success, setSuccess] = useState(''); const [mappingMode, setMappingMode] = useState(false);
    const [restorationNote, setRestorationNote] = useState('');

    const persist = useCallback((next: TransactionImportPreviewResponse, displayFile: { name: string; size: number; type: string } | null, keepMappingOpen: boolean) => {
        if (!userId) return;
        const snapshot: TransactionImportDraftSnapshot = {
            schemaVersion: 1, userId, activeStep: keepMappingOpen ? 'MAPPING' : 'PREVIEW_READY',
            file: displayFile,
            sessionId: next.importSessionId, batchId: next.importBatchId, sessionStatus: next.sessionStatus, revision: next.revision, detectedColumns: next.detectedColumns,
            mapping: next.mapping, summary: next.summary, fileDigest: next.fileDigest, mappingDigest: next.mappingDigest, optionsDigest: next.optionsDigest,
            normalizedRowsDigest: next.normalizedRowsDigest, expiresAt: next.expiresAt, previewToken: next.previewToken, updatedAt: new Date().toISOString(),
        };
        writeTransactionImportDraft(sessionStorage, snapshot);
    }, [userId]);

    const loadOptions = useCallback(async () => {
        const options = await fetchTransactionImportOptions();
        setAccounts(options.accounts as ImportAccountOption[]); setCategories(options.categories as ImportCategoryOption[]);
    }, []);

    useEffect(() => {
        if (!userId) { setRestorationNote('无法确认当前登录用户，请重新登录后开始导入。'); return; }
        const snapshot = readTransactionImportDraft(sessionStorage, userId);
        if (!snapshot) { setRestorationNote('当前浏览器没有可恢复的导入草稿，可选择文件重新开始。'); return; }
        if (Date.parse(snapshot.expiresAt) <= Date.now()) { clearTransactionImportDraft(sessionStorage, userId); setRestorationNote('当前导入草稿已过期，请重新开始导入。'); return; }
        setPreview(previewFromSnapshot(snapshot)); setFileDisplay(snapshot.file); setSelection(selectionFromMapping(snapshot.mapping)); setMappingMode(snapshot.activeStep === 'MAPPING'); setRestorationNote('已恢复此浏览器标签页中的最小导入草稿；继续提交时仍由后端确认会话状态。');
        loadOptions().catch(loadError => setError(getErrorMessage(loadError, '无法加载账户或分类，请稍后重试')));
    }, [loadOptions, userId]);

    const acceptPreview = useCallback((next: TransactionImportPreviewResponse, nextFile: File | null, keepMappingOpen: boolean) => {
        setPreview(next); setSelection(selectionFromMapping(next.mapping)); setDiscovered(null); setCategoryTypeSources({}); setTypeMappings(next.mapping?.typeMappings ?? {}); setAccountMappings(next.mapping?.accountMappings ?? {}); setCategoryMappings(next.mapping?.categoryMappings ?? {});
        const display = nextFile ? { name: nextFile.name, size: nextFile.size, type: nextFile.type } : fileDisplay;
        if (display) setFileDisplay(display);
        setMappingMode(keepMappingOpen); persist(next, display, keepMappingOpen);
    }, [fileDisplay, persist]);

    const selectFile = (next: File | null) => { setFile(next); setFileDisplay(next ? { name: next.name, size: next.size, type: next.type } : null); setError(''); setSuccess(''); };

    const upload = async () => {
        if (preview) { setError('请先放弃当前导入，再选择并上传新文件。'); return; }
        const issue = fileIssue(file); if (issue) { setError(issue); return; }
        setBusy(true); setError(''); setSuccess('');
        try {
            const response = await uploadTransactionImport(file!); acceptPreview(response, file!, response.sessionStatus === 'MAPPING_REQUIRED'); await loadOptions();
            setSuccess(response.sessionStatus === 'MAPPING_REQUIRED' ? '文件已由服务端接收，请完成字段映射。' : '服务端已生成预览边界；本阶段不会执行确认导入。');
        } catch (uploadError) { setError(getErrorMessage(uploadError, '上传或服务端解析失败，请检查文件后重试。')); } finally { setBusy(false); }
    };

    const discovery = async () => {
        if (!preview) return;
        const invalid = validateColumnSelection(preview.detectedColumns, selection); if (invalid) { setError(invalid); return; }
        setBusy(true); setError(''); setSuccess('');
        try {
            const mapping = buildMapping(preview.detectedColumns, selection, {}, {}, {});
            const response = await updateTransactionImportMapping(preview.importSessionId, mapping);
            acceptPreview(response, file, true); // authoritative response entirely replaces the previous revision.
            const typeValues = new Map<string, string>(); const accountValues = new Map<string, string>(); const categoryValues = new Map<string, string>(); const sourcePairs = new Map<string, Set<string>>();
            const pages = Math.min(maximumDiscoveryPages, Math.max(1, Math.ceil(response.summary.totalRows / pageSize)));
            for (let page = 1; page <= pages; page += 1) {
                const rows = await fetchTransactionImportRows(response.importSessionId, page, pageSize);
                const pageValues = collectMappingValues(rows, selection);
                for (const value of pageValues.types) typeValues.set(normalizeSourceValue(value), value);
                for (const value of pageValues.accounts) accountValues.set(normalizeSourceValue(value), value);
                for (const value of pageValues.categories) categoryValues.set(normalizeSourceValue(value), value);
                for (const row of rows) {
                    const category = normalizeSourceValue(row.sourceValues[selection.category] ?? ''); const type = normalizeSourceValue(row.sourceValues[selection.type] ?? '');
                    if (category && type) { const types = sourcePairs.get(category) ?? new Set<string>(); types.add(type); sourcePairs.set(category, types); }
                }
            }
            setDiscovered({ types: [...typeValues.values()], accounts: [...accountValues.values()], categories: [...categoryValues.values()] });
            setCategoryTypeSources(Object.fromEntries([...sourcePairs].map(([category, types]) => [category, [...types]])));
            setSuccess('已在同一导入会话中完成来源值发现，请完成映射后提交正式预览。');
        } catch (discoveryError) { setError(getErrorMessage(discoveryError, '映射发现失败；会话可能已失效，请重新上传或重试。')); } finally { setBusy(false); }
    };

    const categoryUsage = useMemo(() => Object.fromEntries(Object.entries(categoryTypeSources).map(([category, sources]) => [category, sources.map(source => typeMappings[source]).filter((value): value is ImportTransactionType => Boolean(value))])), [categoryTypeSources, typeMappings]);

    const submitMapping = async () => {
        if (!preview || !discovered) return;
        const invalid = validateValueMappings(discovered, typeMappings, accountMappings, categoryMappings, categoryUsage); if (invalid) { setError(invalid); return; }
        setBusy(true); setError(''); setSuccess('');
        try {
            const response = await updateTransactionImportMapping(preview.importSessionId, buildMapping(preview.detectedColumns, selection, typeMappings, accountMappings, categoryMappings));
            acceptPreview(response, file, response.sessionStatus !== 'PREVIEW_READY');
            setSuccess(response.sessionStatus === 'PREVIEW_READY' ? '正式映射已提交，已到达后续 Preview 的前置状态。本阶段不会显示或调用 Confirm。' : '映射已提交；请根据服务端返回继续修正映射。');
        } catch (mappingError) { setError(getErrorMessage(mappingError, '正式映射被服务端拒绝，请检查选择后重试。')); } finally { setBusy(false); }
    };

    const discard = async () => {
        if (!preview) { setFile(null); setFileDisplay(null); return; }
        setBusy(true); setError('');
        try { await cancelTransactionImport(preview.importSessionId); if (userId) clearTransactionImportDraft(sessionStorage, userId); setPreview(null); setFile(null); setFileDisplay(null); setSelection(emptyColumnSelection()); setDiscovered(null); setMappingMode(false); setSuccess('当前导入已放弃。'); }
        catch (cancelError) { setError(getErrorMessage(cancelError, '无法放弃当前导入；请稍后重试。')); } finally { setBusy(false); }
    };

    return { userId, file, fileDisplay, preview, mappingMode, selection, discovered, accounts, categories, typeMappings, accountMappings, categoryMappings, categoryUsage, busy, error, success, restorationNote, selectFile,
        setSelection: (target: keyof TransactionImportColumnSelection, source: string) => setSelection(current => ({ ...current, [target]: source })),
        setTypeMapping: (source: string, value: ImportTransactionType | '') => setTypeMappings(current => { const next = { ...current }; if (value) next[source] = value; else delete next[source]; return next; }),
        setAccountMapping: (source: string, value: number | null) => setAccountMappings(current => { const next = { ...current }; if (value) next[source] = value; else delete next[source]; return next; }),
        setCategoryMapping: (source: string, value: number | null) => setCategoryMappings(current => { const next = { ...current }; if (value) next[source] = value; else delete next[source]; return next; }),
        upload, discovery, submitMapping, discard };
}
