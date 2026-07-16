package com.financeos.module.category.controller;

import com.financeos.common.BusinessException;
import com.financeos.common.GlobalExceptionHandler;
import com.financeos.module.auth.config.SecurityConfig;
import com.financeos.module.auth.config.SecurityErrorResponseHandler;
import com.financeos.module.auth.util.JwtAuthFilter;
import com.financeos.module.category.dto.CategoryRequest;
import com.financeos.module.category.dto.CategoryResponse;
import com.financeos.module.category.service.CategoryService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CategoryController.class)
@Import({SecurityConfig.class, SecurityErrorResponseHandler.class, GlobalExceptionHandler.class})
class CategoryControllerWebMvcTest {

    private static final Long USER_ID = 42L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private CategoryService categoryService;

    @BeforeEach
    void passThroughJwtFilter() throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void listWithoutAuthenticationReturnsUnifiedUnauthorizedResponse() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(categoryService);
    }

    @Test
    void listForwardsSupportedTypeAndReturnsCategoryResponses() throws Exception {
        when(categoryService.listByUser(USER_ID, "EXPENSE"))
                .thenReturn(List.of(categoryResponse(7L, USER_ID, "餐饮", "EXPENSE", null, false, 3)));

        mockMvc.perform(get("/api/v1/categories").param("type", "EXPENSE").with(authentication(currentUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").isNumber())
                .andExpect(jsonPath("$.data[0].userId").isNumber())
                .andExpect(jsonPath("$.data[0].parentId").doesNotExist())
                .andExpect(jsonPath("$.data[0].isSystem").value(false))
                .andExpect(jsonPath("$.data[0].sortOrder").isNumber())
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-07-16T10:30:45"));

        verify(categoryService).listByUser(USER_ID, "EXPENSE");
    }

    @Test
    void createReturnsCategoryResponseAndForwardsValidatedRequest() throws Exception {
        when(categoryService.create(eq(USER_ID), any(CategoryRequest.class)))
                .thenReturn(categoryResponse(7L, USER_ID, "副业", "INCOME", 3L, false, 8));

        mockMvc.perform(post("/api/v1/categories")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"副业","type":"INCOME","parentId":3,"sortOrder":8}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.userId").value(42))
                .andExpect(jsonPath("$.data.isSystem").value(false));

        ArgumentCaptor<CategoryRequest> requestCaptor = ArgumentCaptor.forClass(CategoryRequest.class);
        verify(categoryService).create(eq(USER_ID), requestCaptor.capture());
        assertThat(requestCaptor.getValue()).isEqualTo(new CategoryRequest("副业", "INCOME", 3L, 8));
    }

    @Test
    void createRejectsBlankNameWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/v1/categories")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","type":"INCOME"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(categoryService);
    }

    @Test
    void createMapsInvalidParentCategoryToUnifiedBusinessBadRequest() throws Exception {
        when(categoryService.create(eq(USER_ID), any(CategoryRequest.class)))
                .thenThrow(new BusinessException(400, "父分类不可用"));

        mockMvc.perform(post("/api/v1/categories")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"副业","type":"INCOME","parentId":999}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("父分类不可用"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void initRequiresAuthenticationAndCallsGlobalInitializerOnce() throws Exception {
        mockMvc.perform(post("/api/v1/categories/init"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(post("/api/v1/categories/init").with(authentication(currentUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(categoryService).initSystemCategories();
    }

    private UsernamePasswordAuthenticationToken currentUser() {
        return new UsernamePasswordAuthenticationToken(USER_ID, null, Collections.emptyList());
    }

    private CategoryResponse categoryResponse(Long id, Long userId, String name, String type,
                                              Long parentId, boolean isSystem, int sortOrder) {
        LocalDateTime timestamp = LocalDateTime.of(2026, 7, 16, 10, 30, 45);
        return new CategoryResponse(id, userId, name, type, parentId, isSystem, sortOrder, timestamp, timestamp);
    }
}
