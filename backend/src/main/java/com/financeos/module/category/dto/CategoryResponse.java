package com.financeos.module.category.dto;

import java.time.LocalDateTime;

public record CategoryResponse(
        Long id,
        Long userId,
        String name,
        String type,
        Long parentId,
        Boolean isSystem,
        Integer sortOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
