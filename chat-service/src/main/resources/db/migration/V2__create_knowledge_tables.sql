-- One row per successfully ingested knowledge archive. Every version is kept; exactly one per dataset is active
-- and only the active version is searched.
CREATE TABLE ingestion_run (
    id              UUID PRIMARY KEY,
    dataset_id      VARCHAR(100) NOT NULL,
    dataset_version VARCHAR(100) NOT NULL,
    source          TEXT         NOT NULL,
    archive_sha256  VARCHAR(64)  NOT NULL,
    chunk_count     INTEGER      NOT NULL CHECK (chunk_count > 0),
    active          BOOLEAN      NOT NULL DEFAULT false,
    ingested_at     TIMESTAMPTZ  NOT NULL,
    activated_at    TIMESTAMPTZ,
    CONSTRAINT ingestion_run_dataset_version_uk UNIQUE (dataset_id, dataset_version)
);

-- At most one active version per dataset, enforced by the database.
CREATE UNIQUE INDEX ingestion_run_one_active_per_dataset ON ingestion_run (dataset_id) WHERE active;

-- Retrieval chunks with their embeddings (OpenAI text-embedding-3-small, 1536 dimensions).
CREATE TABLE knowledge_chunk (
    id               UUID PRIMARY KEY,
    ingestion_run_id UUID         NOT NULL REFERENCES ingestion_run (id) ON DELETE CASCADE,
    chunk_id         VARCHAR(256) NOT NULL,
    document_id      VARCHAR(256) NOT NULL,
    text             TEXT         NOT NULL,
    url              TEXT         NOT NULL,
    title            TEXT,
    fetched_at       TEXT,
    heading_path     TEXT[]       NOT NULL,
    source_urls      TEXT[]       NOT NULL,
    embedding        VECTOR(1536) NOT NULL,
    CONSTRAINT knowledge_chunk_run_chunk_uk UNIQUE (ingestion_run_id, chunk_id)
);

CREATE INDEX knowledge_chunk_embedding_idx ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);
