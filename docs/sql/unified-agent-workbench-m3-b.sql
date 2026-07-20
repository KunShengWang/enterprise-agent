-- Unified Agent Workbench V1 / M3-B
-- Hierarchical WorkItem / Incident budget account and reservation ledger.

CREATE TABLE IF NOT EXISTS agent_budget_account (
    account_id TEXT PRIMARY KEY,
    owner_type TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    parent_account_id TEXT,
    tenant_id TEXT NOT NULL,
    owner_principal_id TEXT NOT NULL,
    status TEXT NOT NULL,
    max_model_calls INT NOT NULL,
    max_tokens BIGINT NOT NULL,
    max_tool_calls INT NOT NULL,
    max_duration_millis BIGINT NOT NULL,
    max_estimated_cost DOUBLE PRECISION NOT NULL,
    reserved_model_calls INT NOT NULL DEFAULT 0,
    reserved_tokens BIGINT NOT NULL DEFAULT 0,
    reserved_tool_calls INT NOT NULL DEFAULT 0,
    reserved_duration_millis BIGINT NOT NULL DEFAULT 0,
    reserved_estimated_cost DOUBLE PRECISION NOT NULL DEFAULT 0,
    consumed_model_calls INT NOT NULL DEFAULT 0,
    consumed_tokens BIGINT NOT NULL DEFAULT 0,
    consumed_tool_calls INT NOT NULL DEFAULT 0,
    consumed_duration_millis BIGINT NOT NULL DEFAULT 0,
    consumed_estimated_cost DOUBLE PRECISION NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(owner_type, owner_id)
);

CREATE TABLE IF NOT EXISTS agent_budget_reservation (
    reservation_id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES agent_budget_account(account_id),
    operation_key TEXT NOT NULL,
    category TEXT NOT NULL,
    status TEXT NOT NULL,
    reserved_model_calls INT NOT NULL,
    reserved_tokens BIGINT NOT NULL,
    reserved_tool_calls INT NOT NULL,
    reserved_duration_millis BIGINT NOT NULL,
    reserved_estimated_cost DOUBLE PRECISION NOT NULL,
    consumed_model_calls INT NOT NULL DEFAULT 0,
    consumed_tokens BIGINT NOT NULL DEFAULT 0,
    consumed_tool_calls INT NOT NULL DEFAULT 0,
    consumed_duration_millis BIGINT NOT NULL DEFAULT 0,
    consumed_estimated_cost DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    settled_at TIMESTAMPTZ,
    UNIQUE(account_id, operation_key)
);

CREATE INDEX IF NOT EXISTS idx_budget_owner
    ON agent_budget_account(tenant_id, owner_principal_id, owner_type, owner_id);

CREATE INDEX IF NOT EXISTS idx_budget_reservation_status
    ON agent_budget_reservation(account_id, status, created_at);
