package com.financeos.module.ai.provider;

import com.financeos.module.ai.dto.toolcalling.AiToolRequest;
import com.financeos.module.ai.dto.toolcalling.AiToolResponse;

/** A provider-neutral turn in a bounded tool conversation. */
public interface AiToolCallingProvider {
    AiToolResponse next(AiToolRequest request);
}
