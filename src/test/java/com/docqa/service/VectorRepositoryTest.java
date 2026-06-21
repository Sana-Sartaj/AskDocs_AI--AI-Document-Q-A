package com.docqa.service;

import com.docqa.config.VectorSearchProperties;
import com.docqa.observability.DocQaMetrics;
import com.docqa.repository.ChunkSearchResult;
import com.docqa.repository.DocumentChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class VectorRepositoryTest {

    @Mock private EmbeddingService embeddingService;
    @Mock private DocumentChunkRepository chunkRepository;
    @Mock private DocQaMetrics metrics;

    private VectorRepository vectorRepository;

    private static final String MODEL = "text-embedding-3-small";
    private static final float[] QUERY_VECTOR = {0.1f, 0.2f, 0.3f};
    private static final String VECTOR_LITERAL = "[0.1,0.2,0.3]";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final int DEFAULT_TOP_K = 5;

    @BeforeEach
    void setUp() {
        VectorSearchProperties props = new VectorSearchProperties();
        props.setSimilarityTopK(DEFAULT_TOP_K);
        props.setEfSearch(100);

        vectorRepository = new VectorRepository(embeddingService, chunkRepository, props, metrics);
        ReflectionTestUtils.setField(vectorRepository, "modelName", MODEL);

        given(embeddingService.embed(anyString())).willReturn(QUERY_VECTOR);
        given(embeddingService.toVectorLiteral(QUERY_VECTOR)).willReturn(VECTOR_LITERAL);
    }

    // ── findSimilar() ─────────────────────────────────────────────────────────

    @Test
    void findSimilar_embedsQueryAndPassesLiteralToRepository() {
        given(chunkRepository.findTopKSimilarByUserWithDistance(any(), any(), any(), anyInt()))
                .willReturn(List.of());

        vectorRepository.findSimilar("what is this?", USER_ID, 3);

        verify(embeddingService).embed("what is this?");
        verify(chunkRepository).findTopKSimilarByUserWithDistance(
                USER_ID, MODEL, VECTOR_LITERAL, 3);
    }

    @Test
    void findSimilar_convertsCosineDistanceToSimilarity() {
        UUID chunkId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        // distance = 0.15  →  similarity = 1.0 - 0.15 = 0.85
        given(chunkRepository.findTopKSimilarByUserWithDistance(any(), any(), any(), anyInt()))
                .willReturn(List.of(mockResult(chunkId, docId, 0, "chunk content", 0.15)));

        List<SimilarChunk> chunks = vectorRepository.findSimilar("query", USER_ID, 1);

        assertThat(chunks).hasSize(1);
        SimilarChunk sc = chunks.get(0);
        assertThat(sc.chunkId()).isEqualTo(chunkId);
        assertThat(sc.documentId()).isEqualTo(docId);
        assertThat(sc.chunkIndex()).isEqualTo(0);
        assertThat(sc.content()).isEqualTo("chunk content");
        assertThat(sc.similarity()).isCloseTo(0.85, within(1e-9));
    }

    @Test
    void findSimilar_similarityIsOne_forIdenticalVector() {
        // distance = 0 → similarity = 1.0 (identical vectors)
        given(chunkRepository.findTopKSimilarByUserWithDistance(any(), any(), any(), anyInt()))
                .willReturn(List.of(mockResult(UUID.randomUUID(), UUID.randomUUID(), 0, "x", 0.0)));

        List<SimilarChunk> chunks = vectorRepository.findSimilar("q", USER_ID, 1);

        assertThat(chunks.get(0).similarity()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void findSimilar_usesDefaultTopK_whenCalledWithTwoArgs() {
        given(chunkRepository.findTopKSimilarByUserWithDistance(any(), any(), any(), anyInt()))
                .willReturn(List.of());

        vectorRepository.findSimilar("query", USER_ID);

        verify(chunkRepository).findTopKSimilarByUserWithDistance(
                USER_ID, MODEL, VECTOR_LITERAL, DEFAULT_TOP_K);
    }

    @Test
    void findSimilar_returnsEmptyList_whenRepositoryReturnsNone() {
        given(chunkRepository.findTopKSimilarByUserWithDistance(any(), any(), any(), anyInt()))
                .willReturn(List.of());

        assertThat(vectorRepository.findSimilar("query", USER_ID, 5)).isEmpty();
    }

    @Test
    void findSimilar_resultsOrderedBySimilarityDescending() {
        UUID d = UUID.randomUUID();
        // Repository returns in ascending distance order (ORDER BY distance)
        // so similarity should be descending
        List<ChunkSearchResult> results = List.of(
                mockResult(UUID.randomUUID(), d, 0, "best",   0.05),
                mockResult(UUID.randomUUID(), d, 1, "middle", 0.20),
                mockResult(UUID.randomUUID(), d, 2, "worst",  0.45));
        given(chunkRepository.findTopKSimilarByUserWithDistance(any(), any(), any(), anyInt()))
                .willReturn(results);

        List<SimilarChunk> chunks = vectorRepository.findSimilar("query", USER_ID, 3);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).similarity())
                .isGreaterThan(chunks.get(1).similarity());
        assertThat(chunks.get(1).similarity())
                .isGreaterThan(chunks.get(2).similarity());
    }

    @Test
    void findSimilar_handlesNullDistance_withLowestSimilarity() {
        given(chunkRepository.findTopKSimilarByUserWithDistance(any(), any(), any(), anyInt()))
                .willReturn(List.of(mockResult(UUID.randomUUID(), UUID.randomUUID(), 0, "x", null)));

        List<SimilarChunk> chunks = vectorRepository.findSimilar("q", USER_ID, 1);

        // null distance → distance=2.0 → similarity = 1.0 - 2.0 = -1.0
        assertThat(chunks.get(0).similarity()).isCloseTo(-1.0, within(1e-9));
    }

    // ── findSimilarInDocuments() ──────────────────────────────────────────────

    @Test
    void findSimilarInDocuments_formatsPgArrayLiteral() {
        UUID doc1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID doc2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        given(chunkRepository.findTopKSimilarInDocumentsWithDistance(any(), any(), any(), anyInt()))
                .willReturn(List.of());

        vectorRepository.findSimilarInDocuments("query", List.of(doc1, doc2), 3);

        verify(chunkRepository).findTopKSimilarInDocumentsWithDistance(
                eq("{00000000-0000-0000-0000-000000000001,00000000-0000-0000-0000-000000000002}"),
                eq(MODEL),
                eq(VECTOR_LITERAL),
                eq(3));
    }

    @Test
    void findSimilarInDocuments_usesDefaultTopK_whenCalledWithTwoArgs() {
        UUID docId = UUID.randomUUID();
        given(chunkRepository.findTopKSimilarInDocumentsWithDistance(any(), any(), any(), anyInt()))
                .willReturn(List.of());

        vectorRepository.findSimilarInDocuments("query", List.of(docId));

        verify(chunkRepository).findTopKSimilarInDocumentsWithDistance(
                any(), eq(MODEL), eq(VECTOR_LITERAL), eq(DEFAULT_TOP_K));
    }

    @Test
    void findSimilarInDocuments_returnsEmptyList_whenDocumentIdsIsEmpty() {
        List<SimilarChunk> result =
                vectorRepository.findSimilarInDocuments("query", List.of(), 5);

        assertThat(result).isEmpty();
        verifyNoInteractions(chunkRepository);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static ChunkSearchResult mockResult(
            UUID id, UUID documentId, int chunkIndex, String content, Double distance) {
        return new ChunkSearchResult() {
            @Override public UUID getId()         { return id; }
            @Override public UUID getDocumentId() { return documentId; }
            @Override public int getChunkIndex()  { return chunkIndex; }
            @Override public String getContent()  { return content; }
            @Override public Double getDistance() { return distance; }
        };
    }
}
