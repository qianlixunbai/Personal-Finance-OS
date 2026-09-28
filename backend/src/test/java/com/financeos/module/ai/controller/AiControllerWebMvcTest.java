package com.financeos.module.ai.controller;

import com.financeos.common.GlobalExceptionHandler;
import com.financeos.module.ai.dto.AiResponse;
import com.financeos.module.ai.service.AiRequestRateLimiter;
import com.financeos.module.ai.service.FinanceAiOrchestrator;
import com.financeos.module.auth.config.SecurityConfig;
import com.financeos.module.auth.config.SecurityErrorResponseHandler;
import com.financeos.module.auth.util.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AiController.class)
@Import({SecurityConfig.class, SecurityErrorResponseHandler.class, GlobalExceptionHandler.class})
class AiControllerWebMvcTest {
    private static final Long USER_ID = 42L;
    private static final String QUESTION = "本月支出是多少？";
    private static final String ANSWER = "本月支出为 123.45 元。";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private AiRequestRateLimiter rateLimiter;

    @MockBean
    private FinanceAiOrchestrator orchestrator;

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
    void authenticatedRequestUsesPrincipalAndReturnsOnlyAnswer() throws Exception {
        when(rateLimiter.tryAcquire(USER_ID)).thenReturn(true);
        when(orchestrator.ask(USER_ID, QUESTION)).thenReturn(new AiResponse(ANSWER));

        mockMvc.perform(post("/api/v1/ai/ask")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(ANSWER))
                .andExpect(jsonPath("$.data.content").doesNotExist())
                .andExpect(jsonPath("$.data.userId").doesNotExist());

        var inOrder = inOrder(rateLimiter, orchestrator);
        inOrder.verify(rateLimiter).tryAcquire(USER_ID);
        inOrder.verify(orchestrator).ask(USER_ID, QUESTION);
    }

    @Test
    void blankQuestionReturns400BeforeCallingAiServices() throws Exception {
        mockMvc.perform(post("/api/v1/ai/ask")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(rateLimiter, orchestrator);
    }

    @Test
    void questionLongerThan3000CharactersReturns400BeforeCallingAiServices() throws Exception {
        String question = "q".repeat(3001);

        mockMvc.perform(post("/api/v1/ai/ask")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + question + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(rateLimiter, orchestrator);
    }

    @Test
    void unauthenticatedRequestReturns401BeforeCallingAiServices() throws Exception {
        mockMvc.perform(post("/api/v1/ai/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + QUESTION + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(rateLimiter, orchestrator);
    }

    @Test
    void clientSuppliedUserIdIsRejectedAndNeverUsed() throws Exception {
        mockMvc.perform(post("/api/v1/ai/ask")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + QUESTION + "\",\"userId\":999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(rateLimiter, orchestrator);
    }

    @Test
    void rateLimitRejectsRequestBeforeCallingOrchestrator() throws Exception {
        when(rateLimiter.tryAcquire(USER_ID)).thenReturn(false);

        mockMvc.perform(post("/api/v1/ai/ask")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + QUESTION + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("AI 请求过于频繁，请稍后重试"));

        verify(rateLimiter).tryAcquire(USER_ID);
        verifyNoInteractions(orchestrator);
    }

    private UsernamePasswordAuthenticationToken currentUser() {
        return new UsernamePasswordAuthenticationToken(USER_ID, null, Collections.emptyList());
    }
}
