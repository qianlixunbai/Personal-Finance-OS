package com.financeos.module.category.config;

import com.financeos.module.category.service.CategoryService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class CategoryDataInitializer implements ApplicationRunner {

    private final CategoryService categoryService;

    public CategoryDataInitializer(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        categoryService.initSystemCategories();
    }
}
