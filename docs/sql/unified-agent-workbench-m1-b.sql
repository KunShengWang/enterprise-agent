-- Unified Agent Workbench V1 / M1-B
-- Command classification, constrained routing and routing recovery only.
-- No adapter, dispatch, preview, SSE or projector is introduced here.

ALTER TABLE agent_work_input
    ALTER COLUMN goal_origin DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS principal_roles JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS input_kind TEXT NOT NULL DEFAULT 'NORMAL_GOAL',
    ADD COLUMN IF NOT EXISTS command_type TEXT,
    ADD COLUMN IF NOT EXISTS target_work_item_id TEXT,
    ADD COLUMN IF NOT EXISTS classification_status TEXT NOT NULL DEFAULT 'CLASSIFIED',
    ADD COLUMN IF NOT EXISTS classification_reason TEXT,
    ADD COLUMN IF NOT EXISTS classified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS agent_work_command_decision (
    command_decision_id TEXT PRIMARY KEY,
    input_id TEXT NOT NULL REFERENCES agent_work_input(input_id),
    conversation_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    owner_principal_id TEXT NOT NULL,
    focused_work_item_id TEXT,
    attempt_no INT NOT NULL,
    classifier_type TEXT NOT NULL,
    decision_status TEXT NOT NULL,
    command_type TEXT,
    model_name TEXT,
    prompt_digest CHAR(64),
    raw_output_digest CHAR(64),
    decision_json JSONB,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    model_confidence DOUBLE PRECISION NOT NULL DEFAULT 0,
    failure_code TEXT,
    failure_reason TEXT,
    trace_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE(input_id, attempt_no)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_work_command_effective_per_input
    ON agent_work_command_decision(input_id)
    WHERE decision_status = 'EFFECTIVE';

CREATE INDEX IF NOT EXISTS idx_work_command_decision_conversation
    ON agent_work_command_decision(tenant_id, owner_principal_id, conversation_id, created_at);

CREATE TABLE IF NOT EXISTS agent_routing_decision (
    decision_id TEXT PRIMARY KEY,
    work_item_id TEXT NOT NULL REFERENCES agent_work_item(work_item_id),
    routing_request_id TEXT NOT NULL,
    attempt_no INT NOT NULL,
    decision_status TEXT NOT NULL,
    model_name TEXT,
    target_catalog_version TEXT NOT NULL,
    prompt_digest CHAR(64),
    raw_output_digest CHAR(64),
    decision_json JSONB,
    validation_json JSONB,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    failure_code TEXT,
    failure_reason TEXT,
    trace_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE(work_item_id, attempt_no),
    UNIQUE(routing_request_id, attempt_no)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_routing_effective_per_work
    ON agent_routing_decision(work_item_id)
    WHERE decision_status = 'EFFECTIVE';

CREATE INDEX IF NOT EXISTS idx_routing_decision_request
    ON agent_routing_decision(routing_request_id, attempt_no);

CREATE INDEX IF NOT EXISTS idx_work_item_stale_routing
    ON agent_work_item(control_state, routing_next_retry_at, routing_last_attempt_at)
    WHERE control_state = 'ROUTING';
