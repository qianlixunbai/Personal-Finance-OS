package com.financeos.module.investment.command;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.ApiResponse;
import com.financeos.common.BusinessException;
import com.financeos.module.investment.command.dto.BuyReplacementRequest;
import com.financeos.module.investment.command.dto.DividendReplacementRequest;
import com.financeos.module.investment.command.dto.InvestmentReplacementRequest;
import com.financeos.module.investment.command.dto.InvestmentReplacementResponse;
import com.financeos.module.investment.command.dto.SellReplacementRequest;
import com.financeos.module.investment.entity.InvestmentTransaction;
import com.financeos.module.investment.mapper.InvestmentTransactionMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@Validated
@RequestMapping("/api/v1/investment/transactions")
public class InvestmentReplacementController {
    private final InvestmentCommandService commandService;
    private final InvestmentTransactionMapper transactionMapper;
    private final ObjectMapper objectMapper;

    public InvestmentReplacementController(InvestmentCommandService commandService, InvestmentTransactionMapper transactionMapper, ObjectMapper objectMapper) {
        this.commandService = commandService;
        this.transactionMapper = transactionMapper;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{transactionId}/replacement")
    public ApiResponse<InvestmentReplacementResponse> replace(@PathVariable Long transactionId, @RequestBody JsonNode body,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey, Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        InvestmentTransaction original = transactionMapper.findByUserIdAndId(userId, transactionId);
        if (original == null) throw new BusinessException(404, "Investment transaction not found");
        try {
            InvestmentReplacementRequest request = switch (original.getTransactionType()) {
                case "BUY" -> reader(BuyReplacementRequest.class).readValue(body);
                case "SELL" -> reader(SellReplacementRequest.class).readValue(body);
                case "DIVIDEND" -> reader(DividendReplacementRequest.class).readValue(body);
                default -> throw new BusinessException(409, "Investment transaction cannot be replaced");
            };
            return ApiResponse.ok(commandService.replace(userId, transactionId, idempotencyKey, request));
        } catch (IOException exception) {
            throw new BusinessException(400, "Replacement request contains invalid or unknown fields");
        }
    }

    private <T> com.fasterxml.jackson.databind.ObjectReader reader(Class<T> type) {
        return objectMapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
