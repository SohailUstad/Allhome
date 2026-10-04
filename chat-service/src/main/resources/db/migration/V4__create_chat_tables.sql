-- Chat persistence, copied from the prototype's schema.sql (the chat part, final shape of each table).
-- The prototype's observability tables (chat_turn, execution_artifact, audit_outbox) are not used.

CREATE TABLE chat_conversation (
    id                    UUID PRIMARY KEY,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Human handoff: set when the assistant could not answer from knowledge or the visitor asked for a person.
    handoff_requested_at  TIMESTAMPTZ,
    handoff_reason        TEXT,
    -- Channel the conversation started on (ZOHO_SALESIQ website chat, INSTAGRAM DM, WEB_CHAT).
    channel               VARCHAR(20) NOT NULL DEFAULT 'ZOHO_SALESIQ',
    -- Operator marks a handed-off conversation as followed up.
    handled_at            TIMESTAMPTZ,
    -- Set when a SalesIQ chat was transferred to a human operator; the AI never answers that conversation again.
    forwarded_at          TIMESTAMPTZ
);
CREATE INDEX chat_conversation_handoff_idx ON chat_conversation (handoff_requested_at)
    WHERE handoff_requested_at IS NOT NULL;

CREATE TABLE chat_message (
    id                 BIGSERIAL PRIMARY KEY,
    conversation_id    UUID NOT NULL REFERENCES chat_conversation(id) ON DELETE CASCADE,
    role               VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content            TEXT NOT NULL,
    sources            JSONB,
    model              VARCHAR(100),
    prompt_tokens      INTEGER,
    completion_tokens  INTEGER,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    handoff            BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX chat_message_conversation_idx ON chat_message (conversation_id, id);

-- Lead profile built up across the conversation (one row per conversation). Contains personal data.
CREATE TABLE chat_lead (
    conversation_id  UUID PRIMARY KEY REFERENCES chat_conversation(id) ON DELETE CASCADE,
    status           VARCHAR(20) NOT NULL,
    persona          VARCHAR(40) NOT NULL,
    intent           VARCHAR(40) NOT NULL,
    name             TEXT,
    phone            TEXT,
    email            TEXT,
    city             TEXT,
    project_type     TEXT,
    spaces           TEXT,
    finish_interest  TEXT,
    area_size        TEXT,
    timeline         TEXT,
    budget           TEXT,
    callback_time    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX chat_lead_status_idx ON chat_lead (status, updated_at);
