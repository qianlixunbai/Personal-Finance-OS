package com.financeos.module.importing.controller;

import com.financeos.common.ApiResponse;
import com.financeos.module.importing.dto.TransactionImportConfirmRequest;
import com.financeos.module.importing.dto.TransactionImportConfirmResponse;
import com.financeos.module.importing.service.TransactionImportConfirmService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/imports/transactions")
public class TransactionImportConfirmController {
    private final TransactionImportConfirmService confirmService;
    public TransactionImportConfirmController(TransactionImportConfirmService confirmService) { this.confirmService = confirmService; }

    @PostMapping("/{importSessionId}/confirm")
    public ApiResponse<TransactionImportConfirmResponse> confirm(@PathVariable UUID importSessionId,
                                                                   @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                                   @Valid @RequestBody TransactionImportConfirmRequest request,
                                                                   Authentication authentication) {
        return ApiResponse.ok(confirmService.confirm((Long) authentication.getPrincipal(), importSessionId, idempotencyKey, request));
    }
}
