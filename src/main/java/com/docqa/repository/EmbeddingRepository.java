package com.docqa.repository;

import com.docqa.model.Embedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmbeddingRepository extends JpaRepository<Embedding, UUID> {

    Optional<Embedding> findByChunkIdAndModel(UUID chunkId, String model);

    List<Embedding> findByChunkId(UUID chunkId);

    void deleteByChunkId(UUID chunkId);

    /**
     * ANN cosine-distance search across all chunks belonging to a user.
     * The query embedding must be passed as a pgvector literal: "[0.1,0.2,...]"
     */
    @Query(value = """
            SELECT e.*
            FROM embeddings e
            JOIN document_chunks dc ON dc.id = e.chunk_id
            JOIN documents d ON d.id = dc.document_id
            WHERE d.user_id = :userId
              AND e.model = :model
            ORDER BY e.embedding <=> CAST(:queryEmbedding AS vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<Embedding> findTopKByUser(
            @Param("userId") UUID userId,
            @Param("model") String model,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("topK") int topK
    );

    /**
     * ANN cosine-distance search scoped to a specific set of documents.
     * documentIds must be formatted as a Postgres array literal: "{uuid1,uuid2}"
     */
    @Query(value = """
            SELECT e.*
            FROM embeddings e
            JOIN document_chunks dc ON dc.id = e.chunk_id
            WHERE dc.document_id = ANY(CAST(:documentIds AS uuid[]))
              AND e.model = :model
            ORDER BY e.embedding <=> CAST(:queryEmbedding AS vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<Embedding> findTopKByDocuments(
            @Param("documentIds") String documentIds,
            @Param("model") String model,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("topK") int topK
    );
}
