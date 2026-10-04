-- Eval cases, editable in the console (loaded from evals/cases.json on first start).
CREATE TABLE eval_case (
    id          VARCHAR(100) PRIMARY KEY,
    definition  JSONB        NOT NULL,  -- the case in the cases.json format
    enabled     BOOLEAN      NOT NULL DEFAULT true,
    updated_by  VARCHAR(100) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

-- One eval run: a prompt version and a model against a set of cases. The browser runs the cases one by one.
CREATE TABLE eval_run (
    id                 UUID PRIMARY KEY,
    prompt_version_id  UUID         NOT NULL REFERENCES prompt_version (id),
    model              VARCHAR(100) NOT NULL,
    judge              BOOLEAN      NOT NULL,
    full_run           BOOLEAN      NOT NULL,  -- every enabled case (only full runs can allow a prompt activation)
    case_ids           JSONB        NOT NULL,
    status             VARCHAR(20)  NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED')),
    passed_cases       INTEGER      NOT NULL DEFAULT 0,  -- behaviour checks passed
    created_by         VARCHAR(100) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    finished_at        TIMESTAMPTZ
);
CREATE INDEX eval_run_prompt_idx ON eval_run (prompt_version_id, created_at);

CREATE TABLE eval_result (
    id          UUID PRIMARY KEY,
    run_id      UUID         NOT NULL REFERENCES eval_run (id) ON DELETE CASCADE,
    case_id     VARCHAR(100) NOT NULL,
    passed      BOOLEAN      NOT NULL,  -- behaviour checks passed
    detail      JSONB        NOT NULL,  -- the case as run, the replies and every check
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT eval_result_run_case_uk UNIQUE (run_id, case_id)
);
