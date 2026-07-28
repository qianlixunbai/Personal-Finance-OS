package com.financeos.module.investment.command;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.ApiResponse;
import com.financeos.common.BusinessException;
import com.financeos.module.investment.command.dto.FirstBuyRequest;
import com.financeos.module.investment.command.dto.InvestmentCommandResponse;
import com.financeos.module.investment.command.dto.InvestmentDividendRequest;
import com.financeos.module.investment.command.dto.InvestmentTradeRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@Validated
@RequestMapping("/api/v1/investment/positions")
public class InvestmentCommandController {
    private final InvestmentCommandService commandService;
    private final ObjectMapper objectMapper;

    public InvestmentCommandController(InvestmentCommandService commandService, ObjectMapper objectMapper) {
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ApiResponse<InvestmentCommandResponse> firstBuy(
            @Valid @RequestBody FirstBuyRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            Authentication authentication) {
        return ApiResponse.ok(commandService.firstBuy((Long) authentication.getPrincipal(), idempotencyKey, request));
    }

    @PostMapping("/{assetId}/buy")
    public ApiResponse<InvestmentCommandResponse> buy(
            @PathVariable Long assetId,
            @Valid @RequestBody InvestmentTradeRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            Authentication authentication) {
        return ApiResponse.ok(commandService.buy((Long) authentication.getPrincipal(), assetId, idempotencyKey, request));
    }

    @PostMapping("/{assetId}/sell")
    public ApiResponse<InvestmentCommandResponse> sell(
            @PathVariable Long assetId,
            @Valid @RequestBody InvestmentTradeRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            Authentication authentication) {
        return ApiResponse.ok(commandService.sell((Long) authentication.getPrincipal(), assetId, idempotencyKey, request));
    }

    @PostMapping("/{assetId}/dividends")
    public ApiResponse<InvestmentCommandResponse> dividend(
            @PathVariable Long assetId,
            @RequestBody JsonNode body,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            Authentication authentication) {
        InvestmentDividendRequest request;
        try {
            request = objectMapper.readerFor(InvestmentDividendRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(body);
        } catch (IOException exception) {
            throw new BusinessException(400, "Dividend request must contain only valid decimal string fields");
        }
        return ApiResponse.ok(commandService.dividend((Long) authentication.getPrincipal(), assetId, idempotencyKey, request));
    }
}
