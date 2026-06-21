package com.docqa.service;

import com.docqa.dto.response.DocumentResponse;
import com.docqa.exception.ApiException;
import com.docqa.exception.DocumentNotFoundException;
import com.docqa.model.Document;
import com.docqa.model.User;
import com.docqa.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final UserService userService;
    private final PdfProcessorService pdfProcessorService;

    @Transactional
    public DocumentResponse upload(MultipartFile file, String title, UUID userId) {
        if (title == null || title.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Document title must not be blank");
        }

        User user = userService.getById(userId);

        String s3Key;
        try {
            s3Key = storageService.upload(file, userId);
        } catch (IOException e) {
            log.error("Failed to read file content for upload, userId={}: {}", userId, e.getMessage(), e);
            throw new ApiException(HttpStatus.BAD_REQUEST, "Failed to read uploaded file");
        }

        Document document = Document.builder()
                .user(user)
                .title(title.strip())
                .originalFilename(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unnamed")
                .s3Key(s3Key)
                .contentType(file.getContentType())
                .fileSizeBytes(file.getSize())
                .status(Document.Status.UPLOADED)
                .build();

        document = documentRepository.save(document);
        log.info("Document uploaded: id={}, userId={}, file={}", document.getId(), userId, document.getOriginalFilename());

        final UUID documentId = document.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                processDocumentAsync(documentId);
            }
        });

        return toResponse(document);
    }

    @Transactional(readOnly = true)
    public Page<DocumentResponse> listForUser(UUID userId, Pageable pageable) {
        return documentRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public DocumentResponse getForUser(UUID documentId, UUID userId) {
        Document doc = documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return toResponse(doc);
    }

    /**
     * Deletes the S3 object first, then the DB record (which cascades to document_chunks → embeddings).
     * If the S3 deletion fails the transaction rolls back and the DB record is preserved.
     */
    @Transactional
    public void delete(UUID documentId, UUID userId) {
        Document document = documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        storageService.delete(document.getS3Key());
        documentRepository.delete(document);
        log.info("Document deleted: id={}, userId={}", documentId, userId);
    }

    @Async("documentProcessingExecutor")
    public void processDocumentAsync(UUID documentId) {
        try {
            pdfProcessorService.process(documentId);
        } catch (Exception e) {
            log.error("Async processing failed for document {}: {}", documentId, e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private DocumentResponse toResponse(Document doc) {
        return new DocumentResponse(
                doc.getId(),
                doc.getTitle(),
                doc.getOriginalFilename(),
                doc.getContentType(),
                doc.getFileSizeBytes(),
                doc.getStatus().name(),
                doc.getChunkCount(),
                doc.getCreatedAt(),
                doc.getUpdatedAt()
        );
    }
}
