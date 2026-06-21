package com.docqa.dto.response;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String title,
        String originalFilename,
        String contentType,
        Long fileSizeBytes,
        String status,
        int chunkCount,
        Instant createdAt,
        Instant updatedAt
) {}
