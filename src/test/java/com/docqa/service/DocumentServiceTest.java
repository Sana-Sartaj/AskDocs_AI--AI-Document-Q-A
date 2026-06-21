package com.docqa.service;

import com.docqa.dto.response.DocumentResponse;
import com.docqa.exception.ApiException;
import com.docqa.exception.DocumentNotFoundException;
import com.docqa.model.Document;
import com.docqa.model.User;
import com.docqa.repository.DocumentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private StorageService storageService;
    @Mock private UserService userService;
    @Mock private PdfProcessorService pdfProcessorService;

    private DocumentService documentService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DOC_ID  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(
                documentRepository, storageService, userService, pdfProcessorService);
        // activate transaction synchronization so registerSynchronization() doesn't throw
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // ── upload ────────────────────────────────────────────────────────────────

    @Test
    void upload_validFile_returnsDocumentResponse() throws IOException {
        User user = buildUser(USER_ID);
        given(userService.getById(USER_ID)).willReturn(user);
        given(storageService.upload(any(), any())).willReturn("docs/uuid/file.pdf");
        given(documentRepository.save(any())).willAnswer(inv -> {
            Document d = inv.getArgument(0);
            ReflectionTestUtils.setField(d, "id", DOC_ID);
            ReflectionTestUtils.setField(d, "createdAt", Instant.now());
            ReflectionTestUtils.setField(d, "updatedAt", Instant.now());
            return d;
        });

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "content".getBytes());
        DocumentResponse resp = documentService.upload(file, "My Doc", USER_ID);

        assertThat(resp.id()).isEqualTo(DOC_ID);
        assertThat(resp.title()).isEqualTo("My Doc");
        assertThat(resp.status()).isEqualTo("UPLOADED");
    }

    @Test
    void upload_blankTitle_throwsApiException() {
        assertThatThrownBy(() -> documentService.upload(
                new MockMultipartFile("file", "test.pdf", "application/pdf", "c".getBytes()),
                "   ", USER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void upload_stripsTitleWhitespace() throws IOException {
        User user = buildUser(USER_ID);
        given(userService.getById(USER_ID)).willReturn(user);
        given(storageService.upload(any(), any())).willReturn("s3key");
        given(documentRepository.save(any())).willAnswer(inv -> {
            Document d = inv.getArgument(0);
            ReflectionTestUtils.setField(d, "id", DOC_ID);
            ReflectionTestUtils.setField(d, "createdAt", Instant.now());
            ReflectionTestUtils.setField(d, "updatedAt", Instant.now());
            return d;
        });

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "c".getBytes());
        DocumentResponse resp = documentService.upload(file, "  Trimmed  ", USER_ID);

        assertThat(resp.title()).isEqualTo("Trimmed");
    }

    @Test
    void upload_s3Failure_throwsApiException() throws IOException {
        given(userService.getById(USER_ID)).willReturn(buildUser(USER_ID));
        given(storageService.upload(any(), any())).willThrow(new IOException("S3 down"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "c".getBytes());
        assertThatThrownBy(() -> documentService.upload(file, "My Doc", USER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── listForUser ───────────────────────────────────────────────────────────

    @Test
    void listForUser_returnsMappedPage() {
        Document doc = buildDocument(DOC_ID, "Report");
        given(documentRepository.findByUserIdOrderByCreatedAtDesc(any(), any()))
                .willReturn(new PageImpl<>(List.of(doc)));

        var page = documentService.listForUser(USER_ID, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).title()).isEqualTo("Report");
    }

    // ── getForUser ────────────────────────────────────────────────────────────

    @Test
    void getForUser_existingDoc_returnsResponse() {
        Document doc = buildDocument(DOC_ID, "Annual Report");
        given(documentRepository.findByIdAndUserId(DOC_ID, USER_ID)).willReturn(Optional.of(doc));

        DocumentResponse resp = documentService.getForUser(DOC_ID, USER_ID);

        assertThat(resp.id()).isEqualTo(DOC_ID);
        assertThat(resp.title()).isEqualTo("Annual Report");
    }

    @Test
    void getForUser_notFound_throwsDocumentNotFoundException() {
        given(documentRepository.findByIdAndUserId(DOC_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.getForUser(DOC_ID, USER_ID))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_existingDoc_deletesFromS3AndDb() {
        Document doc = buildDocument(DOC_ID, "Old Doc");
        given(documentRepository.findByIdAndUserId(DOC_ID, USER_ID)).willReturn(Optional.of(doc));

        documentService.delete(DOC_ID, USER_ID);

        verify(storageService).delete(doc.getS3Key());
        verify(documentRepository).delete(doc);
    }

    @Test
    void delete_notFound_throwsDocumentNotFoundException() {
        given(documentRepository.findByIdAndUserId(DOC_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.delete(DOC_ID, USER_ID))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static User buildUser(UUID id) {
        User u = User.builder().email("user@example.com").passwordHash("h").build();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private static Document buildDocument(UUID id, String title) {
        User u = buildUser(UUID.randomUUID());
        Document d = Document.builder()
                .user(u)
                .title(title)
                .originalFilename("file.pdf")
                .s3Key("docs/" + id)
                .contentType("application/pdf")
                .fileSizeBytes(1024L)
                .build();
        ReflectionTestUtils.setField(d, "id", id);
        ReflectionTestUtils.setField(d, "createdAt", Instant.now());
        ReflectionTestUtils.setField(d, "updatedAt", Instant.now());
        return d;
    }
}
