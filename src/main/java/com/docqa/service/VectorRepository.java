package com.docqa.service;

import com.docqa.config.VectorSearchProperties;
import com.docqa.observability.DocQaMetrics;
import com.docqa.repository.ChunkSearchResult;
import com.docqa.repository.DocumentChunkRepository;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Encapsulates all vector similarity search operations.
 *
 * Embeds the query at call time, delegates to native pgvector queries, and
 * returns typed {@link SimilarChunk} results with cosine <em>similarity</em>
 * scores (1 − cosine_distance; higher = more similar).
 *
 * HNSW search quality is governed by the {@code hnsw.ef_search} PostgreSQL
 * parameter, which is set once per connection via Hikari's
 * {@code connection-init-sql} and can be tuned in {@code application.yml}
 * under {@code app.vector.ef-search}.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class VectorRepository {

    private final EmbeddingService embeddingService;
    private final DocumentChunkRepository chunkRepository;
    private final VectorSearchProperties vectorSearchProperties;
    private final DocQaMetrics metrics;

    @Value("${langchain4j.open-ai.embedding-model.model-name}")
    private String modelName;

    // ── public API ────────────────────────────────────────────────────────────

    /** Top-K search across all documents owned by {@code userId}. */
    @Transactional(readOnly = true)
    public List<SimilarChunk> findSimilar(String queryText, UUID userId) {
        return findSimilar(queryText, userId, vectorSearchProperties.getSimilarityTopK());
    }

    /** Top-K search across all documents owned by {@code userId}. */
    @Transactional(readOnly = true)
    public List<SimilarChunk> findSimilar(String queryText, UUID userId, int topK) {
        log.debug("Vector search: userId={}, topK={}, model={}", userId, topK, modelName);
        Timer.Sample sample = metrics.startVectorSearch();
        try {
            String vectorLiteral = embedQuery(queryText);
            return chunkRepository
                    .findTopKSimilarByUserWithDistance(userId, modelName, vectorLiteral, topK)
                    .stream()
                    .map(VectorRepository::toSimilarChunk)
                    .toList();
        } finally {
            metrics.stopVectorSearch(sample);
        }
    }

    /** Top-K search scoped to a specific set of documents. */
    @Transactional(readOnly = true)
    public List<SimilarChunk> findSimilarInDocuments(String queryText, List<UUID> documentIds) {
        return findSimilarInDocuments(queryText, documentIds,
                vectorSearchProperties.getSimilarityTopK());
    }

    /** Top-K search scoped to a specific set of documents. */
    @Transactional(readOnly = true)
    public List<SimilarChunk> findSimilarInDocuments(
            String queryText, List<UUID> documentIds, int topK) {

        if (documentIds.isEmpty()) {
            return List.of();
        }

        log.debug("Vector search: documentCount={}, topK={}, model={}",
                documentIds.size(), topK, modelName);
        Timer.Sample sample = metrics.startVectorSearch();
        try {
            String vectorLiteral = embedQuery(queryText);
            String pgArrayLiteral = toPgArrayLiteral(documentIds);
            return chunkRepository
                    .findTopKSimilarInDocumentsWithDistance(pgArrayLiteral, modelName, vectorLiteral, topK)
                    .stream()
                    .map(VectorRepository::toSimilarChunk)
                    .toList();
        } finally {
            metrics.stopVectorSearch(sample);
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private String embedQuery(String queryText) {
        float[] vector = embeddingService.embed(queryText);
        return embeddingService.toVectorLiteral(vector);
    }

    /**
     * Converts a {@link ChunkSearchResult} to a {@link SimilarChunk}, computing
     * cosine similarity as {@code 1 − cosine_distance}. A null distance (should
     * not occur in practice) is treated as the worst possible similarity.
     */
    private static SimilarChunk toSimilarChunk(ChunkSearchResult r) {
        double distance = r.getDistance() != null ? r.getDistance() : 2.0;
        return new SimilarChunk(
                r.getId(),
                r.getDocumentId(),
                r.getChunkIndex(),
                r.getContent(),
                1.0 - distance);
    }

    private static String toPgArrayLiteral(List<UUID> ids) {
        return "{" + ids.stream().map(UUID::toString).collect(Collectors.joining(",")) + "}";
    }
}
