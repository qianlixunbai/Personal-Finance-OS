package com.financeos.module.category.controller;

import com.financeos.common.ApiResponse;
import com.financeos.module.category.dto.CategoryRequest;
import com.financeos.module.category.entity.Category;
import com.financeos.module.category.service.CategoryService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public ApiResponse<List<Category>> list(@RequestParam(required = false) String type, Authentication auth) {
        return ApiResponse.ok(categoryService.listByUser((Long) auth.getPrincipal(), type));
    }

    @PostMapping
    public ApiResponse<Category> create(@Valid @RequestBody CategoryRequest req, Authentication auth) {
        return ApiResponse.ok(categoryService.create((Long) auth.getPrincipal(), req));
    }

    @PostMapping("/init")
    public ApiResponse<Void> init(Authentication auth) {
        categoryService.initSystemCategories();
        return ApiResponse.ok();
    }
}
