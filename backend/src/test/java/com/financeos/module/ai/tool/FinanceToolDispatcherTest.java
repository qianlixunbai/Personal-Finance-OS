package com.financeos.module.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.ai.dto.toolcalling.AiToolCall;
import com.financeos.module.ai.dto.toolcalling.AiToolDefinition;
import com.financeos.module.ai.service.FinanceAiErrorType;
import com.financeos.module.ai.service.FinanceAiException;
import com.financeos.module.ai.tool.dto.FinancialOverviewFact;
import com.financeos.module.ai.tool.dto.MonthlyCashFlowFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FinanceToolDispatcherTest {
    private static final long AUTHENTICATED_USER_ID = 73L;
    private static final String PRIVATE_ARGUMENT = "private-tool-argument-marker";

    @Test
    void advertisesOnlyTheThreeReadOnlyFinanceToolsWithClosedArgumentSchemas() {
        FinanceToolDispatcher dispatcher = dispatcher();

        List<AiToolDefinition> definitions = dispatcher.definitions();

        assertThat(definitions).extracting(AiToolDefinition::name).containsExactly(
                "getFinancialOverview",
                "getMonthlyCashFlow",
                "getInvestmentPortfolioSummary");
        assertThat(definitions.get(0).parameters())
                .containsEntry("type", "object")
                .containsEntry("properties", Map.of())
                .containsEntry("required", List.of())
                .containsEntry("additionalProperties", false);
        assertThat(definitions.get(1).parameters())
                .containsEntry("type", "object")
                .containsEntry("required", List.of("fromMonth", "throughMonth"))
                .containsEntry("additionalProperties", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> monthProperties = (Map<String, Object>) definitions.get(1)
                .parameters().get("properties");
        assertThat(monthProperties).containsOnlyKeys("fromMonth", "throughMonth");
        assertThat(definitions.get(2).parameters())
                .containsEntry("type", "object")
                .containsEntry("properties", Map.of())
                .containsEntry("required", List.of())
                .containsEntry("additionalProperties", false);
    }

    @Test
    void dispatcherDependsOnlyOnTheThreeExistingFinanceReadToolsAndJsonSerialization() {
        List<String> instanceDependencies = Arrays.stream(FinanceToolDispatcher.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getName())
                .toList();

        assertThat(instanceDependencies).containsExactlyInAnyOrder(
                FinancialOverviewTool.class.getName(),
                MonthlyCashFlowTool.class.getName(),
                InvestmentPortfolioSummaryTool.class.getName(),
                ObjectMapper.class.getName());
    }

    @Test
    void passesTrustedUserIdToFinancialOverviewToolAndSerializesOnlyItsFinanceFact() throws Exception {
        FinancialOverviewTool overviewTool = mock(FinancialOverviewTool.class);
        FinancialOverviewFact fact = new FinancialOverviewFact(
                "CNY", new BigDecimal("1000.00"), new BigDecimal("900.00"),
                "2025-12", new BigDecimal("300.00"), new BigDecimal("100.00"),
                new BigDecimal("200.00"));
        when(overviewTool.getFinancialOverview(AUTHENTICATED_USER_ID)).thenReturn(fact);
        FinanceToolDispatcher dispatcher = dispatcher(overviewTool,
                mock(MonthlyCashFlowTool.class), mock(InvestmentPortfolioSummaryTool.class));

        String result = dispatcher.execute(AUTHENTICATED_USER_ID,
                new AiToolCall("call-overview", "getFinancialOverview", "{}"));

        assertThat(new ObjectMapper().readTree(result)).isEqualTo(new ObjectMapper().readTree(
                """
                {"currency":"CNY","accountingTotalAssets":1000.00,"accountingNetWorth":900.00,
                 "currentMonth":"2025-12","currentMonthIncome":300.00,"currentMonthExpense":100.00,
                 "currentMonthNetCashFlow":200.00}
                """));
        verify(overviewTool).getFinancialOverview(AUTHENTICATED_USER_ID);
    }

    @Test
    void passesTrustedUserIdAndInclusiveOneMonthRangeToMonthlyTool() {
        MonthlyCashFlowTool monthlyTool = mock(MonthlyCashFlowTool.class);
        when(monthlyTool.getMonthlyCashFlow(AUTHENTICATED_USER_ID,
                YearMonth.of(2025, 1), YearMonth.of(2025, 1)))
                .thenReturn(new MonthlyCashFlowFact("CNY", "2025-01", "2025-01", List.of()));
        FinanceToolDispatcher dispatcher = dispatcher(mock(FinancialOverviewTool.class), monthlyTool,
                mock(InvestmentPortfolioSummaryTool.class));

        dispatcher.execute(AUTHENTICATED_USER_ID,
                monthlyCall("2025-01", "2025-01"));

        verify(monthlyTool).getMonthlyCashFlow(AUTHENTICATED_USER_ID,
                YearMonth.of(2025, 1), YearMonth.of(2025, 1));
    }

    @Test
    void acceptsAnInclusiveTwelveMonthRange() {
        MonthlyCashFlowTool monthlyTool = mock(MonthlyCashFlowTool.class);
        when(monthlyTool.getMonthlyCashFlow(AUTHENTICATED_USER_ID,
                YearMonth.of(2025, 1), YearMonth.of(2025, 12)))
                .thenReturn(new MonthlyCashFlowFact("CNY", "2025-01", "2025-12", List.of()));
        FinanceToolDispatcher dispatcher = dispatcher(mock(FinancialOverviewTool.class), monthlyTool,
                mock(InvestmentPortfolioSummaryTool.class));

        dispatcher.execute(AUTHENTICATED_USER_ID,
                monthlyCall("2025-01", "2025-12"));

        verify(monthlyTool).getMonthlyCashFlow(AUTHENTICATED_USER_ID,
                YearMonth.of(2025, 1), YearMonth.of(2025, 12));
    }

    @Test
    void rejectsThirteenMonthRangeBeforeCallingAnyFinanceTool() {
        FinanceToolDispatcher dispatcher = dispatcher();

        assertFinanceError(() -> dispatcher.execute(AUTHENTICATED_USER_ID,
                monthlyCall("2025-01", "2026-01")), FinanceAiErrorType.INVALID_TOOL_ARGUMENTS);

        verifyNoFinanceToolInteractions();
    }

    @ParameterizedTest
    @MethodSource("forbiddenArguments")
    void rejectsModelSuppliedIdentityAndUnexpectedFieldsBeforeCallingTools(String toolName,
                                                                           String argumentsJson) {
        FinanceToolDispatcher dispatcher = dispatcher();

        assertFinanceError(() -> dispatcher.execute(AUTHENTICATED_USER_ID,
                new AiToolCall("call-invalid", toolName, argumentsJson)),
                FinanceAiErrorType.INVALID_TOOL_ARGUMENTS);

        verifyNoFinanceToolInteractions();
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> forbiddenArguments() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("getFinancialOverview", "{\"userId\":999}"),
                org.junit.jupiter.params.provider.Arguments.of("getFinancialOverview", "{\"authenticatedUserId\":999}"),
                org.junit.jupiter.params.provider.Arguments.of("getInvestmentPortfolioSummary", "{\"userId\":999}"),
                org.junit.jupiter.params.provider.Arguments.of("getInvestmentPortfolioSummary", "{\"authenticatedUserId\":999}"),
                org.junit.jupiter.params.provider.Arguments.of("getMonthlyCashFlow",
                        "{\"fromMonth\":\"2025-01\",\"throughMonth\":\"2025-01\",\"userId\":999}"),
                org.junit.jupiter.params.provider.Arguments.of("getMonthlyCashFlow",
                        "{\"fromMonth\":\"2025-01\",\"throughMonth\":\"2025-01\",\"authenticatedUserId\":999}"),
                org.junit.jupiter.params.provider.Arguments.of("getFinancialOverview", "{\"write\":true}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "\"text\"", "{not-json", "{} {}", "{\"x\":1,\"x\":2}"})
    void rejectsNonObjectMalformedOrAmbiguousArgumentsBeforeCallingTools(String argumentsJson) {
        FinanceToolDispatcher dispatcher = dispatcher();

        assertFinanceError(() -> dispatcher.execute(AUTHENTICATED_USER_ID,
                new AiToolCall("call-invalid", "getFinancialOverview", argumentsJson)),
                FinanceAiErrorType.INVALID_TOOL_ARGUMENTS);

        verifyNoFinanceToolInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-00", "2025-13", "2025-1", "2025-01-01"})
    void rejectsMonthsOutsideStrictYearMonthFormatBeforeCallingMonthlyTool(String month) {
        FinanceToolDispatcher dispatcher = dispatcher();

        assertFinanceError(() -> dispatcher.execute(AUTHENTICATED_USER_ID,
                monthlyCall(month, "2025-12")), FinanceAiErrorType.INVALID_TOOL_ARGUMENTS);

        verifyNoFinanceToolInteractions();
    }

    @ParameterizedTest
    @MethodSource("invalidMonthRanges")
    void rejectsReversedOrOverlongMonthRangesBeforeCallingMonthlyTool(String fromMonth, String throughMonth) {
        FinanceToolDispatcher dispatcher = dispatcher();

        assertFinanceError(() -> dispatcher.execute(AUTHENTICATED_USER_ID,
                monthlyCall(fromMonth, throughMonth)), FinanceAiErrorType.INVALID_TOOL_ARGUMENTS);

        verifyNoFinanceToolInteractions();
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> invalidMonthRanges() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("2025-02", "2025-01"),
                org.junit.jupiter.params.provider.Arguments.of("2025-01", "2026-01"));
    }

    @Test
    void rejectsUnknownToolsBeforeCallingAnyFinanceTool() {
        FinanceToolDispatcher dispatcher = dispatcher();

        assertFinanceError(() -> dispatcher.execute(AUTHENTICATED_USER_ID,
                new AiToolCall("call-unknown", "deleteAccount", "{}")),
                FinanceAiErrorType.UNSUPPORTED_TOOL);

        verifyNoFinanceToolInteractions();
    }

    @Test
    void rejectsInvalidAuthenticatedUserBeforeCallingAnyFinanceTool() {
        FinanceToolDispatcher dispatcher = dispatcher();

        assertFinanceError(() -> dispatcher.execute(0L,
                new AiToolCall("call-overview", "getFinancialOverview", "{}")),
                FinanceAiErrorType.INVALID_USER);

        verifyNoFinanceToolInteractions();
    }

    @Test
    void sanitizesReadToolFailureAndDoesNotRetainCauseOrProviderDetails() {
        FinancialOverviewTool overviewTool = mock(FinancialOverviewTool.class);
        when(overviewTool.getFinancialOverview(AUTHENTICATED_USER_ID))
                .thenThrow(new IllegalStateException("database detail " + PRIVATE_ARGUMENT));
        FinanceToolDispatcher dispatcher = dispatcher(overviewTool,
                mock(MonthlyCashFlowTool.class), mock(InvestmentPortfolioSummaryTool.class));

        assertThatThrownBy(() -> dispatcher.execute(AUTHENTICATED_USER_ID,
                new AiToolCall("call-overview", "getFinancialOverview", "{}")))
                .isInstanceOf(FinanceAiException.class)
                .satisfies(exception -> {
                    FinanceAiException financeException = (FinanceAiException) exception;
                    assertThat(financeException.getErrorType()).isEqualTo(FinanceAiErrorType.FINANCE_SYSTEM_ERROR);
                    assertThat(financeException.getMessage()).isEqualTo(
                            FinanceAiErrorType.FINANCE_SYSTEM_ERROR.getSafeMessage());
                    assertThat(financeException.getMessage()).doesNotContain(PRIVATE_ARGUMENT, "database detail");
                    assertThat(financeException.getCause()).isNull();
                });
    }

    private FinanceToolDispatcher dispatcher() {
        return dispatcher(financialOverviewTool, monthlyCashFlowTool, investmentPortfolioSummaryTool);
    }

    private FinanceToolDispatcher dispatcher(FinancialOverviewTool overviewTool,
                                              MonthlyCashFlowTool monthlyTool,
                                              InvestmentPortfolioSummaryTool portfolioTool) {
        return new FinanceToolDispatcher(overviewTool, monthlyTool, portfolioTool, new ObjectMapper());
    }

    private AiToolCall monthlyCall(String fromMonth, String throughMonth) {
        return new AiToolCall("call-monthly", "getMonthlyCashFlow",
                "{\"fromMonth\":\"" + fromMonth + "\",\"throughMonth\":\"" + throughMonth + "\"}");
    }

    private void assertFinanceError(Runnable invocation, FinanceAiErrorType expectedType) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(FinanceAiException.class)
                .satisfies(exception -> assertThat(((FinanceAiException) exception).getErrorType())
                        .isEqualTo(expectedType));
    }

    private void verifyNoFinanceToolInteractions() {
        verifyNoInteractions(financialOverviewTool, monthlyCashFlowTool, investmentPortfolioSummaryTool);
    }

    private final FinancialOverviewTool financialOverviewTool = mock(FinancialOverviewTool.class);
    private final MonthlyCashFlowTool monthlyCashFlowTool = mock(MonthlyCashFlowTool.class);
    private final InvestmentPortfolioSummaryTool investmentPortfolioSummaryTool =
            mock(InvestmentPortfolioSummaryTool.class);
}
