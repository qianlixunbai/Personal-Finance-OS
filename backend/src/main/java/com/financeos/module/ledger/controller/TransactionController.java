package com.financeos.module.ledger.controller;

import com.financeos.common.ApiResponse;
import com.financeos.common.PageResult;
import com.financeos.module.ledger.dto.TransactionRequest;
import com.financeos.module.ledger.dto.TransactionResponse;
import com.financeos.module.ledger.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transaction", description = "Transaction management")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    private Long userId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    @GetMapping("/page")
    @Operation(summary = "Page through current user's transactions", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<PageResult<TransactionResponse>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            Authentication auth) {
        return ApiResponse.ok(transactionService.pageByUser(
                userId(auth), page, size, accountId, categoryId, type, start, end));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a transaction", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<TransactionResponse> get(@PathVariable Long id, Authentication auth) {
        return ApiResponse.ok(transactionService.getById(userId(auth), id));
    }

    @PostMapping
    @Operation(summary = "Create a transaction", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<TransactionResponse> create(@Valid @RequestBody TransactionRequest req,
                                                   Authentication auth) {
        return ApiResponse.ok(transactionService.create(userId(auth), req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a transaction", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<TransactionResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody TransactionRequest req,
                                                   Authentication auth) {
        return ApiResponse.ok(transactionService.update(userId(auth), id, req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a transaction", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication auth) {
        transactionService.delete(userId(auth), id);
        return ApiResponse.ok();
    }
}
