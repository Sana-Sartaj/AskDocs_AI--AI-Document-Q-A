package com.docqa.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "embeddings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Embedding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chunk_id", nullable = false)
    private DocumentChunk chunk;

    @Column(nullable = false, length = 100)
    private String model;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(nullable = false, columnDefinition = "vector(1536)")
    private float[] embedding;

    @Column(nullable = false)
    @Builder.Default
    private int dimension = 1536;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
