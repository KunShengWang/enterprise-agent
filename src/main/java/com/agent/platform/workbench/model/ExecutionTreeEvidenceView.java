package com.agent.platform.workbench.model;

import java.time.Instant;
import java.util.Map;

/** Read-only legacy evidence projection. This is not Procurement supplier evidence. */
public record ExecutionTreeEvidenceView(
        String evidenceId,
        String incidentId,
        String taskId,
        String childRunId,
        String evidenceClass,
        String evidenceSubtype,
        String sourceSystem,
        String sourceReference,
        Map<String, Object> queryParameters,
        Instant observedAt,
        Map<String, Object> facts,
        String payloadHash,
        String status,
        String supersedesEvidenceId,
        String idempotencyKey,
        Instant createdAt
) { }
