import { AlertMessage } from '../components/Feedback';
import { ImportMappingPanel } from '../components/importing/ImportMappingPanel';
import { ImportProgressRail } from '../components/importing/ImportProgressRail';
import { ImportUploadPanel } from '../components/importing/ImportUploadPanel';
import { PageHeader } from '../components/PageHeader';
import { useTransactionImportCoordinator } from '../hooks/useTransactionImportCoordinator';

export default function TransactionImport() {
    const coordinator = useTransactionImportCoordinator();
    const step = coordinator.preview?.sessionStatus === 'PREVIEW_READY' && !coordinator.mappingMode ? 'PREVIEW_READY' : coordinator.preview ? 'MAPPING' : 'UPLOAD';
    return <div className="import-workspace">
        <PageHeader title="导入流水" subtitle="上传 CSV 或 XLSX，在服务端会话中完成字段映射并生成正式预览。" />
        <ImportProgressRail active={step} />
        {coordinator.error && <AlertMessage type="error">{coordinator.error}</AlertMessage>}
        {coordinator.success && <AlertMessage type="success">{coordinator.success}</AlertMessage>}
        {coordinator.restorationNote && <AlertMessage type="warning">{coordinator.restorationNote}</AlertMessage>}
        <ImportUploadPanel file={coordinator.fileDisplay} busy={coordinator.busy} hasSession={Boolean(coordinator.preview)} onSelect={coordinator.selectFile} onUpload={coordinator.upload} onDiscard={coordinator.discard} />
        {coordinator.preview && coordinator.mappingMode && <ImportMappingPanel columns={coordinator.preview.detectedColumns} selection={coordinator.selection} discovered={coordinator.discovered} accounts={coordinator.accounts} categories={coordinator.categories} typeMappings={coordinator.typeMappings} accountMappings={coordinator.accountMappings} categoryMappings={coordinator.categoryMappings} categoryUsage={coordinator.categoryUsage} busy={coordinator.busy} onSelectionChange={coordinator.setSelection} onTypeChange={coordinator.setTypeMapping} onAccountChange={coordinator.setAccountMapping} onCategoryChange={coordinator.setCategoryMapping} onDiscover={coordinator.discovery} onSubmit={coordinator.submitMapping} />}
        {coordinator.preview?.sessionStatus === 'PREVIEW_READY' && !coordinator.mappingMode && <section className="page-panel import-panel" aria-labelledby="import-preview-boundary"><h2 id="import-preview-boundary">已到达预览边界</h2><p className="import-copy">服务端已返回最新 revision。Preview、校验、警告复核和 Confirm 均明确留给后续阶段；此页面没有 Confirm 按钮，也不会发起金融写入。</p></section>}
    </div>;
}
