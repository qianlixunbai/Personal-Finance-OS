package com.financeos.module.importing.controller;

import com.financeos.common.ApiResponse;
import com.financeos.module.importing.dto.TransactionImportMapping;
import com.financeos.module.importing.dto.TransactionImportPreviewRequest;
import com.financeos.module.importing.dto.TransactionImportPreviewResponse;
import com.financeos.module.importing.dto.TransactionImportPreviewRow;
import com.financeos.module.importing.preview.TransactionImportPreviewService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/imports/transactions")
public class TransactionImportPreviewController {
    private final TransactionImportPreviewService previewService;

    public TransactionImportPreviewController(TransactionImportPreviewService previewService) {
        this.previewService = previewService;
    }

    @PostMapping(value = "/preview", consumes = "multipart/form-data")
    public ApiResponse<TransactionImportPreviewResponse> preview(@RequestPart MultipartFile file,
                                                                  @RequestPart TransactionImportPreviewRequest request,
                                                                  Authentication authentication) {
        return ApiResponse.ok(previewService.create((Long) authentication.getPrincipal(), file, request));
    }

    @PutMapping("/{importSessionId}/preview")
    public ApiResponse<TransactionImportPreviewResponse> updateMapping(@PathVariable UUID importSessionId,
                                                                        @RequestPart TransactionImportMapping mapping,
                                                                        Authentication authentication) {
        return ApiResponse.ok(previewService.updateMapping((Long) authentication.getPrincipal(), importSessionId, mapping));
    }

    @GetMapping("/{importSessionId}/rows")
    public ApiResponse<List<TransactionImportPreviewRow>> rows(@PathVariable UUID importSessionId,
                                                                @RequestParam(defaultValue = "1") int page,
                                                                @RequestParam(defaultValue = "100") int size,
                                                                Authentication authentication) {
        return ApiResponse.ok(previewService.getRows((Long) authentication.getPrincipal(), importSessionId, page, size));
    }

    @DeleteMapping("/{importSessionId}")
    public ApiResponse<Void> cancel(@PathVariable UUID importSessionId, Authentication authentication) {
        previewService.cancel((Long) authentication.getPrincipal(), importSessionId);
        return ApiResponse.ok(null);
    }
}
