package com.financeos.module.dashboard.controller;

import com.financeos.common.ApiResponse;
import com.financeos.module.dashboard.dto.DashboardDto;
import com.financeos.module.dashboard.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Dashboard", description = "Dashboard overview")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Get the current user's dashboard", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<DashboardDto> dashboard(Authentication auth) {
        return ApiResponse.ok(dashboardService.getDashboard((Long) auth.getPrincipal()));
    }
}
