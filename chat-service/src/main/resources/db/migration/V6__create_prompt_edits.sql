-- One row per request to the AI prompt editor: what the operator asked, which model answered (and how hard it
-- reasoned), what it proposed, what it cost, and what the operator did with it.
CREATE TABLE prompt_edit (
    id                 UUID PRIMARY KEY,
    version_id         UUID         NOT NULL REFERENCES prompt_version (id),  -- the version the proposal was made for
    version_sha256     VARCHAR(64)  NOT NULL,  -- its content then, to detect a version changed since
    parent_edit_id     UUID REFERENCES prompt_edit (id),  -- the proposal this one refines
    instruction        TEXT         NOT NULL,
    target_section     VARCHAR(100),  -- null: the AI chooses the sections
    model              VARCHAR(100) NOT NULL,
    reasoning_effort   VARCHAR(20),
    status             VARCHAR(20)  NOT NULL
        CHECK (status IN ('PROPOSED', 'QUESTION', 'REFUSED', 'FAILED', 'ACCEPTED', 'REFINED', 'DISCARDED')),
    proposal           JSONB,  -- the editor's structured answer
    error              TEXT,
    prompt_tokens      INTEGER,
    completion_tokens  INTEGER,
    reasoning_tokens   INTEGER,
    duration_ms        BIGINT,
    result_version_id  UUID REFERENCES prompt_version (id),  -- the draft an accepted proposal went into
    created_by         VARCHAR(100) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    decided_at         TIMESTAMPTZ
);
CREATE INDEX prompt_edit_version_idx ON prompt_edit (version_id, created_at);
