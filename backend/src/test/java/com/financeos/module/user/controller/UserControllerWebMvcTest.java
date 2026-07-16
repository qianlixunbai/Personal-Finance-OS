package com.financeos.module.user.controller;

import com.financeos.common.BusinessException;
import com.financeos.module.auth.config.SecurityConfig;
import com.financeos.module.auth.config.SecurityErrorResponseHandler;
import com.financeos.module.auth.util.JwtAuthFilter;
import com.financeos.module.user.dto.LoginRequest;
import com.financeos.module.user.dto.LoginResponse;
import com.financeos.module.user.dto.RegisterRequest;
import com.financeos.module.user.service.UserService;
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
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, SecurityErrorResponseHandler.class})
class UserControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private UserService userService;

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
    void registerReturnsSuccessAndForwardsRequest() throws Exception {
        mockMvc.perform(post("/api/v1/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","email":"alice@example.com","password":"password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").doesNotExist());

        ArgumentCaptor<RegisterRequest> requestCaptor = ArgumentCaptor.forClass(RegisterRequest.class);
        verify(userService).register(requestCaptor.capture());
        RegisterRequest request = requestCaptor.getValue();
        assertThat(request.username()).isEqualTo("alice");
        assertThat(request.email()).isEqualTo("alice@example.com");
        assertThat(request.password()).isEqualTo("password123");
    }

    @Test
    void registerRejectsInvalidEmailWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/v1/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","email":"not-an-email","password":"password123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(userService);
    }

    @Test
    void registerMapsDuplicateUserToUnifiedBadRequest() throws Exception {
        doThrow(new BusinessException(400, "用户名已存在"))
                .when(userService).register(any(RegisterRequest.class));

        mockMvc.perform(post("/api/v1/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","email":"alice@example.com","password":"password123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("用户名已存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void loginReturnsTypedResponseAndForwardsCredentials() throws Exception {
        when(userService.login(any(LoginRequest.class)))
                .thenReturn(new LoginResponse("token-abc", 42L, "alice"));

        mockMvc.perform(post("/api/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.token").isString())
                .andExpect(jsonPath("$.data.token").value("token-abc"))
                .andExpect(jsonPath("$.data.userId").isNumber())
                .andExpect(jsonPath("$.data.userId").value(42))
                .andExpect(jsonPath("$.data.username").isString())
                .andExpect(jsonPath("$.data.username").value("alice"));

        ArgumentCaptor<LoginRequest> requestCaptor = ArgumentCaptor.forClass(LoginRequest.class);
        verify(userService).login(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).isEqualTo(new LoginRequest("alice", "password123"));
    }

    @Test
    void loginMapsInvalidCredentialsToUnifiedUnauthorizedResponse() throws Exception {
        when(userService.login(any(LoginRequest.class)))
                .thenThrow(new BusinessException(401, "用户名或密码错误"));

        mockMvc.perform(post("/api/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void loginMapsDisabledUserToUnifiedForbiddenResponse() throws Exception {
        when(userService.login(any(LoginRequest.class)))
                .thenThrow(new BusinessException(403, "账号已禁用"));

        mockMvc.perform(post("/api/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"password123"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("账号已禁用"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
