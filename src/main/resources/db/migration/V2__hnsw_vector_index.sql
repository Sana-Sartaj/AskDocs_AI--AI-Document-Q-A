-- Replace the IVFFlat approximate-nearest-neighbour index with HNSW.
--
-- Motivation:
--   IVFFlat requires periodic VACUUM to maintain list quality and needs a
--   separate ANALYZE pass to choose the right number of probes.  HNSW builds
--   a navigable graph that stays accurate without maintenance and delivers
--   consistently lower latency at 50k-1M vectors.
--
-- Tuning knobs:
--   m              = 16    max edges per node (memory ↔ recall trade-off)
--   ef_construction = 64   beam width during build (quality ↔ build time)
-- Runtime tuning:   SET hnsw.ef_search = 100   (recall ↔ query speed)
--
-- Target: p95 < 200 ms for 50 000 vectors with ef_search = 100.

-- ── remove the old IVFFlat index ──────────────────────────────────────────────
DROP INDEX IF EXISTS idx_embeddings_embedding;

-- ── HNSW index for cosine-distance ANN search ────────────────────────────────
CREATE INDEX idx_embeddings_embedding_hnsw
    ON embeddings
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- ── Supporting b-tree indexes for the JOIN-filter path ───────────────────────
-- Allows the planner to pre-filter by model before entering the vector scan,
-- reducing candidates and keeping the HNSW graph traversal selective.

CREATE INDEX IF NOT EXISTS idx_embeddings_chunk_model
    ON embeddings (chunk_id, model);

-- Partial composite index: the ANN queries always filter by model; this lets
-- PostgreSQL push the model predicate into an index scan on the FK join.
CREATE INDEX IF NOT EXISTS idx_embeddings_model
    ON embeddings (model);
