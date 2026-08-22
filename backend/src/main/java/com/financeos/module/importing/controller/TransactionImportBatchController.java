package com.financeos.module.importing.controller;

import com.financeos.common.ApiResponse;
import com.financeos.module.importing.dto.TransactionImportReceipt;
import com.financeos.module.importing.service.TransactionImportConfirmService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/imports/transactions/batches")
public class TransactionImportBatchController {
    private final TransactionImportConfirmService confirmService;

    public TransactionImportBatchController(TransactionImportConfirmService confirmService) {
        this.confirmService = confirmService;
    }

    @GetMapping("/{importBatchId}")
    public ApiResponse<TransactionImportReceipt> get(@PathVariable UUID importBatchId, Authentication authentication) {
        return ApiResponse.ok(confirmService.getReceipt((Long) authentication.getPrincipal(), importBatchId));
    }
}
