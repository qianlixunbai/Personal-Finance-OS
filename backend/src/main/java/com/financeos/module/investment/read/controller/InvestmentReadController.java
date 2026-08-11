package com.financeos.module.investment.read.controller;

import com.financeos.common.ApiResponse;
import com.financeos.module.investment.read.dto.CursorPage;
import com.financeos.module.investment.read.dto.InvestmentPortfolioResponse;
import com.financeos.module.investment.read.dto.InvestmentPositionDetail;
import com.financeos.module.investment.read.dto.InvestmentPositionListItem;
import com.financeos.module.investment.read.model.PositionListQuery;
import com.financeos.module.investment.read.service.InvestmentPortfolioQueryService;
import com.financeos.module.investment.read.service.InvestmentPositionDetailQueryService;
import com.financeos.module.investment.read.service.InvestmentPositionReadQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/investment")
@Tag(name = "Investment Read", description = "Investment portfolio and position queries")
public class InvestmentReadController {
    private final InvestmentPortfolioQueryService portfolioService;
    private final InvestmentPositionReadQueryService positionService;
    private final InvestmentPositionDetailQueryService detailService;

    public InvestmentReadController(InvestmentPortfolioQueryService portfolioService,
                                    InvestmentPositionReadQueryService positionService,
                                    InvestmentPositionDetailQueryService detailService) {
        this.portfolioService = portfolioService;
        this.positionService = positionService;
        this.detailService = detailService;
    }

    @GetMapping("/portfolio")
    @Operation(summary = "Get transaction-driven portfolio summary", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Portfolio summary returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Bearer JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Owned resource not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Sanitized internal consistency error")
    })
    public ApiResponse<InvestmentPortfolioResponse> portfolio(Authentication authentication) {
        return ApiResponse.ok(portfolioService.get((Long) authentication.getPrincipal()));
    }

    @GetMapping("/positions")
    @Operation(summary = "List transaction-driven positions by opaque cursor", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Position page returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid filter, cursor, or size"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Bearer JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Account or instrument filter not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Sanitized internal consistency error")
    })
    public ApiResponse<CursorPage<InvestmentPositionListItem>> positions(
            @Parameter(schema = @Schema(type = "string", allowableValues = {"OPEN", "CLOSED", "ALL"}))
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long instrumentId,
            @Parameter(description = "Opaque cursor returned by a preceding request", schema = @Schema(type = "string"))
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) Integer size,
            Authentication authentication) {
        return ApiResponse.ok(positionService.list((Long) authentication.getPrincipal(),
                new PositionListQuery(status, accountId, instrumentId, cursor, size)));
    }

    @GetMapping("/positions/{positionId}")
    @Operation(summary = "Get transaction-driven position detail", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Position detail returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid position ID"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Bearer JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Position not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Sanitized internal consistency error")
    })
    public ApiResponse<InvestmentPositionDetail> position(
            @Parameter(schema = @Schema(type = "integer", format = "int64", minimum = "1"))
            @PathVariable @Min(1) Long positionId, Authentication authentication) {
        return ApiResponse.ok(detailService.get((Long) authentication.getPrincipal(), positionId));
    }
}
