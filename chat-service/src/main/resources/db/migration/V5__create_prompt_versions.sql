-- Versions of the agent's system prompt, edited by the operator in sections. Every version is kept; exactly one per
-- prompt is active (the one visitors get). The first version is stored from prompts/sales-system.md on first start.
CREATE TABLE prompt_version (
    id               UUID PRIMARY KEY,
    name             VARCHAR(100) NOT NULL,
    version_number   INTEGER      NOT NULL CHECK (version_number > 0),
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED', 'DISCARDED')),
    sections         JSONB        NOT NULL,  -- [{key, title, body}] in prompt order
    content          TEXT         NOT NULL,  -- the sections joined: exactly what the model receives
    content_sha256   VARCHAR(64)  NOT NULL,
    base_version_id  UUID REFERENCES prompt_version (id),  -- the active version a draft was started from
    note             TEXT,
    created_by       VARCHAR(100) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    activated_at     TIMESTAMPTZ,
    activated_by     VARCHAR(100),
    CONSTRAINT prompt_version_number_uk UNIQUE (name, version_number)
);

-- At most one active version per prompt, enforced by the database.
CREATE UNIQUE INDEX prompt_version_one_active ON prompt_version (name) WHERE status = 'ACTIVE';

-- Which prompt version produced each reply (null for fixed messages and replies from before versioning).
ALTER TABLE chat_message ADD COLUMN prompt_version_id UUID REFERENCES prompt_version (id);
