package com.financeos.module.investment.command;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.ApiResponse;
import com.financeos.common.BusinessException;
import com.financeos.module.investment.command.dto.InvestmentReversalRequest;
import com.financeos.module.investment.command.dto.InvestmentReversalResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@Validated
@RequestMapping("/api/v1/investment/transactions")
public class InvestmentReversalController {
    private final InvestmentCommandService commandService;
    private final ObjectMapper objectMapper;

    public InvestmentReversalController(InvestmentCommandService commandService, ObjectMapper objectMapper) {
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{transactionId}/reversal")
    public ApiResponse<InvestmentReversalResponse> reverse(
            @PathVariable Long transactionId,
            @RequestBody JsonNode body,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            Authentication authentication) {
        try {
            InvestmentReversalRequest request = objectMapper.readerFor(InvestmentReversalRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(body);
            return ApiResponse.ok(commandService.reverse((Long) authentication.getPrincipal(), transactionId, idempotencyKey, request));
        } catch (IOException exception) {
            throw new BusinessException(400, "Reversal request must contain only a valid reason field");
        }
    }
}
