package com.docqa.benchmark;

import com.docqa.repository.DocumentChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Benchmark verifying that the HNSW index keeps p95 similarity-search latency
 * under 200 ms at 50 000 stored vectors.
 *
 * Prerequisites
 * ─────────────
 *   • PostgreSQL 16 + pgvector reachable at the URL in application.yml
 *   • Flyway migrations applied (V2 creates the HNSW index)
 *   • hnsw.ef_search set via Hikari connection-init-sql (see application.yml)
 *
 * The test is @Disabled by default.  Run it:
 *   - In IDE: remove @Disabled or right-click → Run
 *   - Maven:  mvn test -Dgroups=benchmark -pl . (requires @Tag("benchmark"))
 */
@Tag("benchmark")
@Disabled("Requires a running PostgreSQL with pgvector. Remove @Disabled to run manually.")
@SpringBootTest
@Slf4j
class VectorSearchBenchmarkTest {

    // ── tuning constants ──────────────────────────────────────────────────────

    private static final int VECTOR_COUNT   = 50_000;
    private static final int DIMENSIONS     = 1536;
    private static final int TOP_K          = 10;
    private static final int WARMUP_ROUNDS  = 30;
    private static final int MEASURED_ROUNDS = 200;
    private static final int INSERT_BATCH   = 500;

    /** Target: p95 latency must stay below this value (milliseconds). */
    private static final long P95_LIMIT_MS  = 200L;

    private static final String EMBED_MODEL = "benchmark-model";

    // ── pre-generated vector pool (avoids regenerating per-query) ────────────

    private static final int POOL_SIZE = 200;
    private static final String[] VECTOR_POOL = buildVectorPool(POOL_SIZE, DIMENSIONS, 42L);

    // ── Spring beans ──────────────────────────────────────────────────────────

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DocumentChunkRepository chunkRepository;

    // ── per-test state ────────────────────────────────────────────────────────

    private UUID userId;
    private UUID documentId;

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @BeforeEach
    void insertTestData() {
        userId     = UUID.randomUUID();
        documentId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role) VALUES (?,?,?,?)",
                userId, "benchmark-" + userId + "@test.local", "$2a$10$bench", "USER");

        jdbcTemplate.update(
                "INSERT INTO documents "
                + "(id, user_id, title, original_filename, s3_key, status, chunk_count) "
                + "VALUES (?,?,?,?,?,?,?)",
                documentId, userId, "Benchmark", "bench.pdf",
                "bench/" + documentId, "PROCESSED", VECTOR_COUNT);

        long t0 = System.currentTimeMillis();
        insertChunksAndEmbeddings(documentId, VECTOR_COUNT);
        log.info("Inserted {} vectors in {} ms", VECTOR_COUNT, System.currentTimeMillis() - t0);
    }

    @AfterEach
    void deleteTestData() {
        // CASCADE: users → documents → document_chunks → embeddings
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        log.info("Cleaned up benchmark data for userId={}", userId);
    }

    // ── benchmark tests ───────────────────────────────────────────────────────

    @Test
    void userScopedSearch_p95Under200ms() {
        long[] latencies = runQueries(
                WARMUP_ROUNDS, MEASURED_ROUNDS,
                i -> chunkRepository.findTopKSimilarByUserWithDistance(
                        userId, EMBED_MODEL, VECTOR_POOL[i % POOL_SIZE], TOP_K));

        LatencyStats stats = LatencyStats.of(latencies);
        logStats("user-scoped search", stats);

        assertThat(stats.p95())
                .as("p95 latency must be ≤ %d ms for %d vectors (user-scoped)",
                        P95_LIMIT_MS, VECTOR_COUNT)
                .isLessThanOrEqualTo(P95_LIMIT_MS);
    }

    @Test
    void documentScopedSearch_p95Under200ms() {
        String pgDocArray = "{" + documentId + "}";

        long[] latencies = runQueries(
                WARMUP_ROUNDS, MEASURED_ROUNDS,
                i -> chunkRepository.findTopKSimilarInDocumentsWithDistance(
                        pgDocArray, EMBED_MODEL, VECTOR_POOL[i % POOL_SIZE], TOP_K));

        LatencyStats stats = LatencyStats.of(latencies);
        logStats("document-scoped search", stats);

        assertThat(stats.p95())
                .as("p95 latency must be ≤ %d ms for %d vectors (document-scoped)",
                        P95_LIMIT_MS, VECTOR_COUNT)
                .isLessThanOrEqualTo(P95_LIMIT_MS);
    }

    @Test
    void searchReturnsTopKResults() {
        var results = chunkRepository.findTopKSimilarByUserWithDistance(
                userId, EMBED_MODEL, VECTOR_POOL[0], TOP_K);

        assertThat(results).hasSize(TOP_K);

        // Distances must be in ascending order (closest first)
        for (int i = 0; i + 1 < results.size(); i++) {
            double d1 = results.get(i).getDistance() != null ? results.get(i).getDistance() : 0;
            double d2 = results.get(i + 1).getDistance() != null ? results.get(i + 1).getDistance() : 0;
            assertThat(d1).isLessThanOrEqualTo(d2);
        }
    }

    @Test
    void allReturnedChunksBelongToUser() {
        var results = chunkRepository.findTopKSimilarByUserWithDistance(
                userId, EMBED_MODEL, VECTOR_POOL[0], TOP_K);

        assertThat(results).isNotEmpty();
        // Every chunk must belong to one of our documents
        results.forEach(r ->
                assertThat(r.getDocumentId()).isEqualTo(documentId));
    }

    // ── insertion helpers ─────────────────────────────────────────────────────

    private void insertChunksAndEmbeddings(UUID docId, int count) {
        UUID[] chunkIds = new UUID[count];
        for (int i = 0; i < count; i++) {
            chunkIds[i] = UUID.randomUUID();
        }

        // Batch-insert document_chunks
        for (int offset = 0; offset < count; offset += INSERT_BATCH) {
            final int o = offset;
            final int size = Math.min(INSERT_BATCH, count - offset);
            jdbcTemplate.batchUpdate(
                    "INSERT INTO document_chunks (id, document_id, chunk_index, content) "
                    + "VALUES (?,?,?,?)",
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int k) throws SQLException {
                            ps.setObject(1, chunkIds[o + k]);
                            ps.setObject(2, docId);
                            ps.setInt(3, o + k);
                            ps.setString(4, "Benchmark chunk " + (o + k));
                        }
                        @Override public int getBatchSize() { return size; }
                    });
        }

        // Batch-insert embeddings
        for (int offset = 0; offset < count; offset += INSERT_BATCH) {
            final int o = offset;
            final int size = Math.min(INSERT_BATCH, count - offset);
            jdbcTemplate.batchUpdate(
                    "INSERT INTO embeddings (id, chunk_id, model, embedding, dimension) "
                    + "VALUES (gen_random_uuid(), ?, ?, CAST(? AS vector), ?)",
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int k) throws SQLException {
                            ps.setObject(1, chunkIds[o + k]);
                            ps.setString(2, EMBED_MODEL);
                            ps.setString(3, VECTOR_POOL[(o + k) % POOL_SIZE]);
                            ps.setInt(4, DIMENSIONS);
                        }
                        @Override public int getBatchSize() { return size; }
                    });
        }
    }

    // ── query runner ──────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface QueryFn {
        void run(int iteration);
    }

    private static long[] runQueries(int warmup, int measured, QueryFn fn) {
        for (int i = 0; i < warmup; i++) {
            fn.run(i);
        }
        long[] latencies = new long[measured];
        for (int i = 0; i < measured; i++) {
            long t0 = System.nanoTime();
            fn.run(i);
            latencies[i] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        }
        return latencies;
    }

    // ── statistics ────────────────────────────────────────────────────────────

    private void logStats(String label, LatencyStats s) {
        log.info("[{}] {}k vectors, topK={} — avg={}ms  p50={}ms  p95={}ms  p99={}ms  max={}ms",
                label, VECTOR_COUNT / 1_000, TOP_K,
                s.avg(), s.p50(), s.p95(), s.p99(), s.max());
    }

    record LatencyStats(long avg, long p50, long p95, long p99, long max) {

        static LatencyStats of(long[] latencies) {
            long[] s = latencies.clone();
            Arrays.sort(s);
            int n = s.length;
            long sum = 0;
            for (long v : s) sum += v;
            return new LatencyStats(
                    sum / n,
                    s[n / 2],
                    s[(int) Math.ceil(n * 0.95) - 1],
                    s[(int) Math.ceil(n * 0.99) - 1],
                    s[n - 1]);
        }
    }

    // ── vector pool ───────────────────────────────────────────────────────────

    /**
     * Pre-generates {@code poolSize} random unit-normalised vector literals.
     * Uses a fixed seed so the same data is reproducible across runs.
     */
    private static String[] buildVectorPool(int poolSize, int dims, long seed) {
        Random rng = new Random(seed);
        String[] pool = new String[poolSize];
        for (int p = 0; p < poolSize; p++) {
            float[] v = new float[dims];
            float norm = 0f;
            for (int d = 0; d < dims; d++) {
                v[d] = rng.nextFloat() * 2 - 1f;
                norm += v[d] * v[d];
            }
            // L2-normalise so cosine distance equals Euclidean distance / 2
            norm = (float) Math.sqrt(norm);
            StringBuilder sb = new StringBuilder(dims * 10 + 2);
            sb.append('[');
            for (int d = 0; d < dims; d++) {
                if (d > 0) sb.append(',');
                sb.append(v[d] / norm);
            }
            pool[p] = sb.append(']').toString();
        }
        return pool;
    }
}
