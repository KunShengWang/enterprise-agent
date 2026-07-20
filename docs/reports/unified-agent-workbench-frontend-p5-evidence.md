# Unified Agent Workbench Frontend P5 Evidence

> Date: 2026-07-21
> Baseline: P4 checkpoint `ffdf5dc1a3aa02c574ba851928faad6a9ec868de`
> Scope: Technical Execution Inspector
> Result: PASSED

## 1. Inspector architecture

The right side now has a fixed technical summary and five focused tabs:

```text
ExecutionInspector
|- fixed state/metrics/identifier summary
|- Activity
|- Agents
|- Tools
|- Evidence
|- Diagnostics
`- EventPayloadDrawer
```

Pure projection logic lives in `inspectorProjection.ts`, separate from rendering. Raw technical facts are not copied into the middle public conversation.

## 2. Fixed summary

The Inspector keeps separate values for WorkControlState, WorkExecutionState, WorkOutcome and target Run/Incident/Plan state. It also shows duration, token usage, Tool count, Agent count, projector lag, identifiers and budget. No synthetic combined `status` field is introduced.

## 3. Activity

WorkEvents are sorted by authoritative WorkEvent sequence and grouped into input, routing, dispatch, context, model, tool, Agent collaboration, approval, retry/recovery, budget and final result. Filters cover all/error/tool/model/approval/recovery, with free-text search over summary, phase, type and source.

Rows expose sequence, source type/ID, source time, attempt, duration and derived visual status. The complete payload is no longer expanded inline; selecting a row opens the shared drawer.

## 4. Payload drawer and security

The drawer shows event/work/source IDs, source/work sequence, correlation/causation, Run/trace ID, occurredAt and projectedAt. Copy actions are available for identifiers.

Payload values pass through a bounded recursive sanitizer. Password, secret, token, authorization, cookie, API key, system prompt, prompt, reasoning and chain-of-thought keys are redacted. Oversized strings, arrays and recursion depth are bounded. `INTERNAL` Presentation records remain excluded before browser state and are additionally excluded from Tool projection.

## 5. Agents, Tools and Evidence

- Agents shows Coordinator, Commander, Specialist, Reviewer, Planner and single-Agent nodes when present, including attempt, status, duration, token, Tool count and errors.
- Tools aggregates request/result by authoritative toolCallId and uses only PublicPresentation tool fields. Raw Tool arguments are never used.
- Evidence directly renders the Execution Tree projection: Evidence, Conflict, Assessment, Preview/Proposal, Approval and Recovery Plan. It does not build a second business-fact store.

## 6. Diagnostics

Diagnostics shows both SSE states, reconnect count, all three cursors, last event, gap, sync error and projector lag. UNKNOWN, reconciliation, lease/takeover, fencing, budget exhausted, retry, recovery and failure events are searchable through their technical event records.

## 7. Automated evidence

The P5 smoke verifies:

- all Activity categories;
- stable sequence sorting;
- filter and search;
- event detail drawer contract;
- PUBLIC and INSPECTOR_ONLY Tool projection;
- INTERNAL exclusion;
- Tool request/result aggregation and public argument preservation;
- payload sensitive-key redaction;
- Agent Tree/Coordinator rendering hooks;
- transport/gap/sync diagnostics;
- UNKNOWN/reconciliation/fencing/budget recovery classification;
- empty states in each tab;
- 10,000 event projection within the bounded performance gate.

## 8. Files

- `frontend/src/utils/inspectorProjection.ts`
- `frontend/src/components/EventPayloadDrawer.vue`
- `frontend/src/components/ExecutionInspector.vue`
- `frontend/src/composables/usePrimaryRunStream.ts`
- `frontend/src/types/workbench.ts`
- `frontend/src/views/UnifiedWorkbench.vue`
- `frontend/src/styles.css`
- `frontend/scripts/workbench-p5-smoke.mjs`
- `frontend/package.json`
- `docs/reports/unified-agent-workbench-frontend-p5-evidence.md`

P6 visual token normalization, accessibility and screenshot acceptance have not started in this checkpoint.
