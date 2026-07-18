package com.financeos.module.asset.controller;

import com.financeos.common.ApiResponse;
import com.financeos.common.PageResult;
import com.financeos.module.asset.dto.AssetRequest;
import com.financeos.module.asset.dto.AssetResponse;
import com.financeos.module.asset.marketdata.dto.MarketQuoteResponse;
import com.financeos.module.asset.marketdata.service.MarketQuoteService;
import com.financeos.module.asset.service.AssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/assets")
@Tag(name = "Asset", description = "Asset management")
public class AssetController {

    private final AssetService assetService;
    private final MarketQuoteService marketQuoteService;

    public AssetController(AssetService assetService, MarketQuoteService marketQuoteService) {
        this.assetService = assetService;
        this.marketQuoteService = marketQuoteService;
    }

    @GetMapping
    @Operation(summary = "List current user's assets", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<List<AssetResponse>> list(Authentication auth) {
        return ApiResponse.ok(assetService.listByUser((Long) auth.getPrincipal()));
    }

    @GetMapping("/page")
    @Operation(summary = "Page through current user's assets", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<PageResult<AssetResponse>> page(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        Authentication auth) {
        return ApiResponse.ok(assetService.pageByUser((Long) auth.getPrincipal(), page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an asset", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<AssetResponse> get(@PathVariable Long id, Authentication auth) {
        return ApiResponse.ok(assetService.getById((Long) auth.getPrincipal(), id));
    }

    @PostMapping("/{id}/quote/refresh")
    @Operation(summary = "Refresh an owned stock or ETF reference quote", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<MarketQuoteResponse> refreshQuote(@PathVariable Long id, Authentication auth) {
        return ApiResponse.ok(marketQuoteService.refresh((Long) auth.getPrincipal(), id));
    }

    @PostMapping
    @Operation(summary = "Create an asset", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<AssetResponse> create(@Valid @RequestBody AssetRequest req, Authentication auth) {
        return ApiResponse.ok(assetService.create((Long) auth.getPrincipal(), req));
    }

    @PutMapping("/{id}/price")
    @Operation(summary = "Update an asset price", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<AssetResponse> updatePrice(@PathVariable Long id,
                                                   @RequestParam @Positive BigDecimal price,
                                                   Authentication auth) {
        return ApiResponse.ok(assetService.updatePrice((Long) auth.getPrincipal(), id, price));
    }

    @PutMapping("/{id}/close")
    @Operation(summary = "Close an asset position", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<AssetResponse> close(@PathVariable Long id, Authentication auth) {
        return ApiResponse.ok(assetService.close((Long) auth.getPrincipal(), id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an asset", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication auth) {
        assetService.delete((Long) auth.getPrincipal(), id);
        return ApiResponse.ok();
    }
}
