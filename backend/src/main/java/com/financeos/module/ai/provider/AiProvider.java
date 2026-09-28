package com.financeos.module.ai.provider;

import com.financeos.module.ai.dto.AiRequest;
import com.financeos.module.ai.dto.AiResponse;

public interface AiProvider {
    AiResponse generate(AiRequest request);
}
