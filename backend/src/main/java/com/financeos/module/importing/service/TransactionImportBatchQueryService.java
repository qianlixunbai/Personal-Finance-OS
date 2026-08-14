package com.financeos.module.importing.service;

import com.financeos.common.BusinessException;
import com.financeos.module.importing.dto.TransactionImportBatchStatusResponse;
import com.financeos.module.importing.entity.TransactionImportBatch;
import com.financeos.module.importing.mapper.TransactionImportBatchMapper;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TransactionImportBatchQueryService {
    private final TransactionImportBatchMapper batchMapper;

    public TransactionImportBatchQueryService(TransactionImportBatchMapper batchMapper) {
        this.batchMapper = batchMapper;
    }

    public TransactionImportBatchStatusResponse getConfirmedBatch(Long userId, UUID batchId) {
        TransactionImportBatch batch = batchMapper.findConfirmedByIdAndUserId(batchId, userId);
        if (batch == null) {
            throw new BusinessException(404, "Import batch not found");
        }
        return new TransactionImportBatchStatusResponse(batch.getId(), batch.getStatus(), batch.getOriginalFileName(),
                batch.getFileDigest(), batch.getTotalRows(), batch.getTotalRows(), batch.getTotalRows(), 0,
                batch.getWarningCount(), batch.getConfirmedAt(), batch.getContractVersion());
    }
}
