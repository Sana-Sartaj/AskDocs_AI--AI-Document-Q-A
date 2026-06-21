package com.docqa.service;

import com.docqa.config.DocumentProperties;
import com.docqa.exception.ApiException;
import com.docqa.exception.DocumentNotFoundException;
import com.docqa.observability.DocQaMetrics;
import com.docqa.model.Document;
import com.docqa.model.DocumentChunk;
import com.docqa.model.User;
import com.docqa.repository.DocumentChunkRepository;
import com.docqa.repository.DocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PdfProcessorServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunkRepository chunkRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private DocQaMetrics metrics;

    private PdfProcessorService service;

    private static final UUID DOC_ID = UUID.randomUUID();
    private static final String S3_KEY = "documents/user/uuid/test.pdf";

    @BeforeEach
    void setUp() {
        DocumentProperties props = new DocumentProperties();
        props.setChunkSize(200);
        props.setChunkOverlap(20);

        TextChunker textChunker = new TextChunker(props);
        ObjectMapper objectMapper = new ObjectMapper();

        service = new PdfProcessorService(
                documentRepository, chunkRepository, storageService,
                textChunker, embeddingService, objectMapper, props, metrics);
    }

    // ── process() happy path ──────────────────────────────────────────────────

    @Test
    void process_marksDocumentAsProcessed_onSuccess() throws Exception {
        Document document = uploadedDocument();
        given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(document));
        given(storageService.download(S3_KEY)).willReturn(createTestPdf("Hello World"));
        given(documentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.process(DOC_ID);

        assertThat(document.getStatus()).isEqualTo(Document.Status.PROCESSED);
        assertThat(document.getErrorMessage()).isNull();
    }

    @Test
    void process_setsChunkCount_onSuccess() throws Exception {
        // Use text long enough to produce multiple chunks with chunkSize=200
        String longText = "Word ".repeat(200); // 1000 chars → multiple chunks
        Document document = uploadedDocument();
        given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(document));
        given(storageService.download(S3_KEY)).willReturn(createTestPdf(longText));
        given(documentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.process(DOC_ID);

        assertThat(document.getChunkCount()).isGreaterThan(0);
    }

    @Test
    void process_savesChunksToRepository() throws Exception {
        String text = "The quick brown fox jumps over the lazy dog. ".repeat(10);
        Document document = uploadedDocument();
        given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(document));
        given(storageService.download(S3_KEY)).willReturn(createTestPdf(text));
        given(documentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.process(DOC_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocumentChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).saveAll(captor.capture());
        List<DocumentChunk> savedChunks = captor.getValue();
        assertThat(savedChunks).isNotEmpty();
        assertThat(savedChunks.get(0).getChunkIndex()).isEqualTo(0);
        assertThat(savedChunks.get(0).getContent()).isNotBlank();
        assertThat(savedChunks.get(0).getMetadata()).isNotBlank();
    }

    @Test
    void process_deletesExistingChunksBeforeSaving() throws Exception {
        Document document = uploadedDocument();
        given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(document));
        given(storageService.download(S3_KEY)).willReturn(createTestPdf("Some text"));
        given(documentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.process(DOC_ID);

        verify(chunkRepository).deleteByDocumentId(DOC_ID);
    }

    @Test
    void process_setsProcessingStatusBeforeDownload() throws Exception {
        Document document = uploadedDocument();
        given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(document));
        given(storageService.download(S3_KEY)).willAnswer(inv -> {
            assertThat(document.getStatus()).isEqualTo(Document.Status.PROCESSING);
            return createTestPdf("Content");
        });
        given(documentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.process(DOC_ID);
    }

    @Test
    void process_includesMetadataJsonInChunks() throws Exception {
        Document document = uploadedDocument();
        given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(document));
        given(storageService.download(S3_KEY)).willReturn(createTestPdf("Content for metadata check"));
        given(documentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.process(DOC_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocumentChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).saveAll(captor.capture());
        String metadata = captor.getValue().get(0).getMetadata();
        assertThat(metadata).contains("pageCount");
        assertThat(metadata).contains("chunkIndex");
        assertThat(metadata).contains("totalChunks");
    }

    // ── process() error paths ─────────────────────────────────────────────────

    @Test
    void process_marksDocumentAsFailed_whenS3DownloadFails() {
        Document document = uploadedDocument();
        given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(document));
        given(storageService.download(S3_KEY))
                .willThrow(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "S3 error"));
        given(documentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.process(DOC_ID);

        assertThat(document.getStatus()).isEqualTo(Document.Status.FAILED);
        assertThat(document.getErrorMessage()).isNotBlank();
    }

    @Test
    void process_marksDocumentAsFailed_whenPdfBytesAreInvalid() {
        Document document = uploadedDocument();
        given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(document));
        given(storageService.download(S3_KEY)).willReturn(new byte[]{0x00, 0x01, 0x02}); // not a PDF
        given(documentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.process(DOC_ID);

        assertThat(document.getStatus()).isEqualTo(Document.Status.FAILED);
    }

    @Test
    void process_skips_whenDocumentIsAlreadyProcessed() {
        Document document = uploadedDocument();
        document.setStatus(Document.Status.PROCESSED);
        given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(document));

        service.process(DOC_ID);

        verifyNoInteractions(storageService);
        verifyNoInteractions(chunkRepository);
    }

    @Test
    void process_skips_whenDocumentIsProcessing() {
        Document document = uploadedDocument();
        document.setStatus(Document.Status.PROCESSING);
        given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(document));

        service.process(DOC_ID);

        verifyNoInteractions(storageService);
    }

    @Test
    void process_throwsDocumentNotFoundException_whenDocumentMissing() {
        given(documentRepository.findById(DOC_ID)).willReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
                DocumentNotFoundException.class,
                () -> service.process(DOC_ID));
    }

    // ── extractContent() ─────────────────────────────────────────────────────

    @Test
    void extractContent_returnsExtractedText() throws Exception {
        byte[] pdfBytes = createTestPdf("Hello PDFBox World");
        PdfParseResult result = service.extractContent(pdfBytes);
        assertThat(result.text()).contains("Hello PDFBox World");
    }

    @Test
    void extractContent_returnsPageCount() throws Exception {
        byte[] pdfBytes = createMultiPagePdf(3);
        PdfParseResult result = service.extractContent(pdfBytes);
        assertThat(result.pageCount()).isEqualTo(3);
    }

    @Test
    void extractContent_handlesEmptyPdf() throws Exception {
        byte[] pdfBytes = createEmptyPdf();
        PdfParseResult result = service.extractContent(pdfBytes);
        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(result.text()).isNotNull();
    }

    // ── test helpers ──────────────────────────────────────────────────────────

    private Document uploadedDocument() {
        User user = User.builder()
                .email("test@example.com")
                .passwordHash("hash")
                .build();

        Document doc = Document.builder()
                .user(user)
                .title("Test Document")
                .originalFilename("test.pdf")
                .s3Key(S3_KEY)
                .contentType("application/pdf")
                .fileSizeBytes(1024L)
                .status(Document.Status.UPLOADED)
                .build();

        try {
            var idField = Document.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(doc, DOC_ID);
        } catch (Exception e) {
            // id remains null; findById mock handles the lookup by the mocked UUID
        }

        return doc;
    }

    private static byte[] createTestPdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                // PDFBox showText cannot handle very long single strings — split at 200 chars
                for (int i = 0; i < text.length(); i += 200) {
                    content.showText(text.substring(i, Math.min(i + 200, text.length())));
                    content.newLineAtOffset(0, -15);
                }
                content.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static byte[] createMultiPagePdf(int pageCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                doc.addPage(new PDPage());
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static byte[] createEmptyPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }
}
