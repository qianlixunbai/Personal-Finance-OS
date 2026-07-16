package com.financeos.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void exposesDocumentedPublicAndBearerProtectedOperations() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode document = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(document.path("openapi").asText()).startsWith("3.");
        assertThat(document.at("/components/securitySchemes/bearerAuth/type").asText()).isEqualTo("http");
        assertThat(document.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
        assertThat(document.at("/components/securitySchemes/bearerAuth/bearerFormat").asText()).isEqualTo("JWT");

        assertThat(document.at("/paths/~1api~1v1~1register/post/summary").asText()).isNotBlank();
        assertThat(document.at("/paths/~1api~1v1~1login/post/summary").asText()).isNotBlank();
        assertThat(document.at("/paths/~1api~1v1~1register/post/security").isMissingNode()).isTrue();
        assertThat(document.at("/paths/~1api~1v1~1login/post/security").isMissingNode()).isTrue();
        List<String> protectedOperations = List.of(
                "/paths/~1api~1v1~1accounts/get",
                "/paths/~1api~1v1~1accounts/post",
                "/paths/~1api~1v1~1accounts~1page/get",
                "/paths/~1api~1v1~1accounts~1{id}/get",
                "/paths/~1api~1v1~1accounts~1{id}/put",
                "/paths/~1api~1v1~1accounts~1{id}~1deactivate/post",
                "/paths/~1api~1v1~1assets/get",
                "/paths/~1api~1v1~1assets/post",
                "/paths/~1api~1v1~1assets~1page/get",
                "/paths/~1api~1v1~1assets~1{id}/get",
                "/paths/~1api~1v1~1assets~1{id}/delete",
                "/paths/~1api~1v1~1assets~1{id}~1price/put",
                "/paths/~1api~1v1~1assets~1{id}~1close/put",
                "/paths/~1api~1v1~1categories/get",
                "/paths/~1api~1v1~1categories/post",
                "/paths/~1api~1v1~1categories~1init/post",
                "/paths/~1api~1v1~1transactions/post",
                "/paths/~1api~1v1~1transactions~1page/get",
                "/paths/~1api~1v1~1transactions~1{id}/get",
                "/paths/~1api~1v1~1transactions~1{id}/put",
                "/paths/~1api~1v1~1transactions~1{id}/delete",
                "/paths/~1api~1v1~1dashboard/get"
        );
        protectedOperations.forEach(operation -> {
            assertThat(document.at(operation + "/summary").asText()).isNotBlank();
            assertThat(document.at(operation + "/security/0/bearerAuth").isArray()).isTrue();
        });

        assertThat(document.path("tags"))
                .extracting(tag -> tag.path("name").asText())
                .contains("User", "Account", "Asset", "Category", "Transaction", "Dashboard");
    }

    @Test
    void exposesSwaggerUiWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }
}
