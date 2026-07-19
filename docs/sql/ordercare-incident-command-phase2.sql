-- OrderCare Incident Command / Phase 2 Recovery Planner
-- Recovery Plan is an enterprise-agent aggregate. FlowOrder remains the authority for Proposal and Action.

CREATE TABLE IF NOT EXISTS agent_incident_recovery_plan (
    plan_id TEXT PRIMARY KEY,
    incident_id TEXT NOT NULL REFERENCES agent_incident(incident_id),
    request_key TEXT NOT NULL,
    planner_run_id TEXT UNIQUE,
    assessment_digest CHAR(64) NOT NULL,
    status TEXT NOT NULL,
    outcome TEXT NOT NULL,
    record_json JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(incident_id, request_key)
);

CREATE INDEX IF NOT EXISTS idx_incident_recovery_plan_incident
    ON agent_incident_recovery_plan(incident_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_incident_recovery_plan_status
    ON agent_incident_recovery_plan(status, updated_at DESC);
