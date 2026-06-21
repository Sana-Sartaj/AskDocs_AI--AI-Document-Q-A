package com.docqa.health;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Checks that an OpenAI API key is configured. Does NOT make a live API call
 * (that would be slow, costly, and rate-limited). A missing key means both
 * embedding generation and chat completions will fail at runtime.
 */
@Component("openAi")
@RequiredArgsConstructor
public class OpenAiHealthIndicator implements HealthIndicator {

    @Value("${langchain4j.open-ai.api-key:}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name:}")
    private String chatModel;

    @Value("${langchain4j.open-ai.embedding-model.model-name:}")
    private String embeddingModel;

    @Override
    public Health health() {
        if (apiKey == null || apiKey.isBlank()) {
            return Health.down()
                    .withDetail("reason", "OPENAI_API_KEY is not configured")
                    .build();
        }

        return Health.up()
                .withDetail("chatModel", chatModel)
                .withDetail("embeddingModel", embeddingModel)
                .withDetail("keyConfigured", true)
                // Never expose the actual key — only its length as a sanity signal
                .withDetail("keyLength", apiKey.length())
                .build();
    }
}
