-- Unified Agent Workbench V1 / M3-C
-- Multi-instance claim, lease and fencing for control-plane recovery.

ALTER TABLE agent_routing_decision ADD COLUMN IF NOT EXISTS lease_owner TEXT;
ALTER TABLE agent_routing_decision ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;
ALTER TABLE agent_routing_decision ADD COLUMN IF NOT EXISTS fencing_token BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_routing_lease
    ON agent_routing_decision(decision_status, lease_until);

ALTER TABLE agent_dispatch_attempt ADD COLUMN IF NOT EXISTS lease_owner TEXT;
ALTER TABLE agent_dispatch_attempt ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;
ALTER TABLE agent_dispatch_attempt ADD COLUMN IF NOT EXISTS fencing_token BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_dispatch_lease
    ON agent_dispatch_attempt(status, lease_until);

ALTER TABLE agent_work_projection_cursor ADD COLUMN IF NOT EXISTS lease_owner TEXT;
ALTER TABLE agent_work_projection_cursor ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;
ALTER TABLE agent_work_projection_cursor ADD COLUMN IF NOT EXISTS fencing_token BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_work_projection_lease
    ON agent_work_projection_cursor(lease_until, updated_at);
