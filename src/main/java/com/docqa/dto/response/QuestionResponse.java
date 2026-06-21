package com.docqa.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record QuestionResponse(
        UUID conversationId,
        UUID messageId,
        String answer,
        List<SourceChunk> sources,
        double confidence,
        Instant createdAt
) {
    public record SourceChunk(
            UUID documentId,
            String documentTitle,
            int chunkIndex,
            String content,
            double score
    ) {}
}
