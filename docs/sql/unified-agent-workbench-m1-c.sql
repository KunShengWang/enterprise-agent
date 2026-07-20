-- Unified Agent Workbench V1 / M1-C
-- Immutable route preview and single-instance idempotent dispatch coordination.

ALTER TABLE agent_run_state ADD COLUMN IF NOT EXISTS dispatch_request_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_run_dispatch_request
    ON agent_run_state(dispatch_request_id) WHERE dispatch_request_id IS NOT NULL;

ALTER TABLE agent_incident ADD COLUMN IF NOT EXISTS dispatch_request_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_incident_dispatch_request
    ON agent_incident(dispatch_request_id) WHERE dispatch_request_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS agent_route_preview (
    preview_id TEXT PRIMARY KEY,
    work_item_id TEXT NOT NULL UNIQUE REFERENCES agent_work_item(work_item_id),
    route_decision_id TEXT NOT NULL REFERENCES agent_routing_decision(decision_id),
    target_id TEXT NOT NULL,
    preview_version INT NOT NULL,
    validated_input_digest CHAR(64) NOT NULL,
    scope_digest CHAR(64) NOT NULL,
    payload_json JSONB NOT NULL,
    status TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    confirmed_by TEXT,
    confirmed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_dispatch_attempt (
    attempt_id TEXT PRIMARY KEY,
    work_item_id TEXT NOT NULL REFERENCES agent_work_item(work_item_id),
    dispatch_request_id TEXT NOT NULL,
    attempt_no INT NOT NULL,
    reconciliation BOOLEAN NOT NULL,
    target_id TEXT NOT NULL,
    status TEXT NOT NULL,
    failure_code TEXT,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE(work_item_id, attempt_no),
    UNIQUE(dispatch_request_id, attempt_no)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_dispatch_effective_per_work
    ON agent_dispatch_attempt(work_item_id) WHERE status = 'EFFECTIVE';
CREATE INDEX IF NOT EXISTS idx_dispatch_started
    ON agent_dispatch_attempt(status, created_at);
