package com.financeos.module.category.controller;

import com.financeos.common.ApiResponse;
import com.financeos.module.category.dto.CategoryRequest;
import com.financeos.module.category.dto.CategoryResponse;
import com.financeos.module.category.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Category", description = "Category management")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @Operation(summary = "List current user's categories", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<List<CategoryResponse>> list(@RequestParam(required = false) String type, Authentication auth) {
        return ApiResponse.ok(categoryService.listByUser((Long) auth.getPrincipal(), type));
    }

    @PostMapping
    @Operation(summary = "Create a category", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<CategoryResponse> create(@Valid @RequestBody CategoryRequest req, Authentication auth) {
        return ApiResponse.ok(categoryService.create((Long) auth.getPrincipal(), req));
    }

    @PostMapping("/init")
    @Operation(summary = "Initialize system categories", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<Void> init(Authentication auth) {
        categoryService.initSystemCategories();
        return ApiResponse.ok();
    }
}
