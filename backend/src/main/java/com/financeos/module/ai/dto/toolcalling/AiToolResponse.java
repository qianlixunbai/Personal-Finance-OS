package com.financeos.module.ai.dto.toolcalling;

import java.util.List;

public record AiToolResponse(String content, List<AiToolCall> toolCalls) {
}
