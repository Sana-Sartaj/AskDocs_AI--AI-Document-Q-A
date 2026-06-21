package com.docqa.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PromptBuilder {

    static final String SYSTEM_PROMPT = """
            You are a precise document Q&A assistant. Answer the user's question based ONLY on the \
            context excerpts provided below.

            Rules:
            - Use only information present in the provided context. Do not use external knowledge.
            - If the context does not contain a clear answer, respond exactly: \
            "I could not find an answer to your question in the provided documents."
            - Be concise and factual.
            - You may reference document titles when citing information.""";

    public String buildSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * Formats retrieved chunks into a numbered context block, stopping before
     * {@code maxContextChars} is exceeded. Used by both one-shot QA (in the user
     * message) and multi-turn chat (embedded in the system message).
     */
    public String buildContextSection(
            List<SimilarChunk> chunks,
            Map<UUID, String> docTitles,
            int maxContextChars) {

        if (chunks.isEmpty()) {
            return "(No relevant document excerpts were found.)";
        }

        StringBuilder ctx = new StringBuilder();
        int usedChars = 0;

        for (int i = 0; i < chunks.size(); i++) {
            SimilarChunk chunk = chunks.get(i);
            String title = docTitles.getOrDefault(chunk.documentId(), "Unknown Document");
            String entry = String.format("[%d] Source: \"%s\" (chunk %d)%n%s%n%n",
                    i + 1, title, chunk.chunkIndex(), chunk.content());

            if (usedChars > 0 && usedChars + entry.length() > maxContextChars) {
                break;
            }
            ctx.append(entry);
            usedChars += entry.length();
        }

        return ctx.toString();
    }

    /**
     * Builds the single-turn user-message prompt (context + question).
     * Used by {@code QAService} for one-shot requests.
     */
    public String buildUserPrompt(
            String question,
            List<SimilarChunk> chunks,
            Map<UUID, String> docTitles,
            int maxContextChars) {

        return "Context excerpts:\n\n"
                + buildContextSection(chunks, docTitles, maxContextChars)
                + "\n---\nQuestion: " + question;
    }
}
