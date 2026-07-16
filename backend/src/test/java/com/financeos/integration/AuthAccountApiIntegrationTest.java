package com.financeos.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.account.dto.AccountRequest;
import com.financeos.module.user.dto.LoginRequest;
import com.financeos.module.user.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthAccountApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void realJwtOnlyReadsAccountsOwnedByCurrentUser() throws Exception {
        LoggedInUser userA = registerAndLogin("a");
        LoggedInUser userB = registerAndLogin("b");

        createAccount(userA.token(), "账户-A");
        createAccount(userB.token(), "账户-B");

        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", "Bearer " + userA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("账户-A"));
    }

    @Test
    void tokenIssuedBeforeUserIsDisabledReturnsUnauthorized() throws Exception {
        LoggedInUser user = registerAndLogin("inactive");
        jdbcTemplate.update("UPDATE users SET status = ? WHERE username = ?", "INACTIVE", user.username());

        MvcResult result = mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(response.path("data").isMissingNode()).isTrue();
    }

    private LoggedInUser registerAndLogin(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String username = prefix + suffix;
        String email = username + "@example.com";
        String password = "password123";

        mockMvc.perform(post("/api/v1/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, email, password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult login = mockMvc.perform(post("/api/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isString())
                .andReturn();

        String token = objectMapper.readTree(login.getResponse().getContentAsByteArray())
                .at("/data/token")
                .asText();
        return new LoggedInUser(username, token);
    }

    private void createAccount(String token, String name) throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AccountRequest(name, "CASH", "CNY"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private record LoggedInUser(String username, String token) {
    }
}
