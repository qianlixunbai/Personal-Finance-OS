package com.financeos.module.dashboard.controller;

import com.financeos.common.ApiResponse;
import com.financeos.module.dashboard.dto.DashboardDto;
import com.financeos.module.dashboard.service.DashboardService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<DashboardDto> dashboard(Authentication auth) {
        return ApiResponse.ok(dashboardService.getDashboard((Long) auth.getPrincipal()));
    }
}
