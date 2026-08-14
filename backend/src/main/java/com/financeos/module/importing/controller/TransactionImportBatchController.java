package com.financeos.module.importing.controller;

import com.financeos.common.ApiResponse;
import com.financeos.module.importing.dto.TransactionImportBatchStatusResponse;
import com.financeos.module.importing.service.TransactionImportBatchQueryService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/imports/transactions/batches")
public class TransactionImportBatchController {
    private final TransactionImportBatchQueryService queryService;

    public TransactionImportBatchController(TransactionImportBatchQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{importBatchId}")
    public ApiResponse<TransactionImportBatchStatusResponse> get(@PathVariable UUID importBatchId, Authentication authentication) {
        return ApiResponse.ok(queryService.getConfirmedBatch((Long) authentication.getPrincipal(), importBatchId));
    }
}
