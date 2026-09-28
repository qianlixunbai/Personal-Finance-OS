package com.financeos.module.ai.dto.toolcalling;

import java.util.Map;

public record AiToolDefinition(String name, String description, Map<String, Object> parameters) {
}
