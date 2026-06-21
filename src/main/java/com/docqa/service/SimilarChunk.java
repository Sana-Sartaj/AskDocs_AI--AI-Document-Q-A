package com.docqa.service;

import java.util.UUID;

/**
 * Result of a vector similarity search.
 *
 * {@code similarity} is the cosine similarity score (1 − cosine_distance).
 * Range [−1, 1]; higher means more similar. In practice document embeddings
 * stay in [0, 1] because the vectors are non-negative after the model's
 * activation function.
 */
public record SimilarChunk(
        UUID chunkId,
        UUID documentId,
        int chunkIndex,
        String content,
        double similarity
) {}
