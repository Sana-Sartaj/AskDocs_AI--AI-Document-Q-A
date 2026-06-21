package com.docqa.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.vector")
@Getter
@Setter
public class VectorSearchProperties {

    /** Embedding vector dimensions. Must match the pgvector column definition. */
    private int embeddingDimension = 1536;

    /** Default number of nearest neighbours to return from ANN searches. */
    private int similarityTopK = 5;

    /** HNSW runtime beam-search width (higher = better recall, slower query). */
    private int efSearch = 100;

    /** IVFFlat probe count kept for backwards compatibility / fallback. */
    private int probes = 10;
}
