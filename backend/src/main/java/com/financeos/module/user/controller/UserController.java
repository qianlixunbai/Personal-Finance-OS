package com.financeos.module.user.controller;

import com.financeos.common.ApiResponse;
import com.financeos.module.user.dto.LoginRequest;
import com.financeos.module.user.dto.LoginResponse;
import com.financeos.module.user.dto.RegisterRequest;
import com.financeos.module.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest req) {
        userService.register(req);
        return ApiResponse.ok();
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(userService.login(req));
    }
}
