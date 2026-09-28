package com.financeos.module.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.ai.dto.AiResponse;
import com.financeos.module.ai.dto.toolcalling.AiToolCall;
import com.financeos.module.ai.dto.toolcalling.AiToolDefinition;
import com.financeos.module.ai.dto.toolcalling.AiToolMessage;
import com.financeos.module.ai.dto.toolcalling.AiToolRequest;
import com.financeos.module.ai.dto.toolcalling.AiToolResponse;
import com.financeos.module.ai.provider.AiErrorType;
import com.financeos.module.ai.provider.AiProviderException;
import com.financeos.module.ai.provider.AiToolCallingProvider;
import com.financeos.module.ai.tool.FinanceToolDispatcher;
import com.financeos.module.ai.tool.FinancialOverviewTool;
import com.financeos.module.ai.tool.InvestmentPortfolioSummaryTool;
import com.financeos.module.ai.tool.MonthlyCashFlowTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FinanceAiOrchestratorTest {
    private static final long AUTHENTICATED_USER_ID = 73L;
    private static final String PROVIDER_PRIVATE_DETAIL = "provider-private-detail-marker";
    private static final String TOOL_PRIVATE_DETAIL = "tool-private-detail-marker";

    private final AiToolCallingProvider provider = mock(AiToolCallingProvider.class);
    private final FinanceToolDispatcher dispatcher = mock(FinanceToolDispatcher.class);
    private final FinanceAiOrchestrator orchestrator = new FinanceAiOrchestrator(provider, dispatcher);

    @BeforeEach
    void setUp() {
        when(dispatcher.definitions()).thenReturn(List.of(
                new AiToolDefinition("getFinancialOverview", "Read financial overview", java.util.Map.of()),
                new AiToolDefinition("getMonthlyCashFlow", "Read monthly cash flow", java.util.Map.of()),
                new AiToolDefinition("getInvestmentPortfolioSummary", "Read investment portfolio summary",
                        java.util.Map.of())));
    }

    @Test
    void returnsProviderFinalAnswerAndSuppliesOnlyTheRequiredFinanceSystemGuidance() throws Exception {
        when(provider.next(any(AiToolRequest.class)))
                .thenReturn(new AiToolResponse("You had a positive cash flow.", List.of()));

        AiResponse response = orchestrator.ask(AUTHENTICATED_USER_ID, "Summarize my current finances");

        assertThat(response).isEqualTo(new AiResponse("You had a positive cash flow."));
        ArgumentCaptor<AiToolRequest> requestCaptor = ArgumentCaptor.forClass(AiToolRequest.class);
        verify(provider).next(requestCaptor.capture());
        AiToolRequest request = requestCaptor.getValue();
        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().get(0).role()).isEqualTo(AiToolMessage.Role.SYSTEM);
        assertThat(request.messages().get(0).content()).contains(
                "Finance Tools", "账务真值", "缓存市场参考估值", "缺失", "转账", "交易");
        assertThat(request.messages().get(1)).isEqualTo(new AiToolMessage(
                AiToolMessage.Role.USER, "Summarize my current finances", null, null));

        String serializedRequest = new ObjectMapper().writeValueAsString(request);
        assertThat(serializedRequest)
                .doesNotContain(Long.toString(AUTHENTICATED_USER_ID), "userId", "authenticatedUserId");
        verify(dispatcher).definitions();
        verify(dispatcher, never()).execute(any(), any());
    }

    @Test
    void executesOneToolAndReturnsTheFinalProviderAnswerWithTheFinanceFactInTheNextTurn() {
        AiToolCall call = new AiToolCall("call-overview", "getFinancialOverview", "{}");
        String factJson = "{\"currency\":\"CNY\",\"accountingNetWorth\":900.00}";
        when(provider.next(any(AiToolRequest.class))).thenReturn(
                new AiToolResponse(null, List.of(call)),
                new AiToolResponse("Your accounting net worth is CNY 900.", List.of()));
        when(dispatcher.execute(AUTHENTICATED_USER_ID, call)).thenReturn(factJson);

        AiResponse response = orchestrator.ask(AUTHENTICATED_USER_ID, "What is my net worth?");

        assertThat(response.content()).isEqualTo("Your accounting net worth is CNY 900.");
        ArgumentCaptor<AiToolRequest> requestCaptor = ArgumentCaptor.forClass(AiToolRequest.class);
        verify(provider, times(2)).next(requestCaptor.capture());
        List<AiToolMessage> nextTurnMessages = requestCaptor.getAllValues().get(1).messages();
        assertThat(nextTurnMessages).hasSize(4);
        assertThat(nextTurnMessages.get(2)).isEqualTo(new AiToolMessage(
                AiToolMessage.Role.ASSISTANT, null, null, List.of(call)));
        assertThat(nextTurnMessages.get(3)).isEqualTo(new AiToolMessage(
                AiToolMessage.Role.TOOL, factJson, "call-overview", null));
        verify(dispatcher).validate(call);
        verify(dispatcher).execute(AUTHENTICATED_USER_ID, call);
    }

    @Test
    void carriesFactsAcrossMultipleRoundsAndExecutesEveryRequestedToolWithTrustedIdentity() {
        AiToolCall firstCall = new AiToolCall("call-overview", "getFinancialOverview", "{}");
        AiToolCall secondCall = new AiToolCall("call-month", "getMonthlyCashFlow",
                "{\"fromMonth\":\"2025-12\",\"throughMonth\":\"2025-12\"}");
        AiToolCall thirdCall = new AiToolCall("call-portfolio", "getInvestmentPortfolioSummary", "{}");
        String overviewFact = "{\"accountingNetWorth\":900.00}";
        String monthlyFact = "{\"month\":\"2025-12\",\"net\":200.00}";
        String portfolioFact = "{\"openTotalCost\":500.00,\"referenceValuation\":null}";
        when(provider.next(any(AiToolRequest.class))).thenReturn(
                new AiToolResponse(null, List.of(firstCall, secondCall)),
                new AiToolResponse(null, List.of(thirdCall)),
                new AiToolResponse("The financial overview and investment summary are ready.", List.of()));
        when(dispatcher.execute(AUTHENTICATED_USER_ID, firstCall)).thenReturn(overviewFact);
        when(dispatcher.execute(AUTHENTICATED_USER_ID, secondCall)).thenReturn(monthlyFact);
        when(dispatcher.execute(AUTHENTICATED_USER_ID, thirdCall)).thenReturn(portfolioFact);

        AiResponse response = orchestrator.ask(AUTHENTICATED_USER_ID, "Summarize my finances and portfolio");

        assertThat(response.content()).isEqualTo(
                "The financial overview and investment summary are ready.");
        ArgumentCaptor<AiToolRequest> requestCaptor = ArgumentCaptor.forClass(AiToolRequest.class);
        verify(provider, times(3)).next(requestCaptor.capture());
        List<AiToolMessage> secondRoundMessages = requestCaptor.getAllValues().get(1).messages();
        assertThat(secondRoundMessages).extracting(AiToolMessage::role).containsExactly(
                AiToolMessage.Role.SYSTEM,
                AiToolMessage.Role.USER,
                AiToolMessage.Role.ASSISTANT,
                AiToolMessage.Role.TOOL,
                AiToolMessage.Role.TOOL);
        assertThat(secondRoundMessages).extracting(AiToolMessage::content)
                .contains(overviewFact, monthlyFact);

        List<AiToolMessage> finalTurnMessages = requestCaptor.getAllValues().get(2).messages();
        assertThat(finalTurnMessages).extracting(AiToolMessage::content)
                .contains(overviewFact, monthlyFact, portfolioFact);
        verify(dispatcher).execute(AUTHENTICATED_USER_ID, firstCall);
        verify(dispatcher).execute(AUTHENTICATED_USER_ID, secondCall);
        verify(dispatcher).execute(AUTHENTICATED_USER_ID, thirdCall);
    }

    @Test
    void rejectsAnOverBudgetSameRoundBeforeValidatingOrExecutingAnyCallInThatBatch() {
        when(provider.next(any(AiToolRequest.class)))
                .thenReturn(new AiToolResponse(null, calls(6, "call-batch-")));

        assertFinanceError(FinanceAiErrorType.TOOL_BUDGET_EXCEEDED,
                () -> orchestrator.ask(AUTHENTICATED_USER_ID, "Read my finances"));

        verify(dispatcher).definitions();
        verify(dispatcher, never()).validate(any());
        verify(dispatcher, never()).execute(any(), any());
    }

    @Test
    void rejectsAnOverBudgetLaterBatchWithoutPartiallyExecutingIt() {
        List<AiToolCall> firstBatch = calls(3, "call-first-");
        List<AiToolCall> secondBatch = calls(3, "call-second-");
        when(provider.next(any(AiToolRequest.class))).thenReturn(
                new AiToolResponse(null, firstBatch),
                new AiToolResponse(null, secondBatch));
        when(dispatcher.execute(eq(AUTHENTICATED_USER_ID), any(AiToolCall.class)))
                .thenReturn("{} ");

        assertFinanceError(FinanceAiErrorType.TOOL_BUDGET_EXCEEDED,
                () -> orchestrator.ask(AUTHENTICATED_USER_ID, "Read my finances"));

        for (AiToolCall call : firstBatch) {
            verify(dispatcher).execute(AUTHENTICATED_USER_ID, call);
        }
        for (AiToolCall call : secondBatch) {
            verify(dispatcher, never()).execute(AUTHENTICATED_USER_ID, call);
            verify(dispatcher, never()).validate(call);
        }
    }

    @Test
    void stopsAfterThreeToolRequestRoundsBeforeExecutingTheFourthRound() {
        List<AiToolCall> firstThreeRounds = List.of(
                new AiToolCall("call-round-1", "getFinancialOverview", "{}"),
                new AiToolCall("call-round-2", "getFinancialOverview", "{}"),
                new AiToolCall("call-round-3", "getFinancialOverview", "{}"));
        AiToolCall fourthRoundCall = new AiToolCall("call-round-4", "getFinancialOverview", "{}");
        when(provider.next(any(AiToolRequest.class))).thenReturn(
                new AiToolResponse(null, List.of(firstThreeRounds.get(0))),
                new AiToolResponse(null, List.of(firstThreeRounds.get(1))),
                new AiToolResponse(null, List.of(firstThreeRounds.get(2))),
                new AiToolResponse(null, List.of(fourthRoundCall)));
        when(dispatcher.execute(eq(AUTHENTICATED_USER_ID), any(AiToolCall.class)))
                .thenReturn("{}");

        assertFinanceError(FinanceAiErrorType.TOOL_ROUND_LIMIT_EXCEEDED,
                () -> orchestrator.ask(AUTHENTICATED_USER_ID, "Read my finances"));

        for (AiToolCall call : firstThreeRounds) {
            verify(dispatcher).execute(AUTHENTICATED_USER_ID, call);
        }
        verify(dispatcher, never()).validate(fourthRoundCall);
        verify(dispatcher, never()).execute(AUTHENTICATED_USER_ID, fourthRoundCall);
    }

    @Test
    void mapsProviderFailureToAStableSanitizedFinanceError() {
        when(provider.next(any(AiToolRequest.class)))
                .thenThrow(new IllegalStateException("upstream detail " + PROVIDER_PRIVATE_DETAIL));

        assertThatThrownBy(() -> orchestrator.ask(AUTHENTICATED_USER_ID, "Read my finances"))
                .isInstanceOf(FinanceAiException.class)
                .satisfies(exception -> {
                    FinanceAiException financeException = (FinanceAiException) exception;
                    assertThat(financeException.getErrorType()).isEqualTo(FinanceAiErrorType.PROVIDER_ERROR);
                    assertThat(financeException.getMessage())
                            .isEqualTo(FinanceAiErrorType.PROVIDER_ERROR.getSafeMessage())
                            .doesNotContain(PROVIDER_PRIVATE_DETAIL, "upstream detail");
                    assertThat(financeException.getCause()).isNull();
                });

        verifyNoInteractionsAfterDefinitionsOnly();
    }

    @ParameterizedTest
    @EnumSource(value = AiErrorType.class, names = {"AUTHENTICATION", "RATE_LIMITED", "TIMEOUT"})
    void preservesStableProviderErrorCategoriesAndMessages(AiErrorType providerErrorType) {
        when(provider.next(any(AiToolRequest.class)))
                .thenThrow(new AiProviderException(providerErrorType));

        assertThatThrownBy(() -> orchestrator.ask(AUTHENTICATED_USER_ID, "Read my finances"))
                .isInstanceOf(AiProviderException.class)
                .satisfies(exception -> {
                    AiProviderException providerException = (AiProviderException) exception;
                    assertThat(providerException.getErrorType()).isEqualTo(providerErrorType);
                    assertThat(providerException.getMessage()).isEqualTo(providerErrorType.getSafeMessage());
                    assertThat(providerException.getCause()).isNull();
                });

        verifyNoInteractionsAfterDefinitionsOnly();
    }

    @Test
    void preservesOnlyTheStableSanitizedErrorWhenADispatchedToolFails() {
        AiToolCall call = new AiToolCall("call-failure", "getFinancialOverview", "{}");
        FinancialOverviewTool overviewTool = mock(FinancialOverviewTool.class);
        when(overviewTool.getFinancialOverview(AUTHENTICATED_USER_ID))
                .thenThrow(new IllegalStateException("database detail " + TOOL_PRIVATE_DETAIL));
        FinanceToolDispatcher realDispatcher = new FinanceToolDispatcher(
                overviewTool, mock(MonthlyCashFlowTool.class),
                mock(InvestmentPortfolioSummaryTool.class), new ObjectMapper());
        FinanceAiOrchestrator orchestratorWithReadFailure =
                new FinanceAiOrchestrator(provider, realDispatcher);
        when(provider.next(any(AiToolRequest.class))).thenReturn(new AiToolResponse(null, List.of(call)));

        assertThatThrownBy(() -> orchestratorWithReadFailure.ask(AUTHENTICATED_USER_ID, "Read my finances"))
                .isInstanceOf(FinanceAiException.class)
                .satisfies(exception -> {
                    FinanceAiException financeException = (FinanceAiException) exception;
                    assertThat(financeException.getErrorType()).isEqualTo(FinanceAiErrorType.FINANCE_SYSTEM_ERROR);
                    assertThat(financeException.getMessage())
                            .isEqualTo(FinanceAiErrorType.FINANCE_SYSTEM_ERROR.getSafeMessage())
                            .doesNotContain(TOOL_PRIVATE_DETAIL, "database detail");
                    assertThat(financeException.getCause()).isNull();
                });
    }

    @Test
    void rejectsMissingFinalAnswerWithAStableSanitizedError() {
        when(provider.next(any(AiToolRequest.class))).thenReturn(new AiToolResponse(null, List.of()));

        assertFinanceError(FinanceAiErrorType.INVALID_PROVIDER_RESPONSE,
                () -> orchestrator.ask(AUTHENTICATED_USER_ID, "Read my finances"));

        verify(dispatcher, never()).execute(any(), any());
    }

    @Test
    void rejectsBlankFinalAnswerAsMalformedProviderOutput() {
        when(provider.next(any(AiToolRequest.class))).thenReturn(new AiToolResponse("  ", List.of()));

        assertFinanceError(FinanceAiErrorType.INVALID_PROVIDER_RESPONSE,
                () -> orchestrator.ask(AUTHENTICATED_USER_ID, "Read my finances"));

        verify(dispatcher, never()).execute(any(), any());
    }

    private List<AiToolCall> calls(int count, String prefix) {
        List<AiToolCall> calls = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            calls.add(new AiToolCall(prefix + index, "getFinancialOverview", "{}"));
        }
        return calls;
    }

    private void assertFinanceError(FinanceAiErrorType errorType, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(FinanceAiException.class)
                .satisfies(exception -> {
                    FinanceAiException financeException = (FinanceAiException) exception;
                    assertThat(financeException.getErrorType()).isEqualTo(errorType);
                    assertThat(financeException.getMessage()).isEqualTo(errorType.getSafeMessage());
                    assertThat(financeException.getCause()).isNull();
                });
    }

    private void verifyNoInteractionsAfterDefinitionsOnly() {
        verify(dispatcher).definitions();
        verify(dispatcher, never()).validate(any());
        verify(dispatcher, never()).execute(any(), any());
    }
}
