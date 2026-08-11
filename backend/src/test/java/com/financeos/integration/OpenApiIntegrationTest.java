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
                "/paths/~1api~1v1~1investment~1portfolio/get",
                "/paths/~1api~1v1~1investment~1positions/get",
                "/paths/~1api~1v1~1investment~1positions~1{positionId}/get",
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
                .contains("User", "Account", "Asset", "Category", "Transaction", "Dashboard", "Investment Read");
        assertThat(document.at("/paths/~1api~1v1~1investment~1positions/get/parameters"))
                .extracting(parameter -> parameter.path("name").asText())
                .containsExactlyInAnyOrder("status", "accountId", "instrumentId", "cursor", "size");
        JsonNode positionParameters = document.at("/paths/~1api~1v1~1investment~1positions/get/parameters");
        assertThat(parameter(positionParameters, "cursor").path("description").asText()).containsIgnoringCase("opaque");
        assertThat(parameter(positionParameters, "size").at("/schema/minimum").asInt()).isEqualTo(1);
        assertThat(parameter(positionParameters, "size").at("/schema/maximum").asInt()).isEqualTo(100);
        assertThat(document.at("/paths/~1api~1v1~1investment~1positions~1{positionId}/get/parameters"))
                .anySatisfy(parameter -> {
                    assertThat(parameter.path("name").asText()).isEqualTo("positionId");
                    assertThat(parameter.path("in").asText()).isEqualTo("path");
                    assertThat(parameter.path("required").asBoolean()).isTrue();
                });
        List<String> readOperations = List.of(
                "/paths/~1api~1v1~1investment~1portfolio/get",
                "/paths/~1api~1v1~1investment~1positions/get",
                "/paths/~1api~1v1~1investment~1positions~1{positionId}/get");
        readOperations.forEach(operation -> assertThat(document.at(operation + "/responses").properties().stream()
                .map(java.util.Map.Entry::getKey).toList()).contains("200", "400", "401", "404", "500"));
        assertThat(document.at("/components/schemas/InvestmentPortfolioResponse/properties/openTotalCost/type").asText())
                .isEqualTo("string");
        assertThat(document.at("/components/schemas/InvestmentPositionListItem/properties/quantity/type").asText())
                .isEqualTo("string");
        assertThat(document.at("/components/schemas/InvestmentPositionDetail/properties/totalCost/type").asText())
                .isEqualTo("string");
        String portfolioReferenceSchema = document.at(
                "/components/schemas/InvestmentPortfolioResponse/properties/referenceValuation/$ref").asText();
        String listReferenceSchema = document.at(
                "/components/schemas/InvestmentPositionListItem/properties/referenceValuation/$ref").asText();
        assertThat(portfolioReferenceSchema).isNotBlank().isNotEqualTo(listReferenceSchema);
        assertThat(document.at(schemaPointer(portfolioReferenceSchema) + "/properties/valuedPositionCount/type").asText())
                .isEqualTo("integer");
        assertThat(document.at(schemaPointer(portfolioReferenceSchema) + "/properties/totalOpenPositionCount/type").asText())
                .isEqualTo("integer");
        String readContract = readOperations.stream().map(document::at).map(JsonNode::toString)
                .collect(java.util.stream.Collectors.joining())
                + document.at("/components/schemas/InvestmentPortfolioResponse")
                + document.at("/components/schemas/InvestmentPositionListItem")
                + document.at("/components/schemas/InvestmentPositionDetail");
        assertThat(readContract).doesNotContain("projectionVersion", "lastTransactionId", "requestHash",
                "idempotencyKey", "replayDigest", "correctionGroupId", "internalTrace", "constraintName",
                "stackTrace");
        assertThat(document.at("/paths/~1api~1v1~1investment~1transactions/get").isMissingNode()).isTrue();
        assertThat(document.at("/paths/~1api~1v1~1investment~1transactions~1{id}/get").isMissingNode()).isTrue();
        assertThat(document.at("/paths/~1api~1v1~1investment~1transactions~1{id}~1audit-timeline/get").isMissingNode()).isTrue();
    }

    @Test
    void exposesSwaggerUiWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    private JsonNode parameter(JsonNode parameters, String name) {
        return java.util.stream.StreamSupport.stream(parameters.spliterator(), false)
                .filter(parameter -> name.equals(parameter.path("name").asText()))
                .findFirst()
                .orElseThrow();
    }

    private String schemaPointer(String reference) {
        return reference.replace("#/components/schemas/", "/components/schemas/");
    }
}
