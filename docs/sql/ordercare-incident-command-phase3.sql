-- OrderCare Incident Command Phase 3: multi-instance task lease and fencing migration.
-- Recovery Item lease state is persisted inside the bounded Recovery Plan JSON aggregate.

ALTER TABLE agent_task
    ADD COLUMN IF NOT EXISTS fencing_token BIGINT NOT NULL DEFAULT 0;

ALTER TABLE agent_task
    ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_agent_task_stale_lease
    ON agent_task(status, claim_until)
    WHERE status IN ('CLAIMED', 'RUNNING');

COMMENT ON COLUMN agent_task.fencing_token IS
    'Monotonic token incremented by every lease claim/takeover; stale owners cannot commit results.';

COMMENT ON COLUMN agent_task.last_heartbeat_at IS
    'Last successful lease renewal by the current claimed_by owner.';
