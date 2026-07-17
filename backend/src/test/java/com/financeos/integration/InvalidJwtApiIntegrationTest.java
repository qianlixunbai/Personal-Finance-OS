package com.financeos.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.auth.util.JwtUtil;
import com.financeos.module.dashboard.service.DashboardService;
import com.financeos.module.user.dto.LoginRequest;
import com.financeos.module.user.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Test
    void validJwtForActiveUserCanAccessProtectedEndpoint() throws Exception {
        LoggedInUser user = registerAndLogin("valid-user");

        mockMvc.perform(get("/api/v1/dashboard")
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"));
    }

    @Test
    void expiredJwtReturnsUnifiedUnauthorizedResponseWithoutLeakingDetails() throws Exception {
        LoggedInUser user = registerAndLogin("expired-user");
        String expiredToken = new JwtUtil(jwtSecret, -60_000L).generateToken(user.id(), user.username());

        assertUnauthorized(expiredToken, "ExpiredJwtException", user.username());
    }

    @Test
    void tamperedJwtReturnsUnifiedUnauthorizedResponseWithoutLeakingDetails() throws Exception {
        LoggedInUser user = registerAndLogin("tampered-user");
        String tamperedToken = tamperSignature(user.token());

        assertUnauthorized(tamperedToken, "SignatureException", user.username());
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
        verifyNoInteractions(dashboardService);
    }

    private String tamperSignature(String token) {
        String[] segments = token.split("\\.");
        assertThat(segments).hasSize(3);
        String signature = segments[2];
        char replacement = signature.charAt(0) == 'A' ? 'B' : 'A';
        return segments[0] + "." + segments[1] + "." + replacement + signature.substring(1);
    }

    private LoggedInUser registerAndLogin(String prefix) throws Exception {
        String username = prefix + java.util.UUID.randomUUID().toString().replace("-", "");
        String password = "password123";

        mockMvc.perform(post("/api/v1/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(username, username + "@example.com", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult login = mockMvc.perform(post("/api/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString())
                .andReturn();

        String token = objectMapper.readTree(login.getResponse().getContentAsByteArray()).at("/data/token").asText();
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        return new LoggedInUser(userId, username, token);
    }

    private record LoggedInUser(Long id, String username, String token) {
    }
}
