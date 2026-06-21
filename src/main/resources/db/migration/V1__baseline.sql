-- Enable required PostgreSQL extensions
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ─── users ──────────────────────────────────────────────────────────────────

CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255),
    role          VARCHAR(50)  NOT NULL DEFAULT 'USER',
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users (email);

-- ─── documents ───────────────────────────────────────────────────────────────

CREATE TABLE documents (
    id                UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id           UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title             VARCHAR(500) NOT NULL,
    original_filename VARCHAR(500) NOT NULL,
    s3_key            VARCHAR(1000) NOT NULL,
    content_type      VARCHAR(100),
    file_size_bytes   BIGINT,
    status            VARCHAR(50)  NOT NULL DEFAULT 'UPLOADED',
    chunk_count       INTEGER      NOT NULL DEFAULT 0,
    error_message     TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_documents_user_id ON documents (user_id);
CREATE INDEX idx_documents_status  ON documents (status);

-- ─── document_chunks ─────────────────────────────────────────────────────────

CREATE TABLE document_chunks (
    id          UUID     PRIMARY KEY DEFAULT uuid_generate_v4(),
    document_id UUID     NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index INTEGER  NOT NULL,
    content     TEXT     NOT NULL,
    metadata    JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (document_id, chunk_index)
);

CREATE INDEX idx_document_chunks_document_id ON document_chunks (document_id);

-- ─── embeddings ──────────────────────────────────────────────────────────────

CREATE TABLE embeddings (
    id         UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    chunk_id   UUID         NOT NULL REFERENCES document_chunks(id) ON DELETE CASCADE,
    model      VARCHAR(100) NOT NULL,
    -- 1536 dims = text-embedding-3-small / text-embedding-ada-002
    embedding  vector(1536) NOT NULL,
    dimension  INTEGER      NOT NULL DEFAULT 1536,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_embeddings_chunk_id ON embeddings (chunk_id);
-- IVFFlat approximate nearest-neighbour index (cosine distance)
CREATE INDEX idx_embeddings_embedding
    ON embeddings
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

-- ─── chat_sessions ───────────────────────────────────────────────────────────

CREATE TABLE chat_sessions (
    id           UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title        VARCHAR(500),
    document_ids UUID[],
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_sessions_user_id ON chat_sessions (user_id);

-- ─── chat_messages ───────────────────────────────────────────────────────────

CREATE TABLE chat_messages (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id  UUID        NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role        VARCHAR(50)  NOT NULL,
    content     TEXT        NOT NULL,
    token_count INTEGER,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_messages_session_id ON chat_messages (session_id);

-- ─── updated_at trigger ──────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_documents_updated_at
    BEFORE UPDATE ON documents
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_chat_sessions_updated_at
    BEFORE UPDATE ON chat_sessions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
