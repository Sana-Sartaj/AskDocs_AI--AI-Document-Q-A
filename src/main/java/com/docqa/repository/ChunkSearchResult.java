package com.docqa.repository;

import java.util.UUID;

/**
 * Native-query projection returned by vector similarity searches.
 * Column aliases in the SQL must match these getter names after
 * Spring Data's camelCase ↔ snake_case normalisation:
 *   id, document_id, chunk_index, content, distance
 */
public interface ChunkSearchResult {
    UUID getId();
    UUID getDocumentId();
    int getChunkIndex();
    String getContent();
    Double getDistance();
}
