package com.financeos.module.investment.command;

import com.financeos.common.ApiResponse;
import com.financeos.module.investment.command.dto.FirstBuyRequest;
import com.financeos.module.investment.command.dto.InvestmentCommandResponse;
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

@RestController
@Validated
@RequestMapping("/api/v1/investment/positions")
public class InvestmentCommandController {
    private final InvestmentCommandService commandService;

    public InvestmentCommandController(InvestmentCommandService commandService) {
        this.commandService = commandService;
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
}
