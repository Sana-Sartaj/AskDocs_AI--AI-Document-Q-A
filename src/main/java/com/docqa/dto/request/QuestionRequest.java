package com.docqa.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record QuestionRequest(
        @NotBlank @Size(max = 4000)
        String question,

        List<UUID> documentIds,

        UUID conversationId
) {}
