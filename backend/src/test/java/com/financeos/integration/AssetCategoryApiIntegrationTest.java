package com.financeos.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.asset.dto.AssetRequest;
import com.financeos.module.category.dto.CategoryRequest;
import com.financeos.module.user.dto.LoginRequest;
import com.financeos.module.user.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AssetCategoryApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void realJwtOnlyReadsAssetsOwnedByCurrentUser() throws Exception {
        LoggedInUser userA = registerAndLogin("asset-a");
        LoggedInUser userB = registerAndLogin("asset-b");
        String assetA = "资产-A-" + UUID.randomUUID();
        String assetB = "资产-B-" + UUID.randomUUID();

        createAsset(userA.token(), assetA);
        createAsset(userB.token(), assetB);

        MvcResult result = mockMvc.perform(get("/api/v1/assets")
                        .header("Authorization", "Bearer " + userA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value(assetA))
                .andReturn();

        JsonNode assets = objectMapper.readTree(result.getResponse().getContentAsByteArray()).at("/data");
        assertThat(assets.toString()).doesNotContain(assetB);
    }

    @Test
    void realJwtShowsSystemAndOwnCategoriesButExcludesOtherUsersCategories() throws Exception {
        LoggedInUser userA = registerAndLogin("category-a");
        LoggedInUser userB = registerAndLogin("category-b");
        String categoryA = "分类-A-" + UUID.randomUUID();
        String categoryB = "分类-B-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/categories/init")
                        .header("Authorization", "Bearer " + userA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        createCategory(userA.token(), categoryA);
        createCategory(userB.token(), categoryB);

        MvcResult result = mockMvc.perform(get("/api/v1/categories")
                        .header("Authorization", "Bearer " + userA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode categories = objectMapper.readTree(result.getResponse().getContentAsByteArray()).at("/data");
        assertThat(categories).anySatisfy(category -> assertThat(category.path("isSystem").asBoolean()).isTrue());
        assertThat(categories).anySatisfy(category -> assertThat(category.path("name").asText()).isEqualTo(categoryA));
        assertThat(categories).noneSatisfy(category -> assertThat(category.path("name").asText()).isEqualTo(categoryB));
    }

    private LoggedInUser registerAndLogin(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String username = prefix + suffix;
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
        return new LoggedInUser(objectMapper.readTree(login.getResponse().getContentAsByteArray())
                .at("/data/token").asText());
    }

    private void createAsset(String token, String name) throws Exception {
        AssetRequest request = new AssetRequest(name, "510300", "ETF", "CN", "CNY",
                new BigDecimal("12.50000000"), new BigDecimal("10.2300"));
        mockMvc.perform(post("/api/v1/assets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private void createCategory(String token, String name) throws Exception {
        CategoryRequest request = new CategoryRequest(name, "EXPENSE", null, 99);
        mockMvc.perform(post("/api/v1/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private record LoggedInUser(String token) {
    }
}
