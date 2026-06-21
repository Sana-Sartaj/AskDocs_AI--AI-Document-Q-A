package com.docqa.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    private PromptBuilder promptBuilder;

    private static final UUID DOC_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        promptBuilder = new PromptBuilder();
    }

    @Test
    void buildSystemPrompt_returnsNonBlankInstructions() {
        String prompt = promptBuilder.buildSystemPrompt();
        assertThat(prompt).isNotBlank();
        assertThat(prompt).contains("document Q&A assistant");
    }

    @Test
    void buildContextSection_emptyChunks_returnsNoResultsMessage() {
        String ctx = promptBuilder.buildContextSection(List.of(), Map.of(), 8000);
        assertThat(ctx).contains("No relevant document excerpts");
    }

    @Test
    void buildContextSection_singleChunk_includesSourceAndContent() {
        SimilarChunk chunk = new SimilarChunk(UUID.randomUUID(), DOC_ID, 0, "Chunk text here", 0.9);
        Map<UUID, String> titles = Map.of(DOC_ID, "My Document");

        String ctx = promptBuilder.buildContextSection(List.of(chunk), titles, 8000);

        assertThat(ctx).contains("[1]");
        assertThat(ctx).contains("My Document");
        assertThat(ctx).contains("chunk 0");
        assertThat(ctx).contains("Chunk text here");
    }

    @Test
    void buildContextSection_unknownDocId_usesUnknownDocumentFallback() {
        SimilarChunk chunk = new SimilarChunk(UUID.randomUUID(), UUID.randomUUID(), 0, "content", 0.5);

        String ctx = promptBuilder.buildContextSection(List.of(chunk), Map.of(), 8000);

        assertThat(ctx).contains("Unknown Document");
    }

    @Test
    void buildContextSection_multipleChunks_numbersSequentially() {
        SimilarChunk c1 = new SimilarChunk(UUID.randomUUID(), DOC_ID, 0, "First", 0.9);
        SimilarChunk c2 = new SimilarChunk(UUID.randomUUID(), DOC_ID, 1, "Second", 0.8);
        Map<UUID, String> titles = Map.of(DOC_ID, "Doc");

        String ctx = promptBuilder.buildContextSection(List.of(c1, c2), titles, 8000);

        assertThat(ctx).contains("[1]");
        assertThat(ctx).contains("[2]");
        assertThat(ctx).contains("First");
        assertThat(ctx).contains("Second");
    }

    @Test
    void buildContextSection_respectsMaxContextChars() {
        String longContent = "X".repeat(1000);
        SimilarChunk c1 = new SimilarChunk(UUID.randomUUID(), DOC_ID, 0, longContent, 0.9);
        SimilarChunk c2 = new SimilarChunk(UUID.randomUUID(), DOC_ID, 1, longContent, 0.8);
        Map<UUID, String> titles = Map.of(DOC_ID, "Doc");

        // maxContextChars just above c1's entry length → c2 gets truncated
        String ctx = promptBuilder.buildContextSection(List.of(c1, c2), titles, 1100);

        assertThat(ctx).contains("[1]");
        assertThat(ctx).doesNotContain("[2]");
    }

    @Test
    void buildUserPrompt_includesContextAndQuestion() {
        SimilarChunk chunk = new SimilarChunk(UUID.randomUUID(), DOC_ID, 0, "Relevant info", 0.8);
        Map<UUID, String> titles = Map.of(DOC_ID, "Report");

        String userPrompt = promptBuilder.buildUserPrompt(
                "What is this about?", List.of(chunk), titles, 8000);

        assertThat(userPrompt).contains("Context excerpts:");
        assertThat(userPrompt).contains("Relevant info");
        assertThat(userPrompt).contains("Question: What is this about?");
    }

    @Test
    void buildUserPrompt_emptyChunks_includesNoResultsPlaceholder() {
        String userPrompt = promptBuilder.buildUserPrompt(
                "Any answer?", List.of(), Map.of(), 8000);

        assertThat(userPrompt).contains("No relevant document excerpts");
        assertThat(userPrompt).contains("Question: Any answer?");
    }
}
