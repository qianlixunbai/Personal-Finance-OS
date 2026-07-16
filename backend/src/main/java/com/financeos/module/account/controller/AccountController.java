package com.financeos.module.account.controller;

import com.financeos.common.ApiResponse;
import com.financeos.common.PageResult;
import com.financeos.module.account.dto.AccountRequest;
import com.financeos.module.account.dto.AccountResponse;
import com.financeos.module.account.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Account", description = "Account management")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    private Long userId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    @GetMapping
    @Operation(summary = "List current user's accounts", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<List<AccountResponse>> list(Authentication auth) {
        return ApiResponse.ok(accountService.listByUser(userId(auth)));
    }

    @GetMapping("/page")
    @Operation(summary = "Page through current user's accounts", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<PageResult<AccountResponse>> page(@RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int size,
                                                          Authentication auth) {
        return ApiResponse.ok(accountService.pageByUser(userId(auth), page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an account", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<AccountResponse> get(@PathVariable Long id, Authentication auth) {
        return ApiResponse.ok(accountService.getById(userId(auth), id));
    }

    @PostMapping
    @Operation(summary = "Create an account", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<AccountResponse> create(@Valid @RequestBody AccountRequest req, Authentication auth) {
        return ApiResponse.ok(accountService.create(userId(auth), req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an account", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<AccountResponse> update(@PathVariable Long id,
                                                @Valid @RequestBody AccountRequest req,
                                                Authentication auth) {
        return ApiResponse.ok(accountService.update(userId(auth), id, req));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate an account", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<Void> deactivate(@PathVariable Long id, Authentication auth) {
        accountService.deactivate(userId(auth), id);
        return ApiResponse.ok();
    }
}
