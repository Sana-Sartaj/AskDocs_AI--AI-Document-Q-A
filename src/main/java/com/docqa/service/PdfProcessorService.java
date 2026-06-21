package com.docqa.service;

import com.docqa.config.DocumentProperties;
import com.docqa.exception.DocumentNotFoundException;
import com.docqa.model.Document;
import com.docqa.model.DocumentChunk;
import com.docqa.observability.DocQaMetrics;
import com.docqa.repository.DocumentChunkRepository;
import com.docqa.repository.DocumentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PdfProcessorService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final StorageService storageService;
    private final TextChunker textChunker;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final DocumentProperties documentProperties;
    private final DocQaMetrics metrics;

    @Transactional
    public void process(UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        if (document.getStatus() != Document.Status.UPLOADED) {
            log.warn("Skipping document {}: expected UPLOADED but found {}", documentId, document.getStatus());
            return;
        }

        document.setStatus(Document.Status.PROCESSING);
        documentRepository.save(document);

        Timer.Sample sample = metrics.startPdfProcessing();
        try {
            byte[] pdfBytes = storageService.download(document.getS3Key());
            PdfParseResult parsed = extractContent(pdfBytes);

            List<String> textChunks = textChunker.split(
                    parsed.text(),
                    documentProperties.getChunkSize(),
                    documentProperties.getChunkOverlap());

            chunkRepository.deleteByDocumentId(documentId);

            List<DocumentChunk> chunks = buildChunks(document, parsed, textChunks);
            chunkRepository.saveAll(chunks);

            embeddingService.embedChunks(chunks);

            document.setStatus(Document.Status.PROCESSED);
            document.setChunkCount(chunks.size());
            document.setErrorMessage(null);
            documentRepository.save(document);

            metrics.documentProcessed();
            metrics.chunksCreated(chunks.size());
            log.info("Processed document {}: {} chunks from {} page(s)", documentId, chunks.size(), parsed.pageCount());

        } catch (Exception e) {
            log.error("Failed to process document {}: {}", documentId, e.getMessage(), e);
            document.setStatus(Document.Status.FAILED);
            document.setErrorMessage(truncate(e.getMessage(), 500));
            documentRepository.save(document);
            metrics.documentFailed();
        } finally {
            metrics.stopPdfProcessing(sample);
        }
    }

    PdfParseResult extractContent(byte[] pdfBytes) throws IOException {
        try (PDDocument pdDoc = Loader.loadPDF(new RandomAccessReadBuffer(pdfBytes))) {
            PDDocumentInformation info = pdDoc.getDocumentInformation();
            String text = new PDFTextStripper().getText(pdDoc);
            return new PdfParseResult(
                    text,
                    blankToEmpty(info.getTitle()),
                    blankToEmpty(info.getAuthor()),
                    blankToEmpty(info.getSubject()),
                    pdDoc.getNumberOfPages());
        }
    }

    private List<DocumentChunk> buildChunks(Document document, PdfParseResult parsed, List<String> textChunks) {
        List<DocumentChunk> chunks = new ArrayList<>(textChunks.size());
        for (int i = 0; i < textChunks.size(); i++) {
            chunks.add(DocumentChunk.builder()
                    .document(document)
                    .chunkIndex(i)
                    .content(textChunks.get(i))
                    .metadata(buildMetadataJson(parsed, i, textChunks.size()))
                    .build());
        }
        return chunks;
    }

    private String buildMetadataJson(PdfParseResult parsed, int chunkIndex, int totalChunks) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("pageCount", parsed.pageCount());
        meta.put("pdfTitle", parsed.title());
        meta.put("pdfAuthor", parsed.author());
        meta.put("pdfSubject", parsed.subject());
        meta.put("chunkIndex", chunkIndex);
        meta.put("totalChunks", totalChunks);
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String blankToEmpty(String value) {
        return (value == null || value.isBlank()) ? "" : value.strip();
    }

    private static String truncate(String message, int maxLength) {
        if (message == null) return "Unknown error";
        return message.length() <= maxLength ? message : message.substring(0, maxLength);
    }
}
