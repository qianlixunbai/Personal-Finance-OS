package com.financeos.module.category.config;

import com.financeos.module.category.service.CategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CategoryDataInitializerTest {

    @Test
    void applicationStartupInitializesSystemCategories() {
        CategoryService categoryService = mock(CategoryService.class);
        CategoryDataInitializer initializer = new CategoryDataInitializer(categoryService);

        initializer.run(new DefaultApplicationArguments());

        verify(categoryService).initSystemCategories();
    }
}
