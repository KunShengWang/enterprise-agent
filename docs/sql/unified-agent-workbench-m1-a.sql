-- Unified Agent Workbench V1 / M1-A
-- Product-control persistence only. Router, dispatch, projector, SSE and UI are deliberately absent.

CREATE TABLE IF NOT EXISTS agent_work_input (
    input_id TEXT PRIMARY KEY,
    client_input_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    owner_principal_id TEXT NOT NULL,
    content TEXT NOT NULL,
    content_digest CHAR(64) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    goal_origin TEXT NOT NULL,
    command_decision_id TEXT,
    parent_work_item_id TEXT,
    relation_type TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(tenant_id, owner_principal_id, client_input_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_work_input_conversation
    ON agent_work_input(tenant_id, owner_principal_id, conversation_id, created_at);

CREATE TABLE IF NOT EXISTS agent_work_item (
    work_item_id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    owner_principal_id TEXT NOT NULL,
    original_goal TEXT NOT NULL,
    normalized_goal TEXT NOT NULL,
    control_state TEXT NOT NULL,
    execution_state TEXT NOT NULL,
    outcome TEXT NOT NULL,
    active_execution_target TEXT,
    active_run_id TEXT,
    active_incident_id TEXT,
    active_recovery_plan_id TEXT,
    route_decision_id TEXT,
    source_input_id TEXT NOT NULL REFERENCES agent_work_input(input_id),
    parent_work_item_id TEXT REFERENCES agent_work_item(work_item_id),
    routing_request_id TEXT NOT NULL UNIQUE,
    routing_attempt_count INT NOT NULL DEFAULT 0,
    routing_last_attempt_at TIMESTAMPTZ,
    routing_next_retry_at TIMESTAMPTZ,
    routing_failure_code TEXT,
    dispatch_request_id TEXT,
    next_event_sequence BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE(source_input_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_work_item_conversation
    ON agent_work_item(tenant_id, owner_principal_id, conversation_id, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_work_item_control_state
    ON agent_work_item(control_state, updated_at);

CREATE TABLE IF NOT EXISTS agent_conversation_work_state (
    conversation_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    owner_principal_id TEXT NOT NULL,
    focused_work_item_id TEXT REFERENCES agent_work_item(work_item_id),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(tenant_id, owner_principal_id, conversation_id),
    UNIQUE(conversation_id)
);

CREATE TABLE IF NOT EXISTS agent_work_relation (
    source_work_item_id TEXT NOT NULL REFERENCES agent_work_item(work_item_id),
    target_work_item_id TEXT NOT NULL REFERENCES agent_work_item(work_item_id),
    relation_type TEXT NOT NULL,
    created_by_input_id TEXT NOT NULL REFERENCES agent_work_input(input_id),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(source_work_item_id, target_work_item_id, relation_type),
    CHECK(source_work_item_id <> target_work_item_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_work_relation_target
    ON agent_work_relation(target_work_item_id, relation_type);

CREATE TABLE IF NOT EXISTS agent_work_link (
    work_item_id TEXT NOT NULL REFERENCES agent_work_item(work_item_id),
    dispatch_request_id TEXT,
    link_type TEXT NOT NULL,
    linked_id TEXT NOT NULL,
    relation TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(work_item_id, link_type, linked_id),
    UNIQUE(dispatch_request_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_work_link_work_item
    ON agent_work_link(work_item_id, created_at);

CREATE TABLE IF NOT EXISTS agent_work_event (
    event_id TEXT PRIMARY KEY,
    work_item_id TEXT NOT NULL REFERENCES agent_work_item(work_item_id),
    sequence BIGINT NOT NULL,
    source_type TEXT NOT NULL,
    source_id TEXT NOT NULL,
    source_event_id TEXT NOT NULL,
    source_sequence BIGINT,
    event_type TEXT NOT NULL,
    phase TEXT,
    summary TEXT,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id TEXT NOT NULL,
    causation_id TEXT,
    source_created_at TIMESTAMPTZ NOT NULL,
    projected_at TIMESTAMPTZ NOT NULL,
    UNIQUE(work_item_id, sequence),
    UNIQUE(work_item_id, source_type, source_id, source_event_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_work_event_sequence
    ON agent_work_event(work_item_id, sequence);

COMMENT ON COLUMN agent_work_item.next_event_sequence IS
    'Next product event sequence. Allocate under a row lock; never use SELECT MAX(sequence) + 1.';
COMMENT ON COLUMN agent_work_item.routing_request_id IS
    'Stable server-generated routing id. M1-A persists it but never invokes Router.';
