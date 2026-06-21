package com.docqa.dto.response;

import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UUID userId,
        String email,
        String fullName,
        String role
) {
    public AuthResponse(String accessToken, long expiresIn, UUID userId, String email, String fullName, String role) {
        this(accessToken, "Bearer", expiresIn, userId, email, fullName, role);
    }
}
