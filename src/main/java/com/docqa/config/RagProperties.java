package com.docqa.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rag")
@Getter
@Setter
public class RagProperties {

    /** Maximum characters of retrieved context to include in the LLM prompt. */
    private int maxContextChars = 8000;

    /** Maximum characters of the question used as the chat session title. */
    private int sessionTitleMaxLength = 80;

    /**
     * Maximum number of past chat messages (USER + ASSISTANT) fed back into the
     * LLM on each turn. Capped to avoid context-window overflow.
     * 20 messages = 10 full conversational turns.
     */
    private int maxHistoryMessages = 20;
}
