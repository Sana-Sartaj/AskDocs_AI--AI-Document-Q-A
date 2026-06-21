package com.docqa.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ChatSessionSummary(
        UUID id,
        String title,
        long messageCount,
        Instant createdAt,
        Instant updatedAt
) {}
