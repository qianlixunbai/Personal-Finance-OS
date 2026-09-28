package com.financeos.module.ai.dto.toolcalling;

import java.util.List;

public record AiToolRequest(List<AiToolMessage> messages, List<AiToolDefinition> tools) {
}
