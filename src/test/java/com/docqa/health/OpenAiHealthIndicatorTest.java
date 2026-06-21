package com.docqa.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiHealthIndicatorTest {

    private OpenAiHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        indicator = new OpenAiHealthIndicator();
    }

    @Test
    void health_apiKeyConfigured_returnsUp() {
        ReflectionTestUtils.setField(indicator, "apiKey", "sk-test-key-12345");
        ReflectionTestUtils.setField(indicator, "chatModel", "gpt-4o");
        ReflectionTestUtils.setField(indicator, "embeddingModel", "text-embedding-3-small");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("chatModel", "gpt-4o");
        assertThat(health.getDetails()).containsEntry("keyConfigured", true);
        assertThat(health.getDetails()).doesNotContainKey("sk-test-key-12345");
    }

    @Test
    void health_apiKeyBlank_returnsDown() {
        ReflectionTestUtils.setField(indicator, "apiKey", "");
        ReflectionTestUtils.setField(indicator, "chatModel", "gpt-4o");
        ReflectionTestUtils.setField(indicator, "embeddingModel", "text-embedding-3-small");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("reason");
    }

    @Test
    void health_apiKeyNull_returnsDown() {
        ReflectionTestUtils.setField(indicator, "apiKey", null);
        ReflectionTestUtils.setField(indicator, "chatModel", "");
        ReflectionTestUtils.setField(indicator, "embeddingModel", "");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}
