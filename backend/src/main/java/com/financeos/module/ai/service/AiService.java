package com.financeos.module.ai.service;

import com.financeos.module.ai.dto.AiRequest;
import com.financeos.module.ai.dto.AiResponse;
import com.financeos.module.ai.provider.AiProvider;
import org.springframework.stereotype.Service;

@Service
public class AiService {
    private final AiProvider provider;

    public AiService(AiProvider provider) {
        this.provider = provider;
    }

    public AiResponse generate(AiRequest request) {
        return provider.generate(request);
    }
}
