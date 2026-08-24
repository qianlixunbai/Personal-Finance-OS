import { AlertMessage } from '../components/Feedback';
import { ImportMappingPanel } from '../components/importing/ImportMappingPanel';
import { ImportProgressRail } from '../components/importing/ImportProgressRail';
import { ImportPreviewPanel } from '../components/importing/ImportPreviewPanel';
import { ImportUploadPanel } from '../components/importing/ImportUploadPanel';
import { ImportConfirmDialog } from '../components/importing/ImportConfirmDialog';
import { ImportRecoveryBanner } from '../components/importing/ImportRecoveryBanner';
import { PageHeader } from '../components/PageHeader';
import { useTransactionImportCoordinator } from '../hooks/useTransactionImportCoordinator';

export default function TransactionImport() {
    const coordinator = useTransactionImportCoordinator();
    const step = coordinator.workflow === 'RECEIPT_READY' || coordinator.workflow === 'CONFIRMED' ? 'RECEIPT' : coordinator.readyForConfirm ? 'READY_FOR_CONFIRM' : coordinator.preview?.sessionStatus === 'PREVIEW_READY' && !coordinator.mappingMode ? 'WARNING_REVIEW' : coordinator.preview ? 'MAPPING' : 'UPLOAD';
    return <div className="import-workspace">
        <PageHeader title="导入流水" subtitle="上传 CSV 或 XLSX，在服务端会话中完成字段映射并生成正式预览。" />
        <ImportProgressRail active={step} />
        {coordinator.error && <AlertMessage type="error">{coordinator.error}</AlertMessage>}
        {coordinator.success && <AlertMessage type="success">{coordinator.success}</AlertMessage>}
        {coordinator.restorationNote && <AlertMessage type="warning">{coordinator.restorationNote}</AlertMessage>}
        {!coordinator.pendingConfirm && <ImportUploadPanel file={coordinator.fileDisplay} busy={coordinator.busy} hasSession={Boolean(coordinator.preview)} onSelect={coordinator.selectFile} onUpload={coordinator.upload} onDiscard={coordinator.discard} />}
        {coordinator.pendingConfirm && <ImportRecoveryBanner state={coordinator.pendingConfirm.state} busy={coordinator.busy} onRecover={coordinator.recoverConfirm} />}
        {coordinator.preview && coordinator.mappingMode && <ImportMappingPanel columns={coordinator.preview.detectedColumns} selection={coordinator.selection} discovered={coordinator.discovered} accounts={coordinator.accounts} categories={coordinator.categories} typeMappings={coordinator.typeMappings} accountMappings={coordinator.accountMappings} categoryMappings={coordinator.categoryMappings} categoryUsage={coordinator.categoryUsage} busy={coordinator.busy} onSelectionChange={coordinator.setSelection} onTypeChange={coordinator.setTypeMapping} onAccountChange={coordinator.setAccountMapping} onCategoryChange={coordinator.setCategoryMapping} onDiscover={coordinator.discovery} onSubmit={coordinator.submitMapping} />}
        {!coordinator.pendingConfirm && coordinator.preview?.sessionStatus === 'PREVIEW_READY' && !coordinator.mappingMode && <ImportPreviewPanel preview={coordinator.preview} rows={coordinator.visibleRows} page={coordinator.page} pageLast={coordinator.pageLast} busy={coordinator.busy} workflow={coordinator.workflow} warnings={coordinator.warnings} acknowledged={coordinator.acknowledged} hydrationError={coordinator.hydrationError} readyForConfirm={coordinator.readyForConfirm} onPage={coordinator.loadVisiblePage} onBackToMapping={coordinator.backToMapping} onRetryHydration={coordinator.retryHydration} onToggleWarningGroup={coordinator.toggleWarningGroup} onRepreview={coordinator.repreview} onConfirm={coordinator.openConfirm} />}
        {coordinator.confirmDialogOpen && coordinator.preview && <ImportConfirmDialog preview={coordinator.preview} busy={coordinator.busy || coordinator.workflow === 'CONFIRMING'} onClose={coordinator.closeConfirm} onConfirm={coordinator.confirm} />}
    </div>;
}
