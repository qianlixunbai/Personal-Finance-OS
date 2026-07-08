package com.financeos.module.asset.controller;

import com.financeos.common.ApiResponse;
import com.financeos.common.PageResult;
import com.financeos.module.asset.dto.AssetRequest;
import com.financeos.module.asset.dto.AssetResponse;
import com.financeos.module.asset.service.AssetService;
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
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping
    public ApiResponse<List<AssetResponse>> list(Authentication auth) {
        return ApiResponse.ok(assetService.listByUser((Long) auth.getPrincipal()));
    }

    @GetMapping("/page")
    public ApiResponse<PageResult<AssetResponse>> page(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        Authentication auth) {
        return ApiResponse.ok(assetService.pageByUser((Long) auth.getPrincipal(), page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<AssetResponse> get(@PathVariable Long id, Authentication auth) {
        return ApiResponse.ok(assetService.getById((Long) auth.getPrincipal(), id));
    }

    @PostMapping
    public ApiResponse<AssetResponse> create(@Valid @RequestBody AssetRequest req, Authentication auth) {
        return ApiResponse.ok(assetService.create((Long) auth.getPrincipal(), req));
    }

    @PutMapping("/{id}/price")
    public ApiResponse<AssetResponse> updatePrice(@PathVariable Long id,
                                                   @RequestParam @Positive BigDecimal price,
                                                   Authentication auth) {
        return ApiResponse.ok(assetService.updatePrice((Long) auth.getPrincipal(), id, price));
    }

    @PutMapping("/{id}/close")
    public ApiResponse<AssetResponse> close(@PathVariable Long id, Authentication auth) {
        return ApiResponse.ok(assetService.close((Long) auth.getPrincipal(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication auth) {
        assetService.delete((Long) auth.getPrincipal(), id);
        return ApiResponse.ok();
    }
}
