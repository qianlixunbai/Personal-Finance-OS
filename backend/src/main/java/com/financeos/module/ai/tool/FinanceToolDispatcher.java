package com.financeos.module.ai.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.BusinessException;
import com.financeos.module.ai.dto.toolcalling.AiToolCall;
import com.financeos.module.ai.dto.toolcalling.AiToolDefinition;
import com.financeos.module.ai.service.FinanceAiErrorType;
import com.financeos.module.ai.service.FinanceAiException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
public class FinanceToolDispatcher {

    private static final String FINANCIAL_OVERVIEW = "getFinancialOverview";
    private static final String MONTHLY_CASH_FLOW = "getMonthlyCashFlow";
    private static final String INVESTMENT_PORTFOLIO_SUMMARY = "getInvestmentPortfolioSummary";
    private static final String MONTH_PATTERN = "^\\d{4}-(0[1-9]|1[0-2])$";
    private static final List<AiToolDefinition> DEFINITIONS = List.of(
            new AiToolDefinition(
                    FINANCIAL_OVERVIEW,
                    "Returns the authenticated user's accounting overview for the current month.",
                    objectSchema(Map.of(), List.of())),
            new AiToolDefinition(
                    MONTHLY_CASH_FLOW,
                    "Returns monthly income, expense, and net cash flow for an inclusive month range of 1 to 12 months.",
                    objectSchema(Map.of(
                            "fromMonth", Map.of("type", "string", "pattern", MONTH_PATTERN),
                            "throughMonth", Map.of("type", "string", "pattern", MONTH_PATTERN)),
                            List.of("fromMonth", "throughMonth"))),
            new AiToolDefinition(
                    INVESTMENT_PORTFOLIO_SUMMARY,
                    "Returns the authenticated user's transaction-driven investment summary and separate reference valuation.",
                    objectSchema(Map.of(), List.of()))
    );

    private final FinancialOverviewTool financialOverviewTool;
    private final MonthlyCashFlowTool monthlyCashFlowTool;
    private final InvestmentPortfolioSummaryTool investmentPortfolioSummaryTool;
    private final ObjectMapper objectMapper;

    public FinanceToolDispatcher(
            FinancialOverviewTool financialOverviewTool,
            MonthlyCashFlowTool monthlyCashFlowTool,
            InvestmentPortfolioSummaryTool investmentPortfolioSummaryTool,
            ObjectMapper objectMapper) {
        this.financialOverviewTool = financialOverviewTool;
        this.monthlyCashFlowTool = monthlyCashFlowTool;
        this.investmentPortfolioSummaryTool = investmentPortfolioSummaryTool;
        this.objectMapper = objectMapper;
    }

    public List<AiToolDefinition> definitions() {
        return DEFINITIONS;
    }

    /** Validates a call without invoking its Finance read service. */
    public void validate(AiToolCall call) {
        parseCall(call);
    }

    public String execute(Long authenticatedUserId, AiToolCall call) {
        validateAuthenticatedUserId(authenticatedUserId);
        ParsedCall parsed = parseCall(call);

        Object fact;
        try {
            fact = switch (parsed.name()) {
                case FINANCIAL_OVERVIEW -> financialOverviewTool.getFinancialOverview(authenticatedUserId);
                case MONTHLY_CASH_FLOW -> monthlyCashFlowTool.getMonthlyCashFlow(
                        authenticatedUserId, parsed.fromMonth(), parsed.throughMonth());
                case INVESTMENT_PORTFOLIO_SUMMARY ->
                        investmentPortfolioSummaryTool.getInvestmentPortfolioSummary(authenticatedUserId);
                default -> throw new FinanceAiException(FinanceAiErrorType.UNSUPPORTED_TOOL);
            };
        } catch (BusinessException exception) {
            throw new FinanceAiException(FinanceAiErrorType.FINANCE_READ_FAILED);
        } catch (FinanceAiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new FinanceAiException(FinanceAiErrorType.FINANCE_SYSTEM_ERROR);
        }

        if (fact == null) {
            throw new FinanceAiException(FinanceAiErrorType.FINANCE_SYSTEM_ERROR);
        }
        try {
            return objectMapper.writeValueAsString(fact);
        } catch (JsonProcessingException exception) {
            throw new FinanceAiException(FinanceAiErrorType.FINANCE_SERIALIZATION_ERROR);
        } catch (RuntimeException exception) {
            throw new FinanceAiException(FinanceAiErrorType.FINANCE_SERIALIZATION_ERROR);
        }
    }

    private ParsedCall parseCall(AiToolCall call) {
        if (call == null || call.id() == null || call.id().isBlank()
                || call.name() == null || call.name().isBlank()) {
            throw new FinanceAiException(FinanceAiErrorType.INVALID_TOOL_CALL);
        }
        if (!FINANCIAL_OVERVIEW.equals(call.name())
                && !MONTHLY_CASH_FLOW.equals(call.name())
                && !INVESTMENT_PORTFOLIO_SUMMARY.equals(call.name())) {
            throw new FinanceAiException(FinanceAiErrorType.UNSUPPORTED_TOOL);
        }

        JsonNode arguments = parseArguments(call.argumentsJson());
        if (!arguments.isObject()) {
            throw new FinanceAiException(FinanceAiErrorType.INVALID_TOOL_ARGUMENTS);
        }

        return switch (call.name()) {
            case FINANCIAL_OVERVIEW -> {
                requireEmptyObject(arguments);
                yield new ParsedCall(FINANCIAL_OVERVIEW, null, null);
            }
            case INVESTMENT_PORTFOLIO_SUMMARY -> {
                requireEmptyObject(arguments);
                yield new ParsedCall(INVESTMENT_PORTFOLIO_SUMMARY, null, null);
            }
            case MONTHLY_CASH_FLOW -> parseMonthlyCashFlow(arguments);
            default -> throw new FinanceAiException(FinanceAiErrorType.UNSUPPORTED_TOOL);
        };
    }

    private JsonNode parseArguments(String argumentsJson) {
        if (argumentsJson == null) {
            throw new FinanceAiException(FinanceAiErrorType.INVALID_TOOL_ARGUMENTS);
        }
        try (JsonParser parser = objectMapper.getFactory().createParser(argumentsJson)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode arguments = objectMapper.readTree(parser);
            if (arguments == null || parser.nextToken() != null) {
                throw new FinanceAiException(FinanceAiErrorType.INVALID_TOOL_ARGUMENTS);
            }
            return arguments;
        } catch (FinanceAiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new FinanceAiException(FinanceAiErrorType.INVALID_TOOL_ARGUMENTS);
        }
    }

    private ParsedCall parseMonthlyCashFlow(JsonNode arguments) {
        if (arguments.size() != 2
                || !arguments.has("fromMonth")
                || !arguments.has("throughMonth")) {
            throw new FinanceAiException(FinanceAiErrorType.INVALID_TOOL_ARGUMENTS);
        }

        JsonNode fromNode = arguments.get("fromMonth");
        JsonNode throughNode = arguments.get("throughMonth");
        if (!fromNode.isTextual() || !throughNode.isTextual()
                || !fromNode.asText().matches(MONTH_PATTERN)
                || !throughNode.asText().matches(MONTH_PATTERN)) {
            throw new FinanceAiException(FinanceAiErrorType.INVALID_TOOL_ARGUMENTS);
        }

        try {
            YearMonth fromMonth = YearMonth.parse(fromNode.asText());
            YearMonth throughMonth = YearMonth.parse(throughNode.asText());
            long monthDifference = ChronoUnit.MONTHS.between(fromMonth, throughMonth);
            if (monthDifference < 0 || monthDifference >= 12) {
                throw new FinanceAiException(FinanceAiErrorType.INVALID_TOOL_ARGUMENTS);
            }
            return new ParsedCall(MONTHLY_CASH_FLOW, fromMonth, throughMonth);
        } catch (DateTimeException exception) {
            throw new FinanceAiException(FinanceAiErrorType.INVALID_TOOL_ARGUMENTS);
        }
    }

    private void requireEmptyObject(JsonNode arguments) {
        if (!arguments.isEmpty()) {
            throw new FinanceAiException(FinanceAiErrorType.INVALID_TOOL_ARGUMENTS);
        }
    }

    private void validateAuthenticatedUserId(Long authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new FinanceAiException(FinanceAiErrorType.INVALID_USER);
        }
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required,
                "additionalProperties", false);
    }

    private record ParsedCall(String name, YearMonth fromMonth, YearMonth throughMonth) {
    }
}
