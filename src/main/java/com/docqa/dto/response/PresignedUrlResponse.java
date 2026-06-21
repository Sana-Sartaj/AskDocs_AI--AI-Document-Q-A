package com.docqa.dto.response;

import java.time.Instant;

public record PresignedUrlResponse(
        String url,
        String s3Key,
        Instant expiresAt
) {}
