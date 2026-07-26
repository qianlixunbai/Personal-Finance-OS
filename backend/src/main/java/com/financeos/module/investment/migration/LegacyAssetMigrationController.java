package com.financeos.module.investment.migration;

import com.financeos.common.ApiResponse;
import com.financeos.module.investment.migration.dto.LegacyAssetMigrationPreviewRequest;
import com.financeos.module.investment.migration.dto.LegacyAssetMigrationPreviewResponse;
import com.financeos.module.investment.migration.dto.LegacyAssetMigrationConfirmRequest;
import com.financeos.module.investment.migration.dto.LegacyAssetMigrationConfirmResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/investment/legacy-assets")
public class LegacyAssetMigrationController {
    private final LegacyAssetMigrationPreviewService previewService;
    private final LegacyAssetMigrationConfirmService confirmService;

    public LegacyAssetMigrationController(LegacyAssetMigrationPreviewService previewService,
                                          LegacyAssetMigrationConfirmService confirmService) {
        this.previewService = previewService;
        this.confirmService = confirmService;
    }

    @PostMapping("/{assetId}/migration-preview")
    public ApiResponse<LegacyAssetMigrationPreviewResponse> preview(@PathVariable Long assetId,
                                                                     @Valid @RequestBody LegacyAssetMigrationPreviewRequest request,
                                                                     Authentication auth) {
        return ApiResponse.ok(previewService.preview((Long) auth.getPrincipal(), assetId,
                request.instrumentId(), request.accountId()));
    }

    @PostMapping("/{assetId}/migration-confirm")
    public ApiResponse<LegacyAssetMigrationConfirmResponse> confirm(@PathVariable Long assetId,
                                                                     @RequestHeader("X-Idempotency-Key") String idempotencyKey,
                                                                     @Valid @RequestBody LegacyAssetMigrationConfirmRequest request,
                                                                     Authentication auth) {
        return ApiResponse.ok(confirmService.confirm((Long) auth.getPrincipal(), assetId,
                request.previewToken(), idempotencyKey));
    }
}
