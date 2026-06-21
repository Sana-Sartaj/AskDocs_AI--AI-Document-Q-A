package com.docqa.service;

import com.docqa.model.DocumentChunk;
import com.docqa.observability.DocQaMetrics;
import com.docqa.repository.EmbeddingRepository;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    static final int BATCH_SIZE = 100;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingRepository embeddingRepository;
    private final DocQaMetrics metrics;

    @Value("${langchain4j.open-ai.embedding-model.model-name}")
    private String modelName;

    /**
     * Embeds a query string and returns the raw vector. Does not persist.
     * Use this at search time to embed the user's question.
     */
    public float[] embed(String text) {
        return embeddingModel.embed(text).content().vector();
    }

    /**
     * Generates and persists embeddings for a list of {@link DocumentChunk}s.
     * Processes in batches of {@value BATCH_SIZE} to stay within API limits.
     */
    public void embedChunks(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }

        int saved = 0;
        for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
            List<DocumentChunk> batch = chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size()));
            saved += embedAndPersistBatch(batch);
        }

        log.info("Embedded {} chunk(s) using model '{}'", saved, modelName);
    }

    /**
     * Converts a float vector to pgvector's cast-friendly literal form,
     * e.g. {@code [0.1,0.2,0.3]}.
     */
    public String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private int embedAndPersistBatch(List<DocumentChunk> batch) {
        Timer.Sample sample = metrics.startEmbeddingBatch();
        try {
            List<TextSegment> segments = batch.stream()
                    .map(c -> TextSegment.from(c.getContent()))
                    .toList();

            List<dev.langchain4j.data.embedding.Embedding> lc4jEmbeddings =
                    embeddingModel.embedAll(segments).content();

            List<com.docqa.model.Embedding> entities = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                float[] vector = lc4jEmbeddings.get(i).vector();
                entities.add(com.docqa.model.Embedding.builder()
                        .chunk(batch.get(i))
                        .model(modelName)
                        .embedding(vector)
                        .dimension(vector.length)
                        .build());
            }

            embeddingRepository.saveAll(entities);
            metrics.embeddingsGenerated(entities.size());
            return entities.size();
        } finally {
            metrics.stopEmbeddingBatch(sample);
        }
    }
}
