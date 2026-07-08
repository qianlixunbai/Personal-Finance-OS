package com.financeos.module.category.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financeos.module.category.entity.Category;
import com.financeos.module.category.mapper.CategoryMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CategoryQueryService {

    private final CategoryMapper categoryMapper;

    public CategoryQueryService(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    public Map<Long, String> mapVisibleNamesByUser(Long userId, Set<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectList(
                new LambdaQueryWrapper<Category>()
                        .in(Category::getId, categoryIds)
                        .and(visible -> visible
                                .eq(Category::getUserId, userId)
                                .or(system -> system.isNull(Category::getUserId)
                                        .eq(Category::getIsSystem, true)))
        ).stream().collect(Collectors.toMap(Category::getId, Category::getName, (first, second) -> first));
    }
}
