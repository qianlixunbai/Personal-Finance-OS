package com.financeos.module.ledger.controller;

import com.financeos.common.ApiResponse;
import com.financeos.module.ledger.dto.TransferResponse;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import com.financeos.module.ledger.dto.TransferRequest;
import com.financeos.module.ledger.service.TransferService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {
    private final TransferService transferService;

    public TransferController(TransferService transferService){
        this.transferService = transferService;
    }

    @PostMapping
    public ApiResponse<TransferResponse> create(Authentication authentication,
                                                @Valid @RequestBody TransferRequest request) {
        Long userId = (Long) authentication.getPrincipal();
        TransferResponse response = transferService.create(userId, request);
        return ApiResponse.ok(response);
    }
}
