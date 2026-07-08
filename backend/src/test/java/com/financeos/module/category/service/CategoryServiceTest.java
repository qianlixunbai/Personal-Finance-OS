package com.financeos.module.category.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financeos.module.category.dto.CategoryResponse;
import com.financeos.module.category.entity.Category;
import com.financeos.module.category.mapper.CategoryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategoryServiceTest {

    @Test
    void initSystemCategoriesCreatesRequiredDefaults() {
        CategoryMapper categoryMapper = mock(CategoryMapper.class);
        CategoryService service = new CategoryService(categoryMapper);
        when(categoryMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        service.initSystemCategories();

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryMapper, times(8)).insert(captor.capture());

        List<Category> categories = captor.getAllValues();
        assertEquals(List.of("工资", "奖金", "其他收入", "餐饮", "交通", "购物", "住房", "其他支出"),
                categories.stream().map(Category::getName).toList());
        assertEquals(List.of("INCOME", "INCOME", "INCOME", "EXPENSE", "EXPENSE", "EXPENSE", "EXPENSE", "EXPENSE"),
                categories.stream().map(Category::getType).toList());
        assertEquals(List.of(true, true, true, true, true, true, true, true),
                categories.stream().map(Category::getIsSystem).toList());
    }

    @Test
    void initSystemCategoriesDoesNotInsertExistingDefaultsAgain() {
        CategoryMapper categoryMapper = mock(CategoryMapper.class);
        CategoryService service = new CategoryService(categoryMapper);
        when(categoryMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        service.initSystemCategories();

        verify(categoryMapper, never()).insert(any(Category.class));
    }

    @Test
    void listByUserReturnsUserCategoriesAndSystemCategories() {
        CategoryMapper categoryMapper = mock(CategoryMapper.class);
        CategoryService service = new CategoryService(categoryMapper);

        Category userCategory = category(10L, 1L, "自定义收入", "INCOME", false, 9);
        Category systemCategory = category(11L, null, "工资", "INCOME", true, 1);
        when(categoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(systemCategory, userCategory));

        List<CategoryResponse> result = service.listByUser(1L, "INCOME");

        assertEquals(2, result.size());
        assertEquals("工资", result.get(0).name());
        assertEquals("自定义收入", result.get(1).name());
        verify(categoryMapper).selectList(any(LambdaQueryWrapper.class));
    }

    private Category category(Long id, Long userId, String name, String type, boolean system, int sortOrder) {
        Category category = new Category();
        category.setId(id);
        category.setUserId(userId);
        category.setName(name);
        category.setType(type);
        category.setIsSystem(system);
        category.setSortOrder(sortOrder);
        return category;
    }
}
