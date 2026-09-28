package com.financeos.module.ai.controller;

import com.financeos.common.ApiResponse;
import com.financeos.common.BusinessException;
import com.financeos.module.ai.dto.AiAskRequest;
import com.financeos.module.ai.dto.AiAskResponse;
import com.financeos.module.ai.service.AiRequestRateLimiter;
import com.financeos.module.ai.service.FinanceAiOrchestrator;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {
    private final AiRequestRateLimiter rateLimiter;
    private final FinanceAiOrchestrator orchestrator;

    public AiController(AiRequestRateLimiter rateLimiter, FinanceAiOrchestrator orchestrator) {
        this.rateLimiter = rateLimiter;
        this.orchestrator = orchestrator;
    }

    @PostMapping("/ask")
    public ApiResponse<AiAskResponse> ask(Authentication authentication,
                                          @Valid @RequestBody AiAskRequest request) {
        Long userId = (Long) authentication.getPrincipal();
        if (!rateLimiter.tryAcquire(userId)) {
            throw new BusinessException(429, "AI 请求过于频繁，请稍后重试");
        }

        return ApiResponse.ok(new AiAskResponse(orchestrator.ask(userId, request.question()).content()));
    }
}
