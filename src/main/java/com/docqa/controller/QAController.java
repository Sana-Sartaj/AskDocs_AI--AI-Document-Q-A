package com.docqa.controller;

import com.docqa.dto.request.QuestionRequest;
import com.docqa.dto.response.ApiResponse;
import com.docqa.dto.response.QuestionResponse;
import com.docqa.service.QAService;
import com.docqa.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/qa")
@RequiredArgsConstructor
@Tag(name = "Q&A", description = "Ask questions against uploaded documents")
@SecurityRequirement(name = "bearerAuth")
public class QAController {

    private final QAService qaService;
    private final UserService userService;

    @PostMapping("/ask")
    @Operation(summary = "Ask a question and receive an AI-generated answer with sources")
    public ResponseEntity<ApiResponse<QuestionResponse>> ask(
            @Valid @RequestBody QuestionRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        UUID userId = userService.getByEmail(principal.getUsername()).getId();
        return ResponseEntity.ok(ApiResponse.ok(qaService.ask(request, userId)));
    }
}
