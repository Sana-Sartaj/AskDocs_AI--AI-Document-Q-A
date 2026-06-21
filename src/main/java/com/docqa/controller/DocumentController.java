package com.docqa.controller;

import com.docqa.dto.response.ApiResponse;
import com.docqa.dto.response.DocumentResponse;
import com.docqa.service.DocumentService;
import com.docqa.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Tag(name = "Documents", description = "Upload and manage documents")
@SecurityRequirement(name = "bearerAuth")
public class DocumentController {

    private final DocumentService documentService;
    private final UserService userService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a document for processing")
    public ResponseEntity<ApiResponse<DocumentResponse>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart("title") String title,
            @AuthenticationPrincipal UserDetails principal) {

        UUID userId = userService.getByEmail(principal.getUsername()).getId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(documentService.upload(file, title, userId)));
    }

    @GetMapping
    @Operation(summary = "List all documents for the authenticated user")
    public ResponseEntity<ApiResponse<Page<DocumentResponse>>> list(
            @AuthenticationPrincipal UserDetails principal,
            @PageableDefault(size = 20) Pageable pageable) {

        UUID userId = userService.getByEmail(principal.getUsername()).getId();
        return ResponseEntity.ok(ApiResponse.ok(documentService.listForUser(userId, pageable)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single document by ID")
    public ResponseEntity<ApiResponse<DocumentResponse>> get(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {

        UUID userId = userService.getByEmail(principal.getUsername()).getId();
        return ResponseEntity.ok(ApiResponse.ok(documentService.getForUser(id, userId)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a document")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {

        UUID userId = userService.getByEmail(principal.getUsername()).getId();
        documentService.delete(id, userId);
        return ResponseEntity.noContent().build();
    }
}
