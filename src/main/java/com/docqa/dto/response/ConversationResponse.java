package com.docqa.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        String title,
        List<MessageResponse> messages,
        Instant createdAt,
        Instant updatedAt
) {
    public record MessageResponse(
            UUID id,
            String role,
            String content,
            Instant createdAt
    ) {}
}
