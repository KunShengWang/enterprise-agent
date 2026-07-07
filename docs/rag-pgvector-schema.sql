CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS rag_chunk (
    chunk_id TEXT PRIMARY KEY,
    source TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    embedding vector(1536) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rag_chunk_source ON rag_chunk(source);

-- Optional after data is inserted and pgvector version supports HNSW:
-- CREATE INDEX IF NOT EXISTS idx_rag_chunk_embedding_hnsw
-- ON rag_chunk USING hnsw (embedding vector_cosine_ops);

-- Optional alternative for pgvector IVFFlat:
-- CREATE INDEX IF NOT EXISTS idx_rag_chunk_embedding_ivfflat
-- ON rag_chunk USING ivfflat (embedding vector_cosine_ops)
-- WITH (lists = 100);
