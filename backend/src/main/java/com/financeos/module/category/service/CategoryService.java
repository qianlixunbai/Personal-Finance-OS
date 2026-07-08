package com.financeos.module.category.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financeos.common.BusinessException;
import com.financeos.module.category.dto.CategoryRequest;
import com.financeos.module.category.dto.CategoryResponse;
import com.financeos.module.category.entity.Category;
import com.financeos.module.category.mapper.CategoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
public class CategoryService {

    private final CategoryMapper categoryMapper;

    public CategoryService(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    public List<CategoryResponse> listByUser(Long userId, String type) {
        var query = new LambdaQueryWrapper<Category>()
                .and(w -> w.eq(Category::getUserId, userId).or().eq(Category::getIsSystem, true))
                .orderByAsc(Category::getSortOrder);
        if (type != null) {
            query.eq(Category::getType, type);
        }
        return categoryMapper.selectList(query).stream().map(this::toResponse).toList();
    }

    @Transactional
    public CategoryResponse create(Long userId, CategoryRequest req) {
        Category category = new Category();
        category.setUserId(userId);
        category.setName(req.name());
        category.setType(req.type());
        category.setParentId(req.parentId());
        category.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
        category.setIsSystem(false);
        categoryMapper.insert(category);
        return toResponse(category);
    }

    @Transactional
    public void initSystemCategories() {
        List<CategoryRequest> defaults = Arrays.asList(
                new CategoryRequest("工资", "INCOME", null, 1),
                new CategoryRequest("奖金", "INCOME", null, 2),
                new CategoryRequest("其他收入", "INCOME", null, 3),
                new CategoryRequest("餐饮", "EXPENSE", null, 1),
                new CategoryRequest("交通", "EXPENSE", null, 2),
                new CategoryRequest("购物", "EXPENSE", null, 3),
                new CategoryRequest("住房", "EXPENSE", null, 4),
                new CategoryRequest("其他支出", "EXPENSE", null, 5)
        );
        for (CategoryRequest req : defaults) {
            if (categoryMapper.selectCount(
                    new LambdaQueryWrapper<Category>().eq(Category::getName, req.name()).eq(Category::getIsSystem, true)
            ) == 0) {
                Category cat = new Category();
                cat.setName(req.name());
                cat.setType(req.type());
                cat.setSortOrder(req.sortOrder());
                cat.setIsSystem(true);
                categoryMapper.insert(cat);
            }
        }
    }

    private CategoryResponse toResponse(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getUserId(),
                category.getName(),
                category.getType(),
                category.getParentId(),
                category.getIsSystem(),
                category.getSortOrder(),
                category.getCreatedAt(),
                category.getUpdatedAt()
        );
    }
}
