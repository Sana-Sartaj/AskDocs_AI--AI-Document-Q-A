package com.docqa.repository;

import com.docqa.model.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(UUID documentId);

    void deleteByDocumentId(UUID documentId);

    long countByDocumentId(UUID documentId);

    /**
     * Returns chunks ranked by embedding similarity for a given user.
     * Joins through the embeddings table; queryEmbedding must be a pgvector literal.
     */
    @Query(value = """
            SELECT dc.*
            FROM document_chunks dc
            JOIN embeddings e ON e.chunk_id = dc.id
            JOIN documents d ON d.id = dc.document_id
            WHERE d.user_id = :userId
              AND e.model = :model
            ORDER BY e.embedding <=> CAST(:queryEmbedding AS vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<DocumentChunk> findTopKSimilarByUser(
            @Param("userId") UUID userId,
            @Param("model") String model,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("topK") int topK
    );

    /**
     * Same search scoped to specific documents.
     * documentIds must be a Postgres array literal: "{uuid1,uuid2}"
     */
    @Query(value = """
            SELECT dc.*
            FROM document_chunks dc
            JOIN embeddings e ON e.chunk_id = dc.id
            WHERE dc.document_id = ANY(CAST(:documentIds AS uuid[]))
              AND e.model = :model
            ORDER BY e.embedding <=> CAST(:queryEmbedding AS vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<DocumentChunk> findTopKSimilarInDocuments(
            @Param("documentIds") String documentIds,
            @Param("model") String model,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("topK") int topK
    );

    /**
     * ANN search across a user's chunks that also returns the cosine distance.
     * Maps to {@link ChunkSearchResult} via Spring Data's camelCase projection.
     */
    @Query(value = """
            SELECT dc.id,
                   dc.document_id,
                   dc.chunk_index,
                   dc.content,
                   (e.embedding <=> CAST(:queryEmbedding AS vector)) AS distance
            FROM document_chunks dc
            JOIN embeddings e ON e.chunk_id = dc.id
            JOIN documents d ON d.id = dc.document_id
            WHERE d.user_id = :userId
              AND e.model = :model
            ORDER BY distance
            LIMIT :topK
            """, nativeQuery = true)
    List<ChunkSearchResult> findTopKSimilarByUserWithDistance(
            @Param("userId") UUID userId,
            @Param("model") String model,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("topK") int topK
    );

    /**
     * ANN search scoped to specific documents that also returns the cosine distance.
     * documentIds must be a Postgres array literal: "{uuid1,uuid2}"
     */
    @Query(value = """
            SELECT dc.id,
                   dc.document_id,
                   dc.chunk_index,
                   dc.content,
                   (e.embedding <=> CAST(:queryEmbedding AS vector)) AS distance
            FROM document_chunks dc
            JOIN embeddings e ON e.chunk_id = dc.id
            WHERE dc.document_id = ANY(CAST(:documentIds AS uuid[]))
              AND e.model = :model
            ORDER BY distance
            LIMIT :topK
            """, nativeQuery = true)
    List<ChunkSearchResult> findTopKSimilarInDocumentsWithDistance(
            @Param("documentIds") String documentIds,
            @Param("model") String model,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("topK") int topK
    );
}
