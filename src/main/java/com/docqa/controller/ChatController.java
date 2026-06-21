package com.docqa.controller;

import com.docqa.dto.request.QuestionRequest;
import com.docqa.dto.response.ApiResponse;
import com.docqa.dto.response.ChatSessionSummary;
import com.docqa.dto.response.ConversationResponse;
import com.docqa.dto.response.QuestionResponse;
import com.docqa.service.ChatService;
import com.docqa.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@Tag(name = "Chat", description = "Multi-turn document chat with conversation history")
@SecurityRequirement(name = "bearerAuth")
public class ChatController {

    private final ChatService chatService;
    private final UserService userService;

    @PostMapping("/ask")
    @Operation(summary = "Send a message — creates a new session or continues an existing one")
    public ResponseEntity<ApiResponse<QuestionResponse>> ask(
            @Valid @RequestBody QuestionRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        UUID userId = userService.getByEmail(principal.getUsername()).getId();
        return ResponseEntity.ok(ApiResponse.ok(chatService.ask(request, userId)));
    }

    @GetMapping("/sessions")
    @Operation(summary = "List the current user's chat sessions, newest first")
    public ResponseEntity<ApiResponse<Page<ChatSessionSummary>>> listSessions(
            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC)
            Pageable pageable,
            @AuthenticationPrincipal UserDetails principal) {

        UUID userId = userService.getByEmail(principal.getUsername()).getId();
        return ResponseEntity.ok(ApiResponse.ok(chatService.listSessions(userId, pageable)));
    }

    @GetMapping("/sessions/{id}")
    @Operation(summary = "Get a session with its full message history")
    public ResponseEntity<ApiResponse<ConversationResponse>> getSession(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {

        UUID userId = userService.getByEmail(principal.getUsername()).getId();
        return ResponseEntity.ok(ApiResponse.ok(chatService.getSession(id, userId)));
    }

    @DeleteMapping("/sessions/{id}")
    @Operation(summary = "Delete a session and all its messages")
    public ResponseEntity<Void> deleteSession(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {

        UUID userId = userService.getByEmail(principal.getUsername()).getId();
        chatService.deleteSession(id, userId);
        return ResponseEntity.noContent().build();
    }
}
