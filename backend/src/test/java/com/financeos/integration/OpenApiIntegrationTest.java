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
                "/paths/~1api~1v1~1investment~1transactions/get",
                "/paths/~1api~1v1~1investment~1transactions~1{logicalTransactionId}/get",
                "/paths/~1api~1v1~1investment~1transactions~1{logicalTransactionId}~1audit-timeline/get",
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
        JsonNode transactionParameters = document.at("/paths/~1api~1v1~1investment~1transactions/get/parameters");
        assertThat(transactionParameters)
                .extracting(parameter -> parameter.path("name").asText())
                .containsExactlyInAnyOrder("positionId", "accountId", "instrumentId", "type", "correctionStatus",
                        "from", "to", "cursor", "size");
        assertThat(parameter(transactionParameters, "cursor").path("description").asText()).containsIgnoringCase("opaque");
        assertThat(parameter(transactionParameters, "size").at("/schema/minimum").asInt()).isEqualTo(1);
        assertThat(parameter(transactionParameters, "size").at("/schema/maximum").asInt()).isEqualTo(100);
        assertThat(parameter(transactionParameters, "type").at("/schema/enum"))
                .extracting(JsonNode::asText).containsExactlyInAnyOrder("OPENING_POSITION", "BUY", "SELL", "DIVIDEND");
        assertThat(parameter(transactionParameters, "correctionStatus").at("/schema/enum"))
                .extracting(JsonNode::asText).containsExactlyInAnyOrder("UNCHANGED", "REVERSED", "REPLACED");
        assertThat(parameter(transactionParameters, "from").at("/schema/format").asText()).isEqualTo("date-time");
        assertThat(parameter(transactionParameters, "to").at("/schema/format").asText()).isEqualTo("date-time");
        assertThat(document.at("/paths/~1api~1v1~1investment~1positions~1{positionId}/get/parameters"))
                .anySatisfy(parameter -> {
                    assertThat(parameter.path("name").asText()).isEqualTo("positionId");
                    assertThat(parameter.path("in").asText()).isEqualTo("path");
                    assertThat(parameter.path("required").asBoolean()).isTrue();
                });
        List<String> transactionDetailPaths = List.of(
                "/paths/~1api~1v1~1investment~1transactions~1{logicalTransactionId}/get/parameters",
                "/paths/~1api~1v1~1investment~1transactions~1{logicalTransactionId}~1audit-timeline/get/parameters");
        transactionDetailPaths.forEach(path -> assertThat(document.at(path)).anySatisfy(parameter -> {
            assertThat(parameter.path("name").asText()).isEqualTo("logicalTransactionId");
            assertThat(parameter.path("in").asText()).isEqualTo("path");
            assertThat(parameter.path("required").asBoolean()).isTrue();
            assertThat(parameter.at("/schema/minimum").asInt()).isEqualTo(1);
        }));
        List<String> readOperations = List.of(
                "/paths/~1api~1v1~1investment~1portfolio/get",
                "/paths/~1api~1v1~1investment~1positions/get",
                "/paths/~1api~1v1~1investment~1positions~1{positionId}/get",
                "/paths/~1api~1v1~1investment~1transactions/get",
                "/paths/~1api~1v1~1investment~1transactions~1{logicalTransactionId}/get",
                "/paths/~1api~1v1~1investment~1transactions~1{logicalTransactionId}~1audit-timeline/get");
        readOperations.forEach(operation -> assertThat(document.at(operation + "/responses").properties().stream()
                .map(java.util.Map.Entry::getKey).toList()).contains("200", "400", "401", "404", "500"));
        assertThat(document.at("/components/schemas/InvestmentPortfolioResponse/properties/openTotalCost/type").asText())
                .isEqualTo("string");
        assertThat(document.at("/components/schemas/InvestmentPositionListItem/properties/quantity/type").asText())
                .isEqualTo("string");
        assertThat(document.at("/components/schemas/InvestmentPositionDetail/properties/totalCost/type").asText())
                .isEqualTo("string");
        assertThat(document.at("/components/schemas/InvestmentLogicalTransactionListItem/properties/grossAmount/type").asText())
                .isEqualTo("string");
        String detailBusinessValuesSchema = document.at(
                "/components/schemas/InvestmentTransactionDetail/properties/originalBusinessValues/$ref").asText();
        assertThat(detailBusinessValuesSchema).isNotBlank();
        assertThat(document.at(schemaPointer(detailBusinessValuesSchema) + "/properties/netAmount/type").asText())
                .isEqualTo("string");
        String auditCurrentPositionSchema = document.at(
                "/components/schemas/InvestmentTransactionAuditTimeline/properties/currentPosition/$ref").asText();
        assertThat(auditCurrentPositionSchema).isNotBlank();
        assertThat(document.at(schemaPointer(auditCurrentPositionSchema) + "/properties/totalCost/type").asText())
                .isEqualTo("string");
        String detailAccountSchema = document.at(
                "/components/schemas/InvestmentTransactionDetail/properties/account/$ref").asText();
        String detailInstrumentSchema = document.at(
                "/components/schemas/InvestmentTransactionDetail/properties/instrument/$ref").asText();
        String detailPostingReceiptSchema = document.at(
                "/components/schemas/InvestmentTransactionDetail/properties/postingReceipt/$ref").asText();
        String detailCorrectionReceiptSchema = document.at(
                "/components/schemas/InvestmentTransactionDetail/properties/correctionFinalReceipt/$ref").asText();
        String detailCurrentPositionSchema = document.at(
                "/components/schemas/InvestmentTransactionDetail/properties/currentPosition/$ref").asText();
        String auditEventSchema = document.at(
                "/components/schemas/InvestmentTransactionAuditTimeline/properties/events/items/$ref").asText();
        String auditBusinessValuesSchema = document.at(schemaPointer(auditEventSchema) + "/properties/businessValues/$ref").asText();
        String auditPostingReceiptSchema = document.at(schemaPointer(auditEventSchema) + "/properties/postingReceipt/$ref").asText();
        String auditCorrectionReceiptSchema = document.at(schemaPointer(auditEventSchema) + "/properties/correctionFinalReceipt/$ref").asText();
        String auditFactSchema = document.at(schemaPointer(auditEventSchema) + "/properties/facts/items/$ref").asText();
        assertThat(List.of(detailAccountSchema, detailInstrumentSchema, detailBusinessValuesSchema,
                detailPostingReceiptSchema, detailCorrectionReceiptSchema, detailCurrentPositionSchema,
                auditCurrentPositionSchema, auditEventSchema, auditBusinessValuesSchema, auditPostingReceiptSchema,
                auditCorrectionReceiptSchema, auditFactSchema)).allSatisfy(reference -> assertThat(reference).isNotBlank());
        assertThat(detailBusinessValuesSchema).isNotEqualTo(auditBusinessValuesSchema);
        assertThat(detailPostingReceiptSchema).isNotEqualTo(auditPostingReceiptSchema);
        assertThat(detailCorrectionReceiptSchema).isNotEqualTo(auditCorrectionReceiptSchema);
        assertThat(detailCurrentPositionSchema).isNotEqualTo(auditCurrentPositionSchema);
        assertThat(document.at(schemaPointer(detailAccountSchema) + "/properties/displayName/type").asText()).isEqualTo("string");
        assertThat(document.at(schemaPointer(detailInstrumentSchema) + "/properties/symbol/type").asText()).isEqualTo("string");
        assertThat(document.at(schemaPointer(auditFactSchema) + "/properties/role/type").asText()).isEqualTo("string");
        String transactionNestedSchemas = List.of(detailAccountSchema, detailInstrumentSchema, detailBusinessValuesSchema,
                        detailPostingReceiptSchema, detailCorrectionReceiptSchema, detailCurrentPositionSchema,
                        auditCurrentPositionSchema, auditEventSchema, auditBusinessValuesSchema, auditPostingReceiptSchema,
                        auditCorrectionReceiptSchema, auditFactSchema).stream()
                .map(this::schemaPointer).map(document::at).map(JsonNode::toString)
                .collect(java.util.stream.Collectors.joining());
        assertThat(transactionNestedSchemas).doesNotContain("projectionVersion", "lastTransactionId", "requestHash",
                "idempotencyKey", "replayDigest", "correctionGroupId", "internalTrace", "constraintName",
                "stackTrace", "userId");
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
                + document.at("/components/schemas/InvestmentPositionDetail")
                + document.at("/components/schemas/InvestmentLogicalTransactionListItem")
                + document.at("/components/schemas/InvestmentTransactionDetail")
                + document.at("/components/schemas/InvestmentTransactionAuditTimeline");
        assertThat(readContract).doesNotContain("projectionVersion", "lastTransactionId", "requestHash",
                "idempotencyKey", "replayDigest", "correctionGroupId", "internalTrace", "constraintName",
                "stackTrace", "userId");
        assertThat(document.at("/paths/~1api~1v1~1investment~1transactions~1{logicalTransactionId}~1audit-timeline/get/parameters"))
                .extracting(parameter -> parameter.path("name").asText())
                .doesNotContain("cursor", "size");
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
