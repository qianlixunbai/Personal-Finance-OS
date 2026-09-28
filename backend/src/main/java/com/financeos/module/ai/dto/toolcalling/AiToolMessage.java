package com.financeos.module.ai.dto.toolcalling;

import java.util.List;

public record AiToolMessage(Role role, String content, String toolCallId, List<AiToolCall> toolCalls) {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }
}
