package com.financeos.module.category.dto;

import jakarta.validation.constraints.NotBlank;

public record CategoryRequest(
        @NotBlank String name,
        @NotBlank String type,
        Long parentId,
        Integer sortOrder
) {}
