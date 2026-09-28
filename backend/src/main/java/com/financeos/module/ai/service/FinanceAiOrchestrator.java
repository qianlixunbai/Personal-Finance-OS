package com.financeos.module.ai.service;

import com.financeos.module.ai.dto.AiResponse;
import com.financeos.module.ai.dto.toolcalling.AiToolCall;
import com.financeos.module.ai.dto.toolcalling.AiToolMessage;
import com.financeos.module.ai.dto.toolcalling.AiToolRequest;
import com.financeos.module.ai.dto.toolcalling.AiToolResponse;
import com.financeos.module.ai.provider.AiProviderException;
import com.financeos.module.ai.provider.AiToolCallingProvider;
import com.financeos.module.ai.tool.FinanceToolDispatcher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class FinanceAiOrchestrator {

    private static final int MAX_TOOL_ROUNDS = 3;
    private static final int MAX_TOTAL_TOOL_CALLS = 5;
    private static final String SYSTEM_PROMPT = """
            你是 Personal Finance OS 的只读财务助手。金融事实只能来自 Finance Tools。
            清楚区分账务真值与缓存市场参考估值；参考估值不是账务余额。
            只依据工具返回的数据回答；数据缺失或不确定时明确说明，不要编造数字或事实。
            你只能查询数据，不得声称或尝试执行转账、交易、修改或刷新数据。
            """;

    private final AiToolCallingProvider provider;
    private final FinanceToolDispatcher dispatcher;

    public FinanceAiOrchestrator(AiToolCallingProvider provider, FinanceToolDispatcher dispatcher) {
        this.provider = provider;
        this.dispatcher = dispatcher;
    }

    public AiResponse ask(Long authenticatedUserId, String question) {
        validateRequest(authenticatedUserId, question);

        List<AiToolMessage> messages = new ArrayList<>();
        messages.add(new AiToolMessage(AiToolMessage.Role.SYSTEM, SYSTEM_PROMPT, null, null));
        messages.add(new AiToolMessage(AiToolMessage.Role.USER, question, null, null));

        Set<String> usedCallIds = new HashSet<>();
        int toolRounds = 0;
        int totalToolCalls = 0;

        while (true) {
            AiToolResponse response;
            try {
                response = provider.next(new AiToolRequest(List.copyOf(messages), dispatcher.definitions()));
            } catch (AiProviderException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new FinanceAiException(FinanceAiErrorType.PROVIDER_ERROR);
            }
            if (response == null) {
                throw new FinanceAiException(FinanceAiErrorType.INVALID_PROVIDER_RESPONSE);
            }

            List<AiToolCall> calls = response.toolCalls();
            if (calls == null || calls.isEmpty()) {
                if (!StringUtils.hasText(response.content())) {
                    throw new FinanceAiException(FinanceAiErrorType.INVALID_PROVIDER_RESPONSE);
                }
                return new AiResponse(response.content());
            }

            preflightBatch(calls, usedCallIds, toolRounds, totalToolCalls);
            messages.add(new AiToolMessage(AiToolMessage.Role.ASSISTANT, response.content(), null, calls));
            toolRounds++;
            totalToolCalls += calls.size();
            for (AiToolCall call : calls) {
                usedCallIds.add(call.id());
                String factJson = dispatcher.execute(authenticatedUserId, call);
                messages.add(new AiToolMessage(AiToolMessage.Role.TOOL, factJson, call.id(), null));
            }
        }
    }

    private void preflightBatch(
            List<AiToolCall> calls, Set<String> usedCallIds, int toolRounds, int totalToolCalls) {
        int batchSize = calls.size();
        if (batchSize > MAX_TOTAL_TOOL_CALLS - totalToolCalls) {
            throw new FinanceAiException(FinanceAiErrorType.TOOL_BUDGET_EXCEEDED);
        }
        if (toolRounds >= MAX_TOOL_ROUNDS) {
            throw new FinanceAiException(FinanceAiErrorType.TOOL_ROUND_LIMIT_EXCEEDED);
        }

        Set<String> batchCallIds = new HashSet<>();
        for (AiToolCall call : calls) {
            if (call == null || !StringUtils.hasText(call.id())
                    || usedCallIds.contains(call.id()) || !batchCallIds.add(call.id())) {
                throw new FinanceAiException(FinanceAiErrorType.INVALID_TOOL_CALL);
            }
        }
        for (AiToolCall call : calls) {
            dispatcher.validate(call);
        }
    }

    private void validateRequest(Long authenticatedUserId, String question) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new FinanceAiException(FinanceAiErrorType.INVALID_USER);
        }
        if (!StringUtils.hasText(question)) {
            throw new FinanceAiException(FinanceAiErrorType.INVALID_QUESTION);
        }
    }
}
