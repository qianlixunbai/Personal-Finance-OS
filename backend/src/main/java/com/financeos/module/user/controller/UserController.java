package com.financeos.module.user.controller;

import com.financeos.common.ApiResponse;
import com.financeos.module.user.dto.LoginRequest;
import com.financeos.module.user.dto.LoginResponse;
import com.financeos.module.user.dto.RegisterRequest;
import com.financeos.module.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "User", description = "User registration and authentication")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a user")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest req) {
        userService.register(req);
        return ApiResponse.ok();
    }

    @PostMapping("/login")
    @Operation(summary = "Log in and receive a JWT")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(userService.login(req));
    }
}
