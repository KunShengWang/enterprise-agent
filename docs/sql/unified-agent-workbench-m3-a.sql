-- Unified Agent Workbench V1 / M3-A
-- Durable multi-instance WorkCommand claim, fencing and result audit.

CREATE TABLE IF NOT EXISTS agent_work_command_execution (
    command_request_id TEXT PRIMARY KEY,
    input_id TEXT NOT NULL UNIQUE REFERENCES agent_work_input(input_id),
    work_item_id TEXT REFERENCES agent_work_item(work_item_id),
    tenant_id TEXT NOT NULL,
    owner_principal_id TEXT NOT NULL,
    command_type TEXT NOT NULL,
    admitted_work_version BIGINT NOT NULL,
    status TEXT NOT NULL,
    lease_owner TEXT,
    lease_until TIMESTAMPTZ,
    claim_token BIGINT NOT NULL DEFAULT 1,
    result_code TEXT,
    underlying_execution_changed BOOLEAN NOT NULL DEFAULT FALSE,
    underlying_run_id TEXT,
    message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_work_command_active_per_work
    ON agent_work_command_execution(work_item_id) WHERE status = 'EXECUTING';

CREATE INDEX IF NOT EXISTS idx_work_command_execution_owner
    ON agent_work_command_execution(
        tenant_id,
        owner_principal_id,
        work_item_id,
        created_at
    );
