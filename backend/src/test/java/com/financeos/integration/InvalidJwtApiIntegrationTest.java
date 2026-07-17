package com.financeos.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.auth.util.JwtUtil;
import com.financeos.module.dashboard.service.DashboardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InvalidJwtApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @MockBean
    private DashboardService dashboardService;

    @AfterEach
    void dashboardServiceWasNotCalled() {
        verifyNoInteractions(dashboardService);
    }

    @Test
    void expiredJwtReturnsUnifiedUnauthorizedResponseWithoutLeakingDetails() throws Exception {
        String expiredToken = new JwtUtil(jwtSecret, -60_000L).generateToken(1L, "expired-user");

        assertUnauthorized(expiredToken, "ExpiredJwtException", "expired-user");
    }

    @Test
    void tamperedJwtReturnsUnifiedUnauthorizedResponseWithoutLeakingDetails() throws Exception {
        String token = jwtUtil.generateToken(1L, "tampered-user");
        String tamperedToken = tamperSignature(token);

        assertUnauthorized(tamperedToken, "SignatureException", "tampered-user");
    }

    @Test
    void malformedJwtReturnsUnifiedUnauthorizedResponseWithoutLeakingDetails() throws Exception {
        assertUnauthorized("not-a-jwt", "MalformedJwtException", "not-a-jwt");
    }

    private void assertUnauthorized(String token, String... forbiddenFragments) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        String body = result.getResponse().getContentAsString();
        assertThat(response.path("data").isMissingNode()).isTrue();
        assertThat(body).doesNotContain(token, jwtSecret, "io.jsonwebtoken", "Exception", "at ");
        assertThat(body).doesNotContain(forbiddenFragments);
    }

    private String tamperSignature(String token) {
        String[] segments = token.split("\\.");
        assertThat(segments).hasSize(3);
        String signature = segments[2];
        char replacement = signature.charAt(0) == 'A' ? 'B' : 'A';
        return segments[0] + "." + segments[1] + "." + replacement + signature.substring(1);
    }
}
