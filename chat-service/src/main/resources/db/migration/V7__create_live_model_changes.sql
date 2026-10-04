-- Every change of the model that answers visitors, with the operator's reason. The latest row is the live model;
-- with no rows, the configured default (OPENAI_CHAT_MODEL) is live.
CREATE TABLE live_model_change (
    id              UUID PRIMARY KEY,
    model           VARCHAR(100) NOT NULL,
    previous_model  VARCHAR(100) NOT NULL,
    reason          TEXT         NOT NULL,
    changed_by      VARCHAR(100) NOT NULL,
    changed_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX live_model_change_time_idx ON live_model_change (changed_at DESC);

-- The model change in force when a reply was generated (null: the configured default).
ALTER TABLE chat_message ADD COLUMN model_change_id UUID REFERENCES live_model_change (id);
