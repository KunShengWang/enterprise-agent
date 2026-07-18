-- OrderCare Incident Command V1.2 / M1-B
-- PostgreSQL schema. Runtime CREATE TABLE guards mirror this file for local development.

CREATE TABLE IF NOT EXISTS agent_incident (
    incident_id TEXT PRIMARY KEY,
    commander_run_id TEXT UNIQUE,
    reviewer_run_id TEXT UNIQUE,
    conversation_id TEXT NOT NULL,
    scenario_id TEXT NOT NULL,
    status TEXT NOT NULL,
    snapshot_json JSONB NOT NULL,
    delegation_plan_json JSONB,
    assessment_json JSONB,
    clarification_count INT NOT NULL DEFAULT 0,
    max_clarifications INT NOT NULL DEFAULT 1,
    next_event_sequence BIGINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_incident_snapshot
    ON agent_incident ((snapshot_json ->> 'snapshotId'));
CREATE INDEX IF NOT EXISTS idx_agent_incident_status_updated
    ON agent_incident(status, updated_at DESC);

CREATE TABLE IF NOT EXISTS agent_task (
    task_id TEXT PRIMARY KEY,
    incident_id TEXT NOT NULL REFERENCES agent_incident(incident_id),
    client_task_key TEXT NOT NULL,
    task_type TEXT NOT NULL,
    role TEXT NOT NULL,
    objective TEXT NOT NULL,
    priority INT NOT NULL,
    dependencies_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    required_evidence_json JSONB NOT NULL,
    input_payload_json JSONB NOT NULL,
    output_summary_json JSONB,
    status TEXT NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 2,
    child_run_id TEXT,
    first_child_run_id TEXT,
    deadline_at TIMESTAMPTZ NOT NULL,
    claimed_by TEXT,
    claim_until TIMESTAMPTZ,
    last_error TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(incident_id, client_task_key)
);

CREATE INDEX IF NOT EXISTS idx_agent_task_incident_status
    ON agent_task(incident_id, status, priority DESC);

CREATE TABLE IF NOT EXISTS agent_evidence (
    evidence_id TEXT PRIMARY KEY,
    incident_id TEXT NOT NULL REFERENCES agent_incident(incident_id),
    task_id TEXT NOT NULL REFERENCES agent_task(task_id),
    child_run_id TEXT NOT NULL,
    evidence_class TEXT NOT NULL,
    evidence_subtype TEXT NOT NULL,
    source_system TEXT NOT NULL,
    source_reference TEXT NOT NULL,
    query_parameters_json JSONB NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    facts_json JSONB NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status TEXT NOT NULL,
    supersedes_evidence_id TEXT,
    idempotency_key TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_evidence_incident_subtype
    ON agent_evidence(incident_id, evidence_subtype, created_at);

CREATE TABLE IF NOT EXISTS agent_task_event (
    event_id TEXT PRIMARY KEY,
    incident_id TEXT NOT NULL REFERENCES agent_incident(incident_id),
    task_id TEXT REFERENCES agent_task(task_id),
    child_run_id TEXT,
    event_sequence BIGINT NOT NULL,
    event_type TEXT NOT NULL,
    event_category TEXT NOT NULL,
    actor_type TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    sender_role TEXT,
    recipient_role TEXT,
    message_depth INT NOT NULL DEFAULT 0,
    correlation_id TEXT,
    causation_id TEXT,
    idempotency_key TEXT NOT NULL UNIQUE,
    payload_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(incident_id, event_sequence)
);

CREATE INDEX IF NOT EXISTS idx_agent_task_event_incident_sequence
    ON agent_task_event(incident_id, event_sequence);
